package com.insightserver.knowledgebase.repository;

import com.insightserver.knowledgebase.domain.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    void deleteByDocumentId(Long documentId);

    @Query("select c.id as id from KbChunk c where c.documentId = :documentId")
    List<Long> findIdsByDocumentId(Long documentId);

    /** 向量召回用投影：只取必要列，避免加载整个实体的开销 */
    @Query("select c.id as id, c.documentId as documentId, c.documentTitle as documentTitle, "
            + "c.content as content, c.embeddingJson as embeddingJson, c.embeddingNorm as embeddingNorm "
            + "from KbChunk c where c.embeddingJson is not null")
    List<ChunkVectorRow> findAllVectorRows();

    /** BM25 索引重建用投影：全量 chunk 文本 */
    @Query("select c.id as id, c.content as content from KbChunk c")
    List<ChunkTextRow> findAllTextRows();

    interface ChunkVectorRow {
        Long getId();
        Long getDocumentId();
        String getDocumentTitle();
        String getContent();
        String getEmbeddingJson();
        Double getEmbeddingNorm();
    }

    interface ChunkTextRow {
        Long getId();
        String getContent();
    }
}
