package com.example.demo.knowledgebase.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void docPresentInBothRoutesWins() {
        List<Long> bm25 = List.of(1L, 2L, 3L);
        List<Long> vector = List.of(2L, 4L, 1L);
        Map<Long, Double> fused = HybridRetrievalService.rrfFuse(bm25, vector, 60, 1.0, 1.0);

        // chunk 1 与 2 双路命中，应排在单路命中的 3/4 之前
        List<Long> order = List.copyOf(fused.keySet());
        assertTrue(order.indexOf(1L) < order.indexOf(3L));
        assertTrue(order.indexOf(2L) < order.indexOf(4L));

        // 精确校验公式（rank 从 1 起）：
        // chunk1 = bm25 rank1 (1/61) + vector rank3 (1/63)
        // chunk2 = bm25 rank2 (1/62) + vector rank1 (1/61)
        assertEquals(1.0 / 61 + 1.0 / 63, fused.get(1L), 1e-9);
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(2L), 1e-9);
        // chunk3 仅 bm25 rank3 命中
        assertEquals(1.0 / 63, fused.get(3L), 1e-9);
    }

    @Test
    void weightsBiasTowardRoute() {
        List<Long> bm25 = List.of(1L);
        List<Long> vector = List.of(2L);
        Map<Long, Double> fused = HybridRetrievalService.rrfFuse(bm25, vector, 60, 0.0, 1.0);
        // BM25 权重置 0：chunk1 不应有得分
        assertEquals(0.0, fused.getOrDefault(1L, 0.0), 1e-9);
        assertEquals(1.0 / 61, fused.get(2L), 1e-9);
    }

    @Test
    void singleRouteStillWorks() {
        Map<Long, Double> fused = HybridRetrievalService.rrfFuse(List.of(), List.of(7L, 8L), 60, 1.0, 1.0);
        assertEquals(2, fused.size());
        assertEquals(7L, fused.keySet().iterator().next());
    }
}
