package com.insightserver.knowledgebase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 文档切片（检索的最小单元）。
 * embeddingJson 存放向量（JSON float 数组），MySQL 即向量库；
 * BM25 倒排索引由应用层（jieba 分词）基于 content 构建。
 */
@Entity
@Table(name = "kb_chunk", indexes = {
        @Index(name = "idx_chunk_document", columnList = "documentId")
})
public class KbChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    /** 冗余文档标题，召回结果展示时免去再 join */
    @Column(length = 256)
    private String documentTitle;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    /** 向量 JSON，如 [0.012,-0.03,...]；嵌入失败时为 null */
    @Column(columnDefinition = "longtext")
    private String embeddingJson;

    private Integer embeddingDim;

    /** 预存的 L2 范数，余弦计算时省一遍平方和 */
    private Double embeddingNorm;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getDocumentTitle() { return documentTitle; }
    public void setDocumentTitle(String documentTitle) { this.documentTitle = documentTitle; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }
    public Integer getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(Integer embeddingDim) { this.embeddingDim = embeddingDim; }
    public Double getEmbeddingNorm() { return embeddingNorm; }
    public void setEmbeddingNorm(Double embeddingNorm) { this.embeddingNorm = embeddingNorm; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
