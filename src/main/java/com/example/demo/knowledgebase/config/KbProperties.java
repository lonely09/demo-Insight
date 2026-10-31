package com.example.demo.knowledgebase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 知识库全部可调参数，前缀 kb.*，见 application.yml。
 * 生产环境建议把 kb.* 配置放进 Nacos 的 ai-knowledge-base.yaml，支持热更新。
 */
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    private Chunk chunk = new Chunk();
    private Model embedding = new Model();
    private Model chat = new Model();
    private Retrieval retrieval = new Retrieval();

    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }
    public Model getEmbedding() { return embedding; }
    public void setEmbedding(Model embedding) { this.embedding = embedding; }
    public Model getChat() { return chat; }
    public void setChat(Model chat) { this.chat = chat; }
    public Retrieval getRetrieval() { return retrieval; }
    public void setRetrieval(Retrieval retrieval) { this.retrieval = retrieval; }

    /** OpenAI 兼容的模型服务配置（/v1/embeddings、/v1/chat/completions） */
    public static class Model {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "";
        /**
         * 向量维度：>0 时作为请求参数 dimensions 下发，并对返回维度做强校验；
         * 0 表示不指定也不校验（跟随模型默认，如 qwen3.7-text-embedding-flash 默认 1024）。
         */
        private int dimension = 1024;
        /** 仅 embedding 使用：单批文本数 */
        private int batchSize = 16;
        /** 仅 embedding 使用：请求超时毫秒 */
        private int timeoutMs = 30000;
        /** 仅 chat 使用 */
        private double temperature = 0.2;
        /** 仅 chat 使用 */
        private int maxTokens = 2000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Chunk {
        private int maxChars = 500;
        private int overlapChars = 80;

        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getOverlapChars() { return overlapChars; }
        public void setOverlapChars(int overlapChars) { this.overlapChars = overlapChars; }
    }

    public static class Retrieval {
        private int bm25TopK = 10;
        private int vectorTopK = 10;
        private int finalTopK = 5;
        /** 向量路余弦相似度下限：低于该值的召回结果不参与融合（过滤"强行 top-k"的无关内容）；0 = 不启用 */
        private double minVectorSimilarity = 0.3;
        /** RRF 平滑常数 */
        private int rrfK = 60;
        /** BM25 词频饱和度参数 */
        private double bm25K1 = 1.2;
        /** BM25 文档长度归一化参数 */
        private double bm25B = 0.75;
        /** 双路召回权重：[bm25, vector]，RRF 融合时的加权系数 */
        private List<Double> weights = new ArrayList<>();

        public int getBm25TopK() { return bm25TopK; }
        public void setBm25TopK(int bm25TopK) { this.bm25TopK = bm25TopK; }
        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
        public double getMinVectorSimilarity() { return minVectorSimilarity; }
        public void setMinVectorSimilarity(double minVectorSimilarity) { this.minVectorSimilarity = minVectorSimilarity; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public double getBm25K1() { return bm25K1; }
        public void setBm25K1(double bm25K1) { this.bm25K1 = bm25K1; }
        public double getBm25B() { return bm25B; }
        public void setBm25B(double bm25B) { this.bm25B = bm25B; }
        public List<Double> getWeights() { return weights; }
        public void setWeights(List<Double> weights) { this.weights = weights; }
    }
}
