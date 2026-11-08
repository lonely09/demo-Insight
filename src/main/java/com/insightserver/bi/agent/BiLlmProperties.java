package com.insightserver.bi.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话式统计的 LLM 规划配置。
 * LLM 只负责把自然语言规划成 BiQuerySpec，不直接算数、不直接写 SQL。
 */
@Component
@ConfigurationProperties(prefix = "bi.agent.llm")
public class BiLlmProperties {

    /** 是否启用 LLM 规划；关闭时完全走规则解析 */
    private boolean enabled = true;

    /** OpenAI 兼容地址，例如 https://api.siliconflow.cn/v1 */
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /** API Key；为空时自动跳过 LLM，直接走规则解析 */
    private String apiKey = "";

    /** 用于规划 JSON 的模型 */
    private String model = "Qwen/Qwen2.5-7B-Instruct";

    private double temperature = 0.0;
    private int maxTokens = 800;
    private int timeoutMs = 15000;

    /** LLM 失败时是否回退规则解析 */
    private boolean fallbackToRule = true;

    /** 是否让 OpenAI 兼容服务强制 JSON 输出（部分网关不支持，默认关闭） */
    private boolean jsonMode = false;

    /** LLM 失败后的熔断时间，避免每次提问都等超时 */
    private int failureCooldownSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public boolean isFallbackToRule() { return fallbackToRule; }
    public void setFallbackToRule(boolean fallbackToRule) { this.fallbackToRule = fallbackToRule; }
    public boolean isJsonMode() { return jsonMode; }
    public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
    public int getFailureCooldownSeconds() { return failureCooldownSeconds; }
    public void setFailureCooldownSeconds(int failureCooldownSeconds) {
        this.failureCooldownSeconds = failureCooldownSeconds;
    }
}
