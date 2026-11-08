package com.insightserver.bi.agent;

/** LLM 规划失败；rawOutput 保留模型原始返回，便于审计排查 */
public class BiLlmException extends RuntimeException {

    private final String rawOutput;

    public BiLlmException(String message) {
        this(message, (String) null);
    }

    public BiLlmException(String message, String rawOutput) {
        super(message);
        this.rawOutput = rawOutput;
    }

    public BiLlmException(String message, Throwable cause) {
        super(message, cause);
        this.rawOutput = null;
    }

    public String getRawOutput() {
        return rawOutput;
    }
}
