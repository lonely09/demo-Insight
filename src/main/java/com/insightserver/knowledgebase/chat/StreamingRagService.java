package com.insightserver.knowledgebase.chat;

import com.insightserver.knowledgebase.config.KbProperties;
import com.insightserver.knowledgebase.retrieval.HybridRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流式 RAG 问答：检索参考资料 -> 调用 LLM /chat/completions?stream=true -> 逐 Token 推送到 SseEmitter -> 前端读取
 *
 * SSE 协议（与前端 useStreamingChat 严格对齐）：
 *  统一使用【无名事件】，data 为单个 JSON 对象，必含 type 字段：
 *    data: {"type":"start","message":"..."}
 *    data: {"type":"references","items":{...}}
 *    data: {"type":"chunk","data":"..."}
 *    data: {"type":"done"}
 *    data: {"type":"error","message":"..."}
 */
@Service
public class StreamingRagService {

    private static final Logger log = LoggerFactory.getLogger(StreamingRagService.class);

    private final KbProperties props;
    private final HybridRetrievalService retrievalService;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;

    public StreamingRagService(KbProperties props, HybridRetrievalService retrievalService, ObjectMapper mapper) {
        this.props = props;
        this.retrievalService = retrievalService;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(props.getChat().getTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 流式 RAG 入口。在后台线程发起 HTTP 流式请求，每个 Token 通过 SseEmitter 推送给前端。
     */
    public SseEmitter chatStream(String question, Integer topK) {
        SseEmitter emitter = new SseEmitter((long) props.getChat().getTimeoutMs() + 5000);
        String baseUrl = props.getChat().getBaseUrl().endsWith("/")
                ? props.getChat().getBaseUrl().substring(0, props.getChat().getBaseUrl().length() - 1)
                : props.getChat().getBaseUrl();
        String apiKey = props.getChat().getApiKey() != null && !props.getChat().getApiKey().isBlank()
                ? props.getChat().getApiKey()
                : "";

        Thread bgThread = new Thread(() -> {
            try {
                send(emitter, Map.of("type", "start", "message", "检索知识库中..."));

                var items = retrievalService.retrieve(question, topK);
                if (items.isEmpty()) {
                    send(emitter, Map.of("type", "chunk", "data", "[未找到相关文档]"));
                    send(emitter, Map.of("type", "done"));
                    emitter.complete();
                    return;
                }

                StringBuilder contextSb = new StringBuilder("【参考资料】\n");
                var references = new LinkedHashMap<String, String>();
                for (int i = 0; i < items.size(); i++) {
                    var item = items.get(i);
                    int idx = i + 1;
                    contextSb.append("[").append(idx).append("] 《")
                             .append(item.documentTitle() != null ? item.documentTitle() : "(无标题)")
                             .append("》\n").append(item.content()).append("\n\n");
                    references.put(String.valueOf(idx), "[" + idx + "] " + (item.documentTitle() != null ? item.documentTitle() : ""));
                }
                contextSb.append("【用户问题】\n").append(question);

                Map<String, Object> refPayload = new LinkedHashMap<>();
                refPayload.put("type", "references");
                refPayload.put("items", references);
                send(emitter, refPayload);

                String llmUrl = baseUrl + "/chat/completions";
                String jsonBody = buildRequestBody(question, contextSb.toString(), apiKey,
                        props.getChat().getModel(), props.getChat().getTemperature(),
                        props.getChat().getMaxTokens());

                log.info("Streaming chat for question ({} chars)", question.length());
                performStreamingPost(llmUrl, jsonBody, apiKey, emitter);

                send(emitter, Map.of("type", "done"));
                emitter.complete();

            } catch (Exception e) {
                log.error("Streaming chat error", e);
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("type", "error");
                    err.put("message", "生成失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
                    send(emitter, err);
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, "rag-stream-worker");
        bgThread.setDaemon(true);
        bgThread.start();

        emitter.onTimeout(() -> log.info("SSE stream timeout for: {}", question));
        emitter.onError(e -> log.warn("SSE stream error for {}: {}", question, e.getMessage()));
        emitter.onCompletion(() -> log.info("SSE stream completed for: {}", question));

        return emitter;
    }

    /** 统一发送 SSE 事件：payload(Map) 由 Jackson 序列化为单行 JSON，避免特殊字符/换行破坏行结构 */
    private void send(SseEmitter emitter, Map<String, Object> payload) throws IOException {
        emitter.send(SseEmitter.event().data(payload));
    }

    /** 拼接 LLM API 请求 JSON */
    private String buildRequestBody(String question, String context, String apiKey,
                                    String model, double temp, int maxTokens) {
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temp);
            body.put("max_tokens", maxTokens);
            body.put("stream", true);
            var msgs = body.putArray("messages");
            var sysMsg = msgs.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", SYSTEM_PROMPT);
            var usrMsg = msgs.addObject();
            usrMsg.put("role", "user");
            usrMsg.put("content", context);
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败", e);
        }
    }

    /** 用 HttpURLConnection 发起带 Accept: text/event-stream 的 POST，逐行解析 SSE chunk 写入 emitter */
    private void performStreamingPost(String urlStr, String bodyJson, String apiKey, SseEmitter emitter) throws IOException {
        final java.util.concurrent.atomic.AtomicBoolean clientActive = new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(() -> clientActive.set(false));

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout((int) props.getChat().getTimeoutMs());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                InputStream errStream = conn.getErrorStream();
                String errBody = errStream != null ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8) : "";
                throw new IOException("LLM API returned HTTP " + responseCode + " " + errBody.trim());
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!clientActive.get()) break;
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) continue;
                    if (data.isEmpty()) continue;

                    JsonNode root;
                    try {
                        root = mapper.readTree(data);
                    } catch (Exception parseErr) {
                        continue;
                    }
                    var choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        var delta = choices.get(0).path("delta");
                        if (delta.hasNonNull("content")) {
                            String content = delta.get("content").asText("");
                            if (!content.isEmpty()) {
                                try {
                                    send(emitter, Map.of("type", "chunk", "data", content));
                                } catch (Exception sendErr) {
                                    clientActive.set(false);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static final String SYSTEM_PROMPT =
            "你是企业知识库助手。请严格依据以下【参考资料】回答用户问题：\n"
                    + "1) 回答开头用一句话给出结论；\n"
                    + "2) 引用资料时在句末标注编号，如 [1][2]；\n"
                    + "3) 如果参考资料无法支撑答案，明确说「知识库中未收录相关内容」，不要编造。\n"
                    + "4) 使用简体中文回答，保持简洁准确。";
}
