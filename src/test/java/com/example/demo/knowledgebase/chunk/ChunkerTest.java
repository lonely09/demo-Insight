package com.example.demo.knowledgebase.chunk;

import com.example.demo.knowledgebase.config.KbProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    private Chunker chunker(int maxChars, int overlap) {
        KbProperties props = new KbProperties();
        props.getChunk().setMaxChars(maxChars);
        props.getChunk().setOverlapChars(overlap);
        return new Chunker(props);
    }

    @Test
    void splitsByMaxSizeAndKeepsOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("个句子，用于测试切块逻辑。");
        }
        String text = sb.toString();
        List<String> chunks = chunker(100, 20).split(text);
        assertTrue(chunks.size() > 1, "长文本应切成多块");
        for (String c : chunks) {
            assertTrue(c.length() <= 100 + 40, "块大小应受 maxChars 约束（允许句子级溢出）: " + c.length());
            assertTrue(!c.isBlank());
        }
        // 相邻块存在重叠：后一块开头应能在前一块尾部找到
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String head = chunks.get(i).substring(0, Math.min(20, chunks.get(i).length()));
            assertTrue(prev.contains(head), "相邻块应有重叠内容, prev尾部=" + tail(prev, 40) + ", next头部=" + head);
        }
    }

    @Test
    void shortTextIsOneChunk() {
        List<String> chunks = chunker(500, 80).split("知识库只有一个短段落。");
        assertEquals(1, chunks.size());
    }

    @Test
    void blankInputProducesNoChunks() {
        assertTrue(chunker(500, 80).split("   \n\n  ").isEmpty());
        assertTrue(chunker(500, 80).split(null).isEmpty());
    }

    @Test
    void hardCutsOverlongSentence() {
        String longSentence = "无标点超长文本".repeat(100); // 600 字无分隔
        List<String> chunks = chunker(100, 10).split(longSentence);
        assertTrue(chunks.size() >= 6, "无标点长句应被硬切成多块，实际 " + chunks.size());
        // 硬切滑窗 + 重叠拼接：允许块长 = maxChars + overlap + 1
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 100 + 10 + 1),
                "硬切后每块 ≤ maxChars+overlap");
    }

    private String tail(String s, int n) {
        return s.substring(Math.max(0, s.length() - n));
    }
}
