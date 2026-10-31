package com.example.demo.knowledgebase.vector;

import com.example.demo.knowledgebase.repository.KbChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * MySQL 向量库实现：向量以 JSON 存于 kb_chunk.embedding_json，
 * 召回时在应用层做全量余弦相似度计算（TopK 小根堆）。
 * 十万级分块以内性能足够；更大规模时建议换专用向量库实现（接口不变）。
 */
@Component
public class MysqlVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlVectorStore.class);

    private final KbChunkRepository chunkRepository;
    private final ObjectMapper mapper;

    public MysqlVectorStore(KbChunkRepository chunkRepository, ObjectMapper mapper) {
        this.chunkRepository = chunkRepository;
        this.mapper = mapper;
    }

    @Override
    public List<VectorHit> recall(float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        double queryNorm = norm(queryVector);
        if (queryNorm == 0) {
            return List.of();
        }
        List<KbChunkRepository.ChunkVectorRow> rows = chunkRepository.findAllVectorRows();
        PriorityQueue<VectorHit> heap = new PriorityQueue<>(topK, Comparator.comparingDouble(VectorHit::score));
        int skipped = 0;
        for (KbChunkRepository.ChunkVectorRow row : rows) {
            float[] vec = parse(row.getEmbeddingJson());
            if (vec == null || vec.length != queryVector.length) {
                skipped++;
                continue;
            }
            double score = cosine(queryVector, vec, queryNorm,
                    row.getEmbeddingNorm() != null ? row.getEmbeddingNorm() : norm(vec));
            VectorHit hit = new VectorHit(row.getId(), row.getDocumentId(),
                    row.getDocumentTitle(), row.getContent(), score);
            if (heap.size() < topK) {
                heap.offer(hit);
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(hit);
            }
        }
        if (skipped > 0) {
            log.warn("向量召回跳过 {} 条（缺失或维度不匹配），请检查 kb.embedding.dimension 配置", skipped);
        }
        List<VectorHit> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return result;
    }

    private float[] parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.warn("embedding JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    /** 余弦相似度；normA/normB 为各自 L2 范数（预存） */
    public static double cosine(float[] a, float[] b, double normA, double normB) {
        if (normA == 0 || normB == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (normA * normB);
    }
}
