package com.example.demo.knowledgebase.retrieval.bm25;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * jieba 中文分词 + 停用词过滤。索引侧用 INDEX 模式（细粒度），
 * 查询侧用 SEARCH 模式（歧义消解），Lucene SmartChinese 同款思路。
 */
@Component
public class JiebaTokenizer {

    /** 精简中英停用词表，业务可在集成层扩展 */
    private static final Set<String> STOP_WORDS = new HashSet<>(List.of(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也",
            "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "他",
            "她", "它", "们", "什么", "怎么", "如何", "为什么", "可以", "或者", "以及", "但是", "因为",
            "所以", "如果", "进行", "通过", "对于", "关于", "与", "及", "等", "之", "其", "并", "被",
            "the", "a", "an", "and", "or", "is", "are", "was", "were", "be", "been", "to", "of", "in",
            "on", "at", "for", "with", "by", "from", "as", "that", "this", "it", "its", "we", "you",
            "they", "he", "she", "do", "does", "did", "not", "but", "if", "can", "will", "how", "what"));

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /** forQuery=true 用 SEARCH 模式，否则 INDEX 模式 */
    public List<String> tokenize(String text, boolean forQuery) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        for (SegToken token : segmenter.process(text, forQuery
                ? JiebaSegmenter.SegMode.SEARCH : JiebaSegmenter.SegMode.INDEX)) {
            String term = normalize(token.word);
            if (term != null) {
                terms.add(term);
            }
        }
        return terms;
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String term = raw.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty() || term.length() > 40 || STOP_WORDS.contains(term)) {
            return null;
        }
        // 过滤纯标点/符号
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < term.length(); i++) {
            if (Character.isLetterOrDigit(term.charAt(i))) {
                hasLetterOrDigit = true;
                break;
            }
        }
        return hasLetterOrDigit ? term : null;
    }
}
