package com.insightserver.knowledgebase.retrieval.bm25;

import com.insightserver.knowledgebase.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    private Bm25Index index;

    @BeforeEach
    void setUp() {
        index = new Bm25Index(new JiebaTokenizer(), new KbProperties());
    }

    @Test
    void ranksDocumentContainingRareTermFirst() {
        index.addChunk(1L, "如何使用 MySQL 作为向量数据库存储 embedding");
        index.addChunk(2L, "今天的天气非常晴朗适合出门散步");
        index.addChunk(3L, "BM25 是一种经典的稀疏检索算法，常用于知识库关键词召回");
        index.addChunk(4L, "知识库检索系统介绍");

        List<Bm25Index.Scored> hits = index.search("BM25 知识库召回算法", 4);
        assertTrue(hits.size() >= 2, "至少应有两个文档命中");
        assertEquals(3L, hits.get(0).chunkId(), "命中查询词最多的文档应排第一");
        assertTrue(hits.get(0).score() > hits.get(hits.size() - 1).score(), "得分应降序");
    }

    @Test
    void removalExcludesChunk() {
        index.addChunk(1L, "涨姿势：nacos 注册中心配置");
        index.addChunk(2L, "nacos 配置中心最佳实践");
        assertEquals(2, index.size());
        assertTrue(index.search("nacos 配置", 5).size() >= 1);

        index.removeChunk(2L);
        assertEquals(1, index.size());
        List<Bm25Index.Scored> hits = index.search("nacos 配置", 5);
        assertTrue(hits.stream().allMatch(h -> h.chunkId() != 2L), "已删除的块不应再命中");
    }

    @Test
    void reAddIsIdempotentOnLength() {
        index.addChunk(1L, "同一内容重复添加");
        int len1 = docLen(1L);
        index.addChunk(1L, "同一内容重复添加");
        assertEquals(len1, docLen(1L), "重复 addChunk 不应累积长度");
        assertEquals(1, index.size());
    }

    private int docLen(Long id) {
        // 通过 stats() 间接验证：单文档时 totalLength 即文档长度
        return index.stats().get("totalLength");
    }

    @Test
    void emptyIndexOrBlankQueryReturnsEmpty() {
        assertTrue(index.search("anything", 5).isEmpty());
        index.addChunk(1L, "内容");
        assertTrue(index.search("   ", 5).isEmpty());
    }
}
