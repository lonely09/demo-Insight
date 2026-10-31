package com.example.demo.knowledgebase.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorMathTest {

    @Test
    void cosineOfIdenticalVectorIsOne() {
        float[] v = {0.3f, -0.4f, 0.5f, 1.2f};
        double n = MysqlVectorStore.norm(v);
        assertEquals(1.0, MysqlVectorStore.cosine(v, v, n, n), 1e-6);
    }

    @Test
    void cosineOfOrthogonalVectorIsZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0, MysqlVectorStore.cosine(a, b, 1, 1), 1e-9);
    }

    @Test
    void normMatchesExpected() {
        assertEquals(5.0, MysqlVectorStore.norm(new float[]{3f, 4f}), 1e-9);
        assertEquals(0.0, MysqlVectorStore.cosine(new float[]{1f}, new float[]{1f}, 0, 1), 1e-9,
                "零范数应安全返回 0");
    }

    @Test
    void similarityOrderingMatchesIntuition() {
        float[] q = {1f, 1f, 0f};
        float[] close = {1f, 1f, 0.1f};
        float[] far = {-1f, 1f, 0f};
        double s1 = MysqlVectorStore.cosine(q, close, MysqlVectorStore.norm(q), MysqlVectorStore.norm(close));
        double s2 = MysqlVectorStore.cosine(q, far, MysqlVectorStore.norm(q), MysqlVectorStore.norm(far));
        assertTrue(s1 > s2);
    }
}
