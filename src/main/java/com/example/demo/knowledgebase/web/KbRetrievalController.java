package com.example.demo.knowledgebase.web;

import com.example.demo.knowledgebase.chat.RagChatService;
import com.example.demo.knowledgebase.chat.StreamingRagService;
import com.example.demo.knowledgebase.retrieval.HybridRetrievalService;
import com.example.demo.knowledgebase.retrieval.RetrievedItem;
import com.example.demo.knowledgebase.web.dto.ChatRequest;
import com.example.demo.knowledgebase.web.dto.SearchRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索与 RAG 问答 API */
@RestController
@RequestMapping("/api/kb")
public class KbRetrievalController {

    private final HybridRetrievalService retrievalService;
    private final RagChatService chatService;
    private final StreamingRagService streamingRagService;

    public KbRetrievalController(HybridRetrievalService retrievalService, 
                                 RagChatService chatService, 
                                 StreamingRagService streamingRagService) {
        this.retrievalService = retrievalService;
        this.chatService = chatService;
        this.streamingRagService = streamingRagService;
    }

    /** 混合召回：BM25 + 向量，RRF 融合，返回命中的知识片段 */
    @PostMapping("/search")
    public Map<String, Object> search(@Valid @RequestBody SearchRequest request) {
        List<RetrievedItem> items = retrievalService.retrieve(request.query(), request.topK());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query", request.query());
        resp.put("count", items.size());
        resp.put("items", items);
        return resp;
    }

    /** RAG 问答：检索 + LLM 生成，带引用 */
    @PostMapping("/chat")
    public RagChatService.ChatAnswer chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request.question(), request.topK());
    }

    /**
     * RAG 问答流式版：通过 Server-Sent Events 逐 Token 推送给前端
     * GET /api/kb/chat/sse?question=xxx&topK=5
     */
    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam String question,
            @RequestParam(defaultValue = "5") int topK) throws Exception {
        return streamingRagService.chatStream(question, topK);
    }
}
