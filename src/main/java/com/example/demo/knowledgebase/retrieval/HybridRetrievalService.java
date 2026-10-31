package com.example.demo.knowledgebase.retrieval;

import com.example.demo.knowledgebase.config.KbProperties;
import com.example.demo.knowledgebase.embedding.EmbeddingClient;
import com.example.demo.knowledgebase.repository.KbChunkRepository;
import com.example.demo.knowledgebase.retrieval.bm25.Bm25Index;
import com.example.demo.knowledgebase.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * BM25 + 向量 双路召回，RRF（加权 Reciprocal Rank Fusion）融合：
 *   score(d) = Σ_route  weight_route · 1 / (rrfK + rank_route(d))
 * 只用名次不用原始分，天然规避 BM25 分与余弦分量纲不一致的问题。
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final Bm25Index bm25Index;
    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final KbChunkRepository chunkRepository;
    private final KbProperties props;

    /** 查询向量缓存（LRU ≤ 500）：同一问题文本命中同一向量，消除 provider 端浮点抖动对召回排序的影响 */
    private final Map<String, float[]> queryVectorCache = Collections.synchronizedMap(
            new LinkedHashMap<String, float[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > 500;
                }
            });

    public HybridRetrievalService(Bm25Index bm25Index, VectorStore vectorStore,
                                  EmbeddingClient embeddingClient,
                                  KbChunkRepository chunkRepository, KbProperties props) {
        this.bm25Index = bm25Index;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.props = props;
    }

    public List<RetrievedItem> retrieve(String query, Integer topKOverride) {
        KbProperties.Retrieval cfg = props.getRetrieval();
        int finalTopK = topKOverride != null && topKOverride > 0 ? topKOverride : cfg.getFinalTopK();

        // 第一路：BM25 关键词召回
        List<Long> bm25Ids = new ArrayList<>();
        bm25Index.search(query, cfg.getBm25TopK()).forEach(s -> bm25Ids.add(s.chunkId()));

        // 第二路：向量语义召回（带相似度下限过滤，砍掉小知识库下"强行 top-k"的无关结果）
        List<VectorStore.VectorHit> vectorHits;
        try {
            float[] queryVector = cachedEmbedOne(query);
            List<VectorStore.VectorHit> recalled = vectorStore.recall(queryVector, cfg.getVectorTopK());
            log.info("向量召回 top3 相似度: [{}]", recalled.stream().limit(3)
                    .map(h -> String.format("%.4f", h.score()))
                    .collect(Collectors.joining(", ")));
            double minSim = cfg.getMinVectorSimilarity();
            vectorHits = new ArrayList<>();
            for (VectorStore.VectorHit hit : recalled) {
                if (minSim <= 0 || hit.score() >= minSim) {
                    vectorHits.add(hit);
                }
            }
            if (!recalled.isEmpty() && vectorHits.isEmpty()) {
                log.info("向量路结果全部低于阈值 (min-vector-similarity={})，降级为 BM25 单路", minSim);
            }
        } catch (RuntimeException e) {
            // 向量路失败不阻塞：降级为纯 BM25
            log.warn("向量召回失败，降级为 BM25 单路: {}", e.getMessage());
            vectorHits = List.of();
        }
        List<Long> vectorIds = new ArrayList<>();
        Map<Long, VectorStore.VectorHit> vectorById = new LinkedHashMap<>();
        for (VectorStore.VectorHit hit : vectorHits) {
            vectorIds.add(hit.chunkId());
            vectorById.put(hit.chunkId(), hit);
        }

        // RRF 融合
        double bm25Weight = 1.0;
        double vectorWeight = 1.0;
        List<Double> weights = cfg.getWeights();
        if (weights != null && weights.size() == 2) {
            bm25Weight = weights.get(0);
            vectorWeight = weights.get(1);
        }
        Map<Long, Double> fused = rrfFuse(bm25Ids, vectorIds, cfg.getRrfK(), bm25Weight, vectorWeight);

        // 组装结果：内容优先取向量路已加载的投影，缺失的回查 MySQL
        Set<Long> needLookup = new LinkedHashSet<>(fused.keySet());
        needLookup.removeAll(vectorById.keySet());
        Map<Long, KbChunkRepository.ChunkTextRow> fallback = new TreeMap<>();
        List<Long> needLookupIds = new ArrayList<>(needLookup);
        if (!needLookupIds.isEmpty()) {
            chunkRepository.findAllById(needLookupIds).forEach(c ->
                    fallback.put(c.getId(), new KbChunkRepository.ChunkTextRow() {
                        @Override
                        public Long getId() {
                            return c.getId();
                        }

                        @Override
                        public String getContent() {
                            return c.getContent();
                        }
                    }));
        }

        List<RetrievedItem> items = new ArrayList<>(fused.size());
        Set<Long> bm25Set = new LinkedHashSet<>(bm25Ids);
        Set<Long> vecSet = new LinkedHashSet<>(vectorIds);
        for (Map.Entry<Long, Double> e : fused.entrySet()) {
            Long id = e.getKey();
            String title = null;
            String content = null;
            Long docId = null;
            VectorStore.VectorHit vh = vectorById.get(id);
            if (vh != null) {
                docId = vh.documentId();
                title = vh.documentTitle();
                content = vh.content();
            }
            if (content == null && fallback.get(id) != null) {
                content = fallback.get(id).getContent();
            }
            String matchedBy = bm25Set.contains(id) && vecSet.contains(id)
                    ? "BM25+VECTOR" : bm25Set.contains(id) ? "BM25" : "VECTOR";
            items.add(new RetrievedItem(id, docId, title, content, e.getValue(), matchedBy));
        }
        items.sort(Comparator.comparingDouble(RetrievedItem::score).reversed());
        return items.size() > finalTopK ? new ArrayList<>(items.subList(0, finalTopK)) : items;
    }

    /** 调 embedding 模型；同一问题文本复用进程缓存，缓解 provider 端数值抖动 → 召回排序漂移 */
    private float[] cachedEmbedOne(String query) {
        String key = query.trim();
        float[] cached = queryVectorCache.get(key);
        if (cached != null) {
            return cached;
        }
        float[] vector = embeddingClient.embedOne(query);
        queryVectorCache.put(key, vector);
        return vector;
    }

    /**
     * 加权 RRF：对每条召回链路按名次打分求和，返回按融合分降序的 chunkId -> score。
     * rank 从 1 开始，rrfK 为平滑常数（默认 60）。
     */
    public static Map<Long, Double> rrfFuse(List<Long> bm25Ranked, List<Long> vectorRanked,
                                           int rrfK, double bm25Weight, double vectorWeight) {
        Map<Long, Double> fused = new LinkedHashMap<>();
        accumulate(fused, bm25Ranked, rrfK, bm25Weight);
        accumulate(fused, vectorRanked, rrfK, vectorWeight);
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort(Map.Entry.<Long, Double>comparingByValue().reversed());
        Map<Long, Double> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static void accumulate(Map<Long, Double> fused, List<Long> ranked, int rrfK, double weight) {
        for (int i = 0; i < ranked.size(); i++) {
            double contribution = weight / (rrfK + i + 1.0);
            fused.merge(ranked.get(i), contribution, Double::sum);
        }
    }
}
