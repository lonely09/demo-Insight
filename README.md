# insight-server — AI 知识库服务（集成业务用）

企业级 AI 知识库：文档入库自动切块、Embedding 向量化，检索走 **BM25 关键词召回 + 向量语义召回双链路，RRF 融合**，对外提供检索 API 与 RAG 问答 API。注册中心 / 配置中心集成 **Nacos**，**MySQL 同时作为业务库与向量库**。
前端地址：[demo-Insight-vue](https://github.com/lonely09/demo-Insight-vue)

## 业务数据统计 Agent（对话式问数）

项目同时包含一个面向业务人员的对话式数据统计 Agent：自然语言完成指标统计、筛选、分组对比、区域下钻、趋势与 CSV 导出，并保证**口径统一、区域权限合规、结果可追溯**。

- 数据底座：渠道 / 商品 / 活动 / 区域维表 + 销售流水事实表，启动自动造约 3000 条演示数据
- 自然语言规划：LLM 输出严格 JSON 的查询规格，失败自动回退规则解析；数字始终由统计引擎计算
- MCP Server：`POST /mcp` 暴露 `bi_metrics` / `bi_ask` / `bi_parse` / `bi_trace`，供 Claude Desktop、Cursor、Dify 等调用
- 前端页面：`D:\ai_work\insight-ai-web`（Vue 3 + Vite），入口 `http://localhost:5173/?view=bi-agent`
- 详细设计、API、MCP、权限矩阵与示例问题：[BI_AGENT.md](BI_AGENT.md)

默认使用 **Nacos + MySQL**（`mysql` profile），启动脚本：

```bat
cd /d D:\ai_work\insight-server
run-mysql.cmd
```

> 必须带 `-Dfile.encoding=UTF-8` 启动，否则 Nacos 上的中文 YAML 在 Windows GBK 环境会解析失败；`run-mysql.cmd` 已处理。H2 仅作离线回退：`run-local.cmd`。


## 技术栈

| 组件 | 选型 | 说明 |
|---|---|---|
| JDK | 17 | Zulu 17 |
| 框架 | Spring Boot 3.2.12 + Spring Cloud 2023.0.4 | |
| 注册/配置中心 | Spring Cloud Alibaba 2023.0.1.3 (Nacos) | 服务注册 + `spring.config.import` 拉取配置 |
| 存储/向量库 | MySQL 8（`kb_document` / `kb_chunk`） | 向量以 JSON 存 `embedding_json` 列，应用层余弦召回 |
| BM25 | jieba-analysis 中文分词 + 进程内倒排索引 | 标准 Okapi BM25，k1/b 可配 |
| Embedding / LLM | OpenAI 兼容协议（/embeddings、/chat/completions） | 可接 OpenAI / 豆包 / 通义 / One-API / Ollama |

## 目录结构

```
com.insightai.knowledgebase
├── config        KbProperties（kb.* 全量参数）/ KbConfig
├── domain        KbDocument / KbChunk（JPA 实体）
├── repository    Spring Data 仓库 + 向量/文本投影
├── chunk         Chunker（段落→句子→贪心装填+重叠）
├── embedding     EmbeddingClient / OpenAiCompatibleEmbeddingClient
├── vector        VectorStore 抽象 / MysqlVectorStore（JSON 列 + 余弦 TopK）
├── retrieval
│   ├── bm25      JiebaTokenizer / Bm25Index（进程内倒排，Okapi BM25）
│   ├── HybridRetrievalService（双路召回 + 加权 RRF 融合，向量路失败自动降级 BM25）
│   └── RetrievedItem
├── chat          OpenAiCompatibleChatClient / RagChatService（引用编号 [1][2]）
├── service       KbIngestService（入库流水线、删除、索引重建）
├── startup       Bm25IndexInitializer（启动时从 MySQL 重建 BM25 索引）
├── web           文档管理 / 检索问答 Controller + DTO + 全局异常
└── support       KbServiceException
```

## 检索链路

```
query ──┬── jieba 分词 ──► BM25 倒排索引（k1=1.2,b=0.75）──► Top-N 稀疏召回   ┐
        │                                                                    ├─► 加权 RRF 融合
        └── Embedding API ──► MySQL 向量全量余弦 ──► Top-N 语义召回 ──────────   score=Σ w/(60+rank)
                                                          │
                                              final-top-k 片段 ──► 引用拼接 ──► LLM 生成答案
```

为什么用 RRF：BM25 得分（无上界）与余弦相似度（[-1,1]）量纲不同，无法直接加权相加；RRF 只用**名次**融合，对分数量纲免疫，是混合检索的工业标准做法。

## 配置（⚠️ 需要你补全的占位）

全部集中在 `src/main/resources/application.yml`，也可整体搬到 Nacos 的 `ai-knowledge-base.yaml`（推荐，支持集中管理）：

1. **Nacos**：`NACOS_SERVER_ADDR`（地址）、`NACOS_USERNAME/PASSWORD`、`NACOS_NAMESPACE`
2. **MySQL**：`MYSQL_HOST/PORT/DATABASE/USERNAME/PASSWORD`；schema.sql 默认建库 `ai_knowledge_base`，
   若用其它库名（如 `ai_work`）改一下脚本里的 `USE`，或直接依赖 `ddl-auto=update` 自动建表
3. **Embedding 模型**：`KB_EMBEDDING_BASE_URL / API_KEY / MODEL / DIMENSION`
   默认已配 **`qwen3.7-text-embedding-flash`**（阿里百炼），`DIMENSION` 支持 256 / 512 / 768 / 1024（默认 1024，入库时强校验）；
   base-url 用你的 workspace 端点（如 `https://{workspace}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`）
4. **Chat 模型**：`KB_CHAT_BASE_URL / API_KEY / MODEL`（默认 `qwen-plus`，可按需换成 qwen-max 等）

Nacos 上创建配置：dataId=`ai-knowledge-base.yaml`，group=`DEFAULT_GROUP`，内容可与本地 yml 的 `datasource` / `kb.*` 一致；本地以 `optional:nacos:` 前缀导入，Nacos 未就绪时不阻塞启动。

> **Nacos 3.x 兼容说明**（项目用 spring-cloud-alibaba 2023.0.1.3，内置 nacos-client 2.4.2）：
> 1. **`server-addr` 填服务端主端口 8848，不是 8080**——3.x 把控制台拆到独立端口 8080（浏览器访问用），客户端连 8080 会出现 gRPC 9848/9080 连不上、登录接口 404 等怪异报错；
> 2. 除 `8848`（HTTP）外，必须放通 gRPC 端口 **9848/9849**（规则：主端口 +1000/+1001），虚拟机记得放行防火墙；
> 3. Nacos 3.x 默认开启鉴权，`NACOS_USERNAME/PASSWORD` 必须配置正确；
> 4. `namespace` 填的是命名空间的 **ID**（控制台里每个命名空间旁的 ID 字符串），不是名称；
> 5. 若客户端仍连不上 3.x 服务端，终极方案是升级到 `spring-cloud-alibaba 2025.0.0.0`（内置 nacos-client 3.0.3，需 Boot 3.5.x）。

## API

```bash
# 入库文档（同步切块+向量化）
curl -X POST http://localhost:8080/api/kb/documents \
  -H 'Content-Type: application/json' \
  -d '{"title":"报销制度","content":"第一条 差旅费报销……","source":"hr"}'

# 混合检索（只召回，不生成）
curl -X POST http://localhost:8080/api/kb/search \
  -H 'Content-Type: application/json' -d '{"query":"出差住宿标准","topK":5}'
# 返回 items[].matchedBy 标注命中来源：BM25 / VECTOR / BM25+VECTOR

# RAG 问答（检索+生成，带 [1][2] 引用）
curl -X POST http://localhost:8080/api/kb/chat \
  -H 'Content-Type: application/json' -d '{"question":"出差住宿怎么报销？"}'

# 文档列表 / 详情 / 分块 / 删除
curl 'http://localhost:8080/api/kb/documents?page=0&size=20'
curl  http://localhost:8080/api/kb/documents/1/chunks
curl -X DELETE http://localhost:8080/api/kb/documents/1

# 重建进程内 BM25 索引（多实例刷新时调用）
curl -X POST http://localhost:8080/api/kb/documents/index/rebuild
```

## 构建 / 运行

```bat
set JAVA_HOME=D:\Program Files\devlopertools\zulu-jdk-17
mvnw.cmd clean package     :: 单测无需外部依赖（BM25/RRF/切块/余弦均为纯逻辑测试）
java -jar target\insight-server-0.0.1-SNAPSHOT.jar
```

## 参数调优（kb.retrieval.*）

| 参数 | 默认 | 说明 |
|---|---|---|
| bm25-top-k / vector-top-k | 10 / 10 | 两路各自的召回深度 |
| final-top-k | 5 | 融合后送给 LLM / 返回的片段数 |
| rrf-k | 60 | RRF 平滑常数 |
| bm25-k1 / bm25-b | 1.2 / 0.75 | BM25 词频饱和 / 长度归一化 |
| weights | [1.0, 1.0] | 两路 RRF 权重，如 `[0.4,0.6]` 偏语义 |
| kb.chunk.max-chars / overlap-chars | 500 / 80 | 切块大小与重叠 |

## 已知边界与演进路线

- **向量召回为应用层全量余弦**：10 万 chunk 内可接受；更大规模先把 `MysqlVectorStore` 换成 Milvus / PgVector 实现即可（接口不变）。
- **BM25 索引在单进程内存**：多实例各自持索引、以 MySQL 为准重建（提供 `/index/rebuild`）；量级上台阶后换 Elasticsearch（同 `VectorStore` 思路再抽 `KeywordStore` 接口）。
- **入库为同步**：大文档 / 批量导入建议改异步（文档状态字段已预留 PROCESSING/READY/FAILED）。
- 未做：多租户/权限（业务集成时在 Controller 前置鉴权层解决）、流式输出、rerank 精排（可在 RRF 之后追加 Cross-Encoder，`HybridRetrievalService` 结构已留位）。
