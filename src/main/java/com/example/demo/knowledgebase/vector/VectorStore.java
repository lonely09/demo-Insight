package com.example.demo.knowledgebase.vector;

import java.util.List;

/**
 * 向量库抽象。当前实现基于 MySQL（kb_chunk.embedding_json），
 * 数据量上来后可平滑替换为 Milvus / PgVector / OpenSearch 等实现。
 */
public interface VectorStore {

    /**
     * 余弦相似度召回。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     */
    List<VectorHit> recall(float[] queryVector, int topK);

    /** 单条召回命中 */
    record VectorHit(Long chunkId, Long documentId, String documentTitle, String content, double score) {
    }
}
