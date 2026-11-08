package com.insightserver.bi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI 兼容的 Chat Completions 客户端，仅用于 BI 查询规划。
 * 与知识库的 Chat 客户端分开配置，避免把问答模型/参数耦合到统计口径上。
 */
@Component
public class BiLlmClient {

    private final BiLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public BiLlmClient(BiLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(5000, Math.max(1000, properties.getTimeoutMs())));
        factory.setReadTimeout(Math.max(1000, properties.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 发送 system + user 两条消息，返回 choices[0].message.content */
    public String chat(String systemPrompt, String userPrompt) {
        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        if (baseUrl.isBlank()) {
            throw new BiLlmException("LLM base-url 未配置");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        if (properties.isJsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "/chat/completions",
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);
            String content = extractContent(response.getBody());
            if (content == null || content.isBlank()) {
                throw new BiLlmException("LLM 返回为空", response.getBody());
            }
            return content;
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            throw new BiLlmException("LLM HTTP " + e.getStatusCode().value() + ": " + shorten(responseBody),
                    responseBody);
        } catch (BiLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new BiLlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private String extractContent(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new BiLlmException("LLM 响应缺少 choices", body);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            return content.isTextual() ? content.asText() : content.toString();
        } catch (BiLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new BiLlmException("LLM 响应不是合法 JSON: " + e.getMessage(), body);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
