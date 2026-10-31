package com.example.demo.knowledgebase.retrieval.bm25;

import com.example.demo.knowledgebase.config.KbProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 进程内 BM25 倒排索引（Okapi BM25 标准公式）。
 * 数据源是 MySQL kb_chunk，启动时全量重建（Bm25IndexInitializer），
 * 文档入库/删除时增量维护；多实例部署时各实例各自持索引并跟随 DB 重建，
 * 更大规模可替换为 Elasticsearch / OpenSearch。
 */
@Component
public class Bm25Index {

    private final JiebaTokenizer tokenizer;
    private final KbProperties props;

    /** term -> (chunkId -> 词频) */
    private final Map<String, Map<Long, Integer>> postings = new HashMap<>();
    /** chunkId -> (term -> 词频)，用于 O(词数) 删除 */
    private final Map<Long, Map<String, Integer>> docTerms = new HashMap<>();
    /** chunkId -> 词数（文档长度） */
    private final Map<Long, Integer> docLength = new HashMap<>();
    private long totalLength = 0;

    public Bm25Index(JiebaTokenizer tokenizer, KbProperties props) {
        this.tokenizer = tokenizer;
        this.props = props;
    }

    public synchronized void addChunk(Long chunkId, String content) {
        removeChunk(chunkId); // 幂等：已存在则先移除
        List<String> terms = tokenizer.tokenize(content, false);
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) {
            tf.merge(term, 1, Integer::sum);
        }
        docTerms.put(chunkId, tf);
        docLength.put(chunkId, terms.size());
        totalLength += terms.size();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            postings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(chunkId, e.getValue());
        }
    }

    public synchronized void removeChunk(Long chunkId) {
        Map<String, Integer> tf = docTerms.remove(chunkId);
        if (tf == null) {
            return;
        }
        Integer len = docLength.remove(chunkId);
        totalLength -= len == null ? 0 : len;
        for (String term : tf.keySet()) {
            Map<Long, Integer> posting = postings.get(term);
            if (posting != null) {
                posting.remove(chunkId);
                if (posting.isEmpty()) {
                    postings.remove(term);
                }
            }
        }
    }

    public synchronized void clear() {
        postings.clear();
        docTerms.clear();
        docLength.clear();
        totalLength = 0;
    }

    public synchronized int size() {
        return docLength.size();
    }

    /**
     * BM25 检索。
     * score(q,d) = Σ_t idf(t) · tf·(k1+1) / (tf + k1·(1−b + b·|d|/avgdl))
     * idf(t) = ln(1 + (N − df + 0.5) / (df + 0.5))
     */
    public synchronized List<Scored> search(String query, int topK) {
        if (topK <= 0 || docLength.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = tokenizer.tokenize(query, true);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        int n = docLength.size();
        double avgdl = totalLength / (double) n;
        double k1 = props.getRetrieval().getBm25K1();
        double b = props.getRetrieval().getBm25B();

        Map<Long, Double> scores = new HashMap<>();
        Set<String> distinct = new HashSet<>(queryTerms);
        for (String term : distinct) {
            Map<Long, Integer> posting = postings.get(term);
            if (posting == null || posting.isEmpty()) {
                continue;
            }
            double idf = Math.log(1 + (n - posting.size() + 0.5) / (posting.size() + 0.5));
            for (Map.Entry<Long, Integer> e : posting.entrySet()) {
                double tf = e.getValue();
                double dl = docLength.getOrDefault(e.getKey(), 1);
                double denom = tf + k1 * (1 - b + b * dl / avgdl);
                scores.merge(e.getKey(), idf * tf * (k1 + 1) / denom, Double::sum);
            }
        }
        List<Scored> result = new ArrayList<>(scores.size());
        scores.forEach((id, score) -> result.add(new Scored(id, score)));
        result.sort(Comparator.comparingDouble(Scored::score).reversed());
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /** 命中：chunkId + BM25 得分 */
    public record Scored(Long chunkId, double score) {
    }

    synchronized Map<String, Integer> stats() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("chunks", docLength.size());
        s.put("terms", postings.size());
        s.put("totalLength", (int) Math.min(Integer.MAX_VALUE, totalLength));
        return s;
    }
}
