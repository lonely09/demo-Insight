package com.insightserver.knowledgebase.service;

import com.insightserver.knowledgebase.chunk.Chunker;
import com.insightserver.knowledgebase.config.KbProperties;
import com.insightserver.knowledgebase.domain.KbChunk;
import com.insightserver.knowledgebase.domain.KbDocument;
import com.insightserver.knowledgebase.embedding.EmbeddingClient;
import com.insightserver.knowledgebase.repository.KbChunkRepository;
import com.insightserver.knowledgebase.repository.KbDocumentRepository;
import com.insightserver.knowledgebase.retrieval.bm25.Bm25Index;
import com.insightserver.knowledgebase.support.KbServiceException;
import com.insightserver.knowledgebase.vector.MysqlVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库流水线：切块 → 批量 Embedding → 写入 MySQL（正文+向量）→ 增量更新 BM25 索引。
 */
@Service
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final Bm25Index bm25Index;
    private final KbProperties props;
    private final ObjectMapper mapper;

    public KbIngestService(KbDocumentRepository documentRepository, KbChunkRepository chunkRepository,
                           Chunker chunker, EmbeddingClient embeddingClient,
                           Bm25Index bm25Index, KbProperties props, ObjectMapper mapper) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.bm25Index = bm25Index;
        this.props = props;
        this.mapper = mapper;
    }

    public KbDocument ingest(String title, String content, String source) {
        KbDocument doc = new KbDocument();
        doc.setTitle(title);
        doc.setSource(source == null || source.isBlank() ? "manual" : source);
        doc.setStatus(KbDocument.STATUS_PROCESSING);
        doc = documentRepository.save(doc);

        long start = System.currentTimeMillis();
        try {
            List<String> pieces = chunker.split(content);
            if (pieces.isEmpty()) {
                throw new KbServiceException("文档内容为空或无法切分出有效块");
            }
            // 远程嵌入放在事务外：失败时整篇置 FAILED，不落半截向量
            List<float[]> vectors = embeddingClient.embed(pieces);

            List<KbChunk> rows = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setDocumentTitle(doc.getTitle());
                chunk.setChunkIndex(i);
                chunk.setContent(pieces.get(i));
                applyEmbedding(chunk, vectors.get(i));
                rows.add(chunk);
            }
            List<KbChunk> saved = chunkRepository.saveAll(rows);
            for (KbChunk chunk : saved) {
                bm25Index.addChunk(chunk.getId(), chunk.getContent());
            }

            doc.setStatus(KbDocument.STATUS_READY);
            doc.setChunkCount(saved.size());
            doc.setErrorMessage(null);
            doc = documentRepository.save(doc);
            log.info("文档入库完成 id={} title={} chunks={} 耗时={}ms",
                    doc.getId(), title, saved.size(), System.currentTimeMillis() - start);
            return doc;
        } catch (RuntimeException e) {
            doc.setStatus(KbDocument.STATUS_FAILED);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            doc.setErrorMessage(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            documentRepository.save(doc);
            throw e;
        }
    }

    private void applyEmbedding(KbChunk chunk, float[] vector) {
        try {
            chunk.setEmbeddingJson(mapper.writeValueAsString(vector));
        } catch (Exception e) {
            throw new KbServiceException("向量序列化失败", e);
        }
        chunk.setEmbeddingDim(vector.length);
        chunk.setEmbeddingNorm(MysqlVectorStore.norm(vector));
    }

    public void deleteDocument(Long documentId) {
        List<Long> chunkIds = chunkRepository.findIdsByDocumentId(documentId);
        chunkIds.forEach(bm25Index::removeChunk);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("文档删除 id={} chunks={}", documentId, chunkIds.size());
    }

    /** 从 MySQL 全量重建 BM25 索引（多实例刷新 / 索引损坏修复） */
    public int rebuildIndex() {
        bm25Index.clear();
        for (KbChunkRepository.ChunkTextRow row : chunkRepository.findAllTextRows()) {
            bm25Index.addChunk(row.getId(), row.getContent());
        }
        log.info("BM25 索引重建完成，chunk 数={}", bm25Index.size());
        return bm25Index.size();
    }

    public int getEmbeddingDimension() {
        return props.getEmbedding().getDimension();
    }
}
