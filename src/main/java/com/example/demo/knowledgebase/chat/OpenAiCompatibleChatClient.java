package com.example.demo.knowledgebase.chat;

import com.example.demo.knowledgebase.config.KbProperties;
import com.example.demo.knowledgebase.support.KbServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容 Chat Completions 客户端（/chat/completions，非流式） */
@Component
public class OpenAiCompatibleChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private final KbProperties props;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleChatClient(KbProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(props.getChat().getTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    public String chat(List<Map<String, String>> messages) {
        KbProperties.Model cfg = props.getChat();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        ArrayNode arr = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode msg = arr.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                headers.setBearerAuth(cfg.getApiKey());
            }
            String base = cfg.getBaseUrl().endsWith("/")
                    ? cfg.getBaseUrl().substring(0, cfg.getBaseUrl().length() - 1) : cfg.getBaseUrl();
            String resp = restTemplate.postForObject(base + "/chat/completions",
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            JsonNode node = mapper.readTree(resp);
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || !content.isTextual()) {
                throw new KbServiceException("chat 响应格式异常: " + resp);
            }
            return content.asText();
        } catch (KbServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 chat 服务失败: {}", e.toString());
            throw new KbServiceException("调用 chat 服务失败: " + e.getMessage(), e);
        }
    }
}
