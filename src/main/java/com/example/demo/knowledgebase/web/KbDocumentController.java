package com.example.demo.knowledgebase.web;

import com.example.demo.knowledgebase.domain.KbChunk;
import com.example.demo.knowledgebase.domain.KbDocument;
import com.example.demo.knowledgebase.repository.KbChunkRepository;
import com.example.demo.knowledgebase.repository.KbDocumentRepository;
import com.example.demo.knowledgebase.service.KbIngestService;
import com.example.demo.knowledgebase.web.dto.IngestRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识库文档管理 API */
@RestController
@RequestMapping("/api/kb/documents")
public class KbDocumentController {

    private final KbIngestService ingestService;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;

    public KbDocumentController(KbIngestService ingestService,
                                KbDocumentRepository documentRepository,
                                KbChunkRepository chunkRepository) {
        this.ingestService = ingestService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /** 入库一篇文档：切块 + Embedding + BM25 索引更新（同步执行，大文档可后续改异步） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        long start = System.currentTimeMillis();
        KbDocument doc = ingestService.ingest(request.title(), request.content(), request.source());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("documentId", doc.getId());
        resp.put("title", doc.getTitle());
        resp.put("status", doc.getStatus());
        resp.put("chunkCount", doc.getChunkCount());
        resp.put("tookMs", System.currentTimeMillis() - start);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Page<KbDocument> result = documentRepository.findAll(
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "id")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("pages", result.getTotalPages());
        resp.put("items", result.getContent());
        return resp;
    }

    @GetMapping("/{id}")
    public KbDocument get(@PathVariable Long id) {
        return documentRepository.findById(id).orElse(null);
    }

    /** 查看某文档切块（不回传向量，仅预览正文） */
    @GetMapping("/{id}/chunks")
    public List<Map<String, Object>> chunks(@PathVariable Long id) {
        return chunkRepository.findByDocumentIdOrderByChunkIndexAsc(id).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("chunkId", c.getId());
                    m.put("chunkIndex", c.getChunkIndex());
                    m.put("content", c.getContent());
                    m.put("hasEmbedding", c.getEmbeddingJson() != null);
                    m.put("embeddingDim", c.getEmbeddingDim());
                    return m;
                })
                .toList();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        ingestService.deleteDocument(id);
        return Map.of("deleted", id);
    }

    /** 从 MySQL 全量重建进程内 BM25 索引 */
    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuildIndex() {
        return Map.of("indexedChunks", ingestService.rebuildIndex());
    }
}
