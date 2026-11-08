package com.insightserver.knowledgebase.retrieval;

/** 混合召回命中的一个知识片段 */
public record RetrievedItem(
        Long chunkId,
        Long documentId,
        String documentTitle,
        String content,
        double score,
        /** 命中来源：BM25 / VECTOR / BM25+VECTOR */
        String matchedBy) {

    public RetrievedItem withScore(double newScore) {
        return new RetrievedItem(chunkId, documentId, documentTitle, content, newScore, matchedBy);
    }
}
