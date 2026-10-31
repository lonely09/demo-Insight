package com.example.demo.knowledgebase.chat;

import com.example.demo.knowledgebase.retrieval.HybridRetrievalService;
import com.example.demo.knowledgebase.retrieval.RetrievedItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RAG 问答：混合召回 → 上下文拼接 → LLM 生成（带引用编号） */
@Service
public class RagChatService {

    private static final String SYSTEM_PROMPT =
            "你是企业知识库助手。请严格依据【参考资料】回答用户问题："
                    + "1) 回答开头用一句话给出结论；2) 引用资料时在句末标注编号，如 [1][2]；"
                    + "3) 参考资料无法支撑答案时，明确说“知识库中未收录相关内容”，不要编造；"
                    + "4) 用简体中文回答，简洁准确。";

    private final HybridRetrievalService retrievalService;
    private final OpenAiCompatibleChatClient chatClient;

    public RagChatService(HybridRetrievalService retrievalService, OpenAiCompatibleChatClient chatClient) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
    }

    public ChatAnswer chat(String question, Integer topK) {
        List<RetrievedItem> context = retrievalService.retrieve(question, topK);
        if (context.isEmpty()) {
            return new ChatAnswer("知识库中未检索到与该问题相关的内容，无法回答。", List.of());
        }
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < context.size(); i++) {
            RetrievedItem item = context.get(i);
            sb.append('[').append(i + 1).append("] 《")
              .append(item.documentTitle() == null ? "未知文档" : item.documentTitle())
              .append("》\n").append(item.content()).append("\n\n");
        }
        sb.append("【用户问题】\n").append(question);
        String answer = chatClient.chat(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", sb.toString())));
        return new ChatAnswer(answer, new ArrayList<>(context));
    }

    public record ChatAnswer(String answer, List<RetrievedItem> references) {
    }
}
