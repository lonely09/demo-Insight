package com.example.demo.knowledgebase.embedding;

import java.util.List;

/** 文本向量化客户端抽象，便于替换供应商 */
public interface EmbeddingClient {

    /** 批量嵌入，返回顺序与入参一致 */
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }
}
