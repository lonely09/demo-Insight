-- =====================================================================
-- AI 知识库 MySQL 初始化脚本（MySQL 8.0+，utf8mb4）
-- 开发期可直接用 spring.jpa.hibernate.ddl-auto=update 自动建表；
-- 生产建议执行本脚本建表，并把 ddl-auto 改为 none。
-- 说明：BM25 倒排索引在应用进程内（jieba 分词），不依赖 MySQL FULLTEXT；
--       向量以 JSON 存于 embedding_json，由应用层做余弦召回。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS ai_knowledge_base
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_knowledge_base;

CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    title         VARCHAR(256) NOT NULL COMMENT '文档标题',
    source        VARCHAR(64)           DEFAULT 'manual' COMMENT '来源：manual/file/url/业务系统名',
    status        VARCHAR(32)  NOT NULL COMMENT 'PROCESSING/READY/FAILED',
    chunk_count   INT          NOT NULL DEFAULT 0 COMMENT '切片数量',
    error_message VARCHAR(1024)         DEFAULT NULL COMMENT '入库失败原因',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_doc_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档';

CREATE TABLE IF NOT EXISTS kb_chunk (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '切片ID（检索最小单元）',
    document_id     BIGINT   NOT NULL COMMENT '所属文档',
    document_title  VARCHAR(256)      DEFAULT NULL COMMENT '冗余标题，免join',
    chunk_index     INT      NOT NULL COMMENT '文档内序号，从0开始',
    content         LONGTEXT NOT NULL COMMENT '切片正文（BM25 索引源）',
    embedding_json  LONGTEXT          DEFAULT NULL COMMENT '向量 JSON float 数组（向量库数据）',
    embedding_dim   INT               DEFAULT NULL COMMENT '向量维度',
    embedding_norm  DOUBLE            DEFAULT NULL COMMENT '预存 L2 范数，加速余弦',
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chunk_document (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文档切片（含向量）';

-- =====================================================================
-- 业务数据统计：维表 + 销售事实表（对话式数据统计 Agent 的数据底座）
-- 开发期由 spring.jpa.hibernate.ddl-auto=update 自动建表；
-- 生产建议执行以下脚本建表后把 ddl-auto 改为 none。
-- =====================================================================

CREATE TABLE IF NOT EXISTS bi_channel (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '渠道ID',
    name       VARCHAR(128) NOT NULL COMMENT '渠道名称',
    code       VARCHAR(64)  NOT NULL COMMENT '渠道编码（唯一）',
    type       VARCHAR(16)  NOT NULL COMMENT 'ONLINE/OFFLINE',
    status     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_channel_code (code),
    KEY idx_bi_channel_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='渠道维表';

CREATE TABLE IF NOT EXISTS bi_product (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
    sku        VARCHAR(64)   NOT NULL COMMENT '商品SKU（唯一）',
    category   VARCHAR(32)   NOT NULL COMMENT '品类',
    price      DECIMAL(12,2) NOT NULL COMMENT '标准单价（元）',
    status     VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_product_sku (sku),
    KEY idx_bi_product_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品维表';

CREATE TABLE IF NOT EXISTS bi_activity (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    name        VARCHAR(128)  NOT NULL COMMENT '活动名称',
    type        VARCHAR(32)   NOT NULL COMMENT 'PROMO/MEMBER/LIVE',
    start_date  DATE          NOT NULL COMMENT '开始日期',
    end_date    DATE          NOT NULL COMMENT '结束日期',
    discount    DECIMAL(4,2)  NOT NULL DEFAULT 1.00 COMMENT '活动折扣乘数（0.85=85折）',
    status      VARCHAR(32)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='活动维表';

CREATE TABLE IF NOT EXISTS bi_region (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '区域ID',
    name       VARCHAR(128) NOT NULL COMMENT '区域名称',
    code       VARCHAR(64)  NOT NULL COMMENT '区域编码（唯一）',
    level      INT          NOT NULL COMMENT '层级：1=大区 2=省/直辖市 3=市',
    parent_id  BIGINT                DEFAULT NULL COMMENT '上级区域ID',
    status     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_region_code (code),
    KEY idx_bi_region_parent (parent_id),
    KEY idx_bi_region_level (level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='区域维表';

CREATE TABLE IF NOT EXISTS bi_sales_record (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    stat_date   DATE          NOT NULL COMMENT '销售日期',
    channel_id  BIGINT        NOT NULL COMMENT '渠道ID',
    product_id  BIGINT        NOT NULL COMMENT '商品ID',
    region_id   BIGINT        NOT NULL COMMENT '区域ID',
    activity_id BIGINT                 DEFAULT NULL COMMENT '活动ID（非活动期为空）',
    quantity    INT           NOT NULL COMMENT '销量（件）',
    amount      DECIMAL(14,2) NOT NULL COMMENT '成交额（元）= 单价 x 数量 x 活动折扣',
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bi_sales_date (stat_date),
    KEY idx_bi_sales_channel (channel_id),
    KEY idx_bi_sales_product (product_id),
    KEY idx_bi_sales_region (region_id),
    KEY idx_bi_sales_activity (activity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='销售流水事实表';
