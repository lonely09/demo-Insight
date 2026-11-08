package com.insightserver.knowledgebase.embedding;

import com.insightserver.knowledgebase.config.KbProperties;
import com.insightserver.knowledgebase.support.KbServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议实现：POST {base-url}/embeddings  {model, input:[...]}
 * 适配 OpenAI / 豆包 / 通义 / One-API / Ollama 等各种网关，base-url 与 key 走配置。
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final KbProperties props;
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleEmbeddingClient(KbProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(props.getEmbedding().getTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        KbProperties.Model cfg = props.getEmbedding();
        for (int from = 0; from < texts.size(); from += Math.max(1, cfg.getBatchSize())) {
            int to = Math.min(from + Math.max(1, cfg.getBatchSize()), texts.size());
            result.addAll(embedBatch(cfg, texts.subList(from, to)));
        }
        return result;
    }

    private List<float[]> embedBatch(KbProperties.Model cfg, List<String> batch) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        // 支持自定义向量维度（OpenAI 兼容模式的 fields 名称为 dimensions）
        if (cfg.getDimension() > 0) {
            body.put("dimensions", cfg.getDimension());
        }
        ArrayNode input = body.putArray("input");
        batch.forEach(input::add);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                headers.setBearerAuth(cfg.getApiKey());
            }
            String resp = restTemplate.postForObject(
                    trimSlash(cfg.getBaseUrl()) + "/embeddings",
                    new org.springframework.http.HttpEntity<>(mapper.writeValueAsString(body), headers),
                    String.class);
            JsonNode data = mapper.readTree(resp).path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new KbServiceException("embedding 响应格式异常: " + abbreviate(resp));
            }
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (JsonNode item : data) {
                JsonNode vec = item.path("embedding");
                float[] v = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    v[i] = (float) vec.get(i).asDouble();
                }
                if (cfg.getDimension() > 0 && v.length != cfg.getDimension()) {
                    throw new KbServiceException("向量维度不匹配：模型返回 " + v.length
                            + "，配置 kb.embedding.dimension=" + cfg.getDimension()
                            + "，请核对模型与配置");
                }
                vectors.add(v);
            }
            return vectors;
        } catch (KbServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 embedding 服务失败: {}", e.toString());
            throw new KbServiceException("调用 embedding 服务失败: " + e.getMessage(), e);
        }
    }

    static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
