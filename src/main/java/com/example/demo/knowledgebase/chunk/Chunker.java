package com.example.demo.knowledgebase.chunk;

import com.example.demo.knowledgebase.config.KbProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本切块器：先按空行拆段落，再按句子边界（。！？；!?; 换行）拆句，
 * 贪心装填到 maxChars 封块，相邻块之间保留 overlapChars 的重叠，
 * 单句超长时按 maxChars 硬切。纯函数、无外部依赖，可单测。
 */
@Component
public class Chunker {

    private static final Pattern PARAGRAPH = Pattern.compile("\\R\\s*\\R");
    /** 句子边界：在终止标点后切开，标点保留在前句尾部 */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？；!?;])|\\R");

    private final KbProperties props;

    public Chunker(KbProperties props) {
        this.props = props;
    }

    public List<String> split(String text) {
        int maxChars = props.getChunk().getMaxChars();
        int overlapChars = props.getChunk().getOverlapChars();
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        // 段 → 句
        List<String> sentences = new ArrayList<>();
        for (String para : PARAGRAPH.split(text.strip())) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= maxChars) {
                sentences.add(p);
                continue;
            }
            for (String s : SENTENCE.split(p)) {
                String sent = s.strip();
                if (sent.isEmpty()) {
                    continue;
                }
                if (sent.length() <= maxChars) {
                    sentences.add(sent);
                } else {
                    // 超长单句：滑窗硬切
                    for (int i = 0; i < sent.length(); i += Math.max(1, maxChars - overlapChars)) {
                        int end = Math.min(i + maxChars, sent.length());
                        sentences.add(sent.substring(i, end));
                        if (end == sent.length()) {
                            break;
                        }
                    }
                }
            }
        }
        // 贪心装填
        StringBuilder buf = new StringBuilder();
        for (String sent : sentences) {
            if (buf.length() > 0 && buf.length() + sent.length() + 1 > maxChars) {
                String chunk = buf.toString().strip();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                buf.setLength(0);
                if (overlapChars > 0 && chunk.length() > 0) {
                    int from = Math.max(0, chunk.length() - overlapChars);
                    buf.append(chunk, from, chunk.length());
                }
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(sent);
        }
        if (buf.length() > 0 && !buf.toString().strip().isEmpty()) {
            chunks.add(buf.toString().strip());
        }
        return chunks;
    }
}
