# 对话式数据统计 Agent（BI Agent）

在原有 AI 知识库服务里新增的一块业务数据统计能力：业务人员用自然语言提问，Agent 完成**指标统计、筛选、分组对比、区域下钻、趋势与 CSV 导出**，并在每次回答中带出**统一口径、区域权限范围、traceId 与结果指纹**。

> 本次按约定**跳过自动化测试**，用真实启动 + 接口冒烟验证（见文末）。原有知识库测试未改动。

---

## 1. 能力清单

| 能力 | 实现 |
|---|---|
| 指标统计 | 成交额(GMV) / 销量 / 流水记录数，指标名与口径统一登记在 `BiMetric` |
| 自然语言规划 | LLM 输出严格 JSON 的 `BiQuerySpec`；失败/超时/非法实体自动回退规则解析 |
| 筛选 | 渠道、商品、品类、区域、活动；支持别名、编码、线上/线下 |
| 分组 | 渠道 / 商品 / 品类 / 区域 / 活动 / 日期（日、周、月） |
| 对比 | 环比（前同长度窗口）、同比（去年同日历区间），含分组增长率 |
| 下钻 | 区域两级下钻（大区 → 省/直辖市），按权限范围重建树 |
| 占比 | 分组指标 / 全范围指标，输出百分比 |
| 导出 | 按 `traceId` 导出当时的结果快照 CSV（UTF-8 BOM，带口径/筛选/权限/指纹） |
| MCP | `/mcp` 暴露 `bi_metrics` / `bi_ask` / `bi_parse` / `bi_trace` 四个 MCP 工具 |
| 口径统一 | 单一指标登记表，问答、接口、导出共用 |
| 权限合规 | 角色 + 区域数据范围；越权查询直接 403，不静默裁剪 |
| 结果可追溯 | 每次提问落 `bi_audit_log`，保存解析规格、口径快照、结果 JSON、指纹、导出标记、规划来源 |

---

## 2. 目录与链路

```
com.insightserver.bi
├── domain        渠道/商品/活动/区域/销售流水（JPA 实体）
├── repository    Spring Data 仓库（含汇总与按日期取明细）
├── service       维表 CRUD 校验、销售流水、假数据生成
├── web           CRUD Controller / DTO / 全局异常 / BiMcpController（MCP Server）
├── audit         BiAuditLog / Repository / BiAuditService
└── agent
    ├── BiMetric / BiMetricDef        指标口径单一来源
    ├── BiLlmPlanner / BiLlmClient    LLM 规划（严格 JSON + 实体校验）
    ├── BiLlmProperties               LLM 开关/模型/超时/回退/熔断配置
    ├── BiDateRangeParser             中文日期区间解析
    ├── BiQueryParser                 规则解析（LLM 失败时的回退）
    ├── BiQuerySpec / BiQueryFilters  结构化查询规格
    ├── BiPermissionService           角色与区域范围校验
    ├── BiQueryEngine                 筛选、聚合、对比、下钻、指纹
    ├── BiAnswerComposer              自然语言结论
    ├── BiExportService               CSV 快照导出
    └── BiAgentService                LLM/规则规划 → 权限 → 统计 → 结论 → 审计
```

一次提问的链路：

```
自然语言问题
  → BiLlmPlanner（LLM 输出 JSON Spec，校验指标/维度/实体 id）
      └─ 失败、超时、JSON 非法 → BiQueryParser 规则解析
  → BiPermissionService（角色 + 区域范围求交）
  → BiQueryEngine（按日期取明细 → 过滤 → 聚合 → 对比/下钻/指纹）
  → BiAnswerComposer（中文结论 + 口径 + traceId）
  → BiAuditService（落审计 + 结果快照 + planner/模型/原始输出）
  → BiAgentController（JSON / CSV） / BiMcpController（MCP）
```

**LLM 的边界**：LLM 只输出 `BiQuerySpec`，不写 SQL、不输出统计数字；实体 id 必须能通过本地维表校验，否则判失败并回退规则解析。最终数字始终由 `BiQueryEngine` 计算，权限校验在规划之后、取数之前。

---

## 3. 数据底座与假数据

- 维表：`bi_channel`、`bi_product`、`bi_activity`、`bi_region`
- 事实表：`bi_sales_record`（日期 + 渠道 + 商品 + 区域 + 活动 + 销量 + 成交额）
- 审计表：`bi_audit_log`

启动时 `BiDataSeeder` 在业务表为空的情况下自动造数（`bi.seed-on-startup=true`，`bi.seed-days=120`）：

- 4 个大区 + 10 个省/直辖市（二级区域树）
- 6 个渠道（天猫/京东/抖音/拼多多/微信小程序/线下门店）
- 10 个商品（家电/数码/个护/美妆/食品）
- 5 个活动（618、会员日、开学季、国庆中秋、双11）
- 约 3000 条销售流水，日期相对“今天”滚动，周末上浮、活动期折扣、区域与渠道有强弱差异

手动重造：`POST /api/bi/seed?force=true`。

CRUD 接口：

| 资源 | 接口 |
|---|---|
| 渠道 | `GET/POST /api/bi/channels`，`GET/PUT/DELETE /api/bi/channels/{id}` |
| 商品 | `GET/POST /api/bi/products`，`GET/PUT/DELETE /api/bi/products/{id}` |
| 活动 | `GET/POST /api/bi/activities`，`GET/PUT/DELETE /api/bi/activities/{id}` |
| 区域 | `GET/POST /api/bi/regions`，`GET/PUT/DELETE /api/bi/regions/{id}` |
| 销售流水 | `GET/POST /api/bi/sales`，`DELETE /api/bi/sales/{id}` |
| 假数据 | `POST /api/bi/seed?force=false` |

已做关联保护：有销售流水的渠道/商品/区域/活动不能删除；区域有子节点不能删除；编码重复返回 409。

---

## 4. 指标口径

指标和口径只在 `BiMetric` 登记一次，任何出口都从这里取：

| key | 名称 | 口径 |
|---|---|---|
| `amount` | 成交额(GMV) | 元；已包含命中活动折扣，即实际成交金额；不含运费/服务费 |
| `quantity` | 销量 | 件；流水 `quantity` 字段，活动期销量未按折扣折算 |
| `records` | 流水记录数 | 条；命中流水行数，口径 = 行数而非汇总量 |
| `share` | 占比 | %；该分组指标 / 同口径同筛选同区间的全范围指标 |
| `daily` | 每日趋势 | 按 `stat_date` 逐日桶；缺失日期视为 0 |
| `mom` | 环比增长率 | %；（当前区间 - 前同长度相邻区间）/ 前同长度相邻区间 |
| `yoy` | 同比增长率 | %；（当前区间 - 去年同日历区间）/ 去年同日历区间 |

`share / daily / mom / yoy` 是展示与对比语义，由引擎统一计算；`amount / quantity / records` 是可直接问的统计指标。

---

## 5. API

身份用请求头模拟（生产环境应由网关/SSO 注入，禁止前端自报角色）：

| 头 | 说明 |
|---|---|
| `X-Bi-User` | 用户标识，缺省 `anonymous` |
| `X-Bi-Role` | `ADMIN` / `ANALYST` / `BUSINESS_USER` / `VIEWER`，缺省 `ANALYST` |
| `X-Bi-Region-Id` | 业务用户必填；区域 ID / 编码 / 名称，含全部下级 |

### 5.1 提问

```bash
curl -X POST http://localhost:8080/api/bi/agent/ask \
  -H 'Content-Type: application/json' \
  -H 'X-Bi-User: alice' \
  -H 'X-Bi-Role: ANALYST' \
  -d '{"question":"上个月各区域的销量，环比"}'
```

返回结构（节选）：

```json
{
  "answer": "在 上个月（2026-09-01 至 2026-09-30），统计结果：销量合计 1,815 件……口径：……追溯：traceId=……",
  "totals": { "amount": 628919.05, "quantity": 1815, "records": 770 },
  "groups": [
    { "rank": 1, "key": "12", "name": "广东", "amount": 92655.33, "quantity": 255, "records": 99, "share": 14.05 }
  ],
  "trend": [],
  "comparison": {
    "type": "mom",
    "label": "环比（前 30 天）",
    "current": 1815.00,
    "previous": 2038.00,
    "growthRate": -10.94,
    "note": "口径一致：同一指标、同一筛选、同一区域权限范围"
  },
  "drill": [],
  "meta": {
    "traceId": "……",
    "metricName": "成交额(GMV)",
    "caliber": "元；已包含命中活动折扣……",
    "dateFrom": "2026-09-01",
    "dateTo": "2026-09-30",
    "groupBy": "region",
    "filters": [],
    "resultHash": "……",
    "planner": "LLM",
    "plannerModel": "Qwen/Qwen2.5-7B-Instruct",
    "plannerNote": null,
    "permission": { "role": "ANALYST", "scopeLabel": "全部区域", "exportAllowed": true }
  }
}
```

### 5.2 其它接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/bi/agent/ask` | 自然语言提问，返回结论 + 结构化数据 + 元信息 |
| POST | `/api/bi/agent/parse` | 只解析不取数，排查“问题被理解成了什么” |
| POST | `/api/bi/agent/export` | 提问后直接下载 CSV（同样落审计） |
| GET | `/api/bi/agent/export/{traceId}` | 按 traceId 导出当时的结果快照 |
| GET | `/api/bi/agent/metrics` | 指标口径目录、维度、角色矩阵、示例问题 |
| GET | `/api/bi/agent/history?limit=20` | 审计历史（管理员/分析师看全部，其他角色只看自己） |
| GET | `/api/bi/agent/trace/{traceId}` | 单次查询的完整追溯信息 |

### 5.3 示例问题

- `最近30天各渠道的成交额`
- `上个月各区域的销量，环比`
- `国庆期间天猫和京东的成交额对比`
- `各品类成交额占比`
- `最近7天成交额趋势`
- `华东区域下钻看各城市成交额`
- `卖得最好的5个商品`
- `线上渠道本月销量同比`

### 5.4 MCP Server 接入

后端已实现一个轻量 **MCP Server**，把 BI Agent 暴露成 MCP 工具，供 Claude Desktop、Cursor、Dify、自研 Agent 等调用。

- 端点：`POST http://localhost:8080/mcp`
- 协议：JSON-RPC 2.0 / MCP Streamable HTTP（只实现 POST JSON 响应，不实现 stdio 与 SSE GET）
- 支持方法：`initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`
- 协议版本：`2024-11-05` / `2025-03-26` / `2025-06-18`（不匹配时回退 `2025-06-18`）
- 会话：`initialize` 返回 `Mcp-Session-Id` 响应头，后续可原样带回

| 工具 | 作用 | 参数 |
|---|---|---|
| `bi_metrics` | 指标/口径/维度/LLM 状态 | 无 |
| `bi_ask` | 自然语言问数，返回结论 + 完整结构化结果 + traceId | `question` |
| `bi_parse` | 只做查询规划，返回 planner、BiQuerySpec、LLM 原始输出 | `question` |
| `bi_trace` | 按 traceId 查审计记录 | `traceId` |

调用示例：

```bash
# 1. initialize
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'X-Bi-User: alice' -H 'X-Bi-Role: ANALYST' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# 2. tools/list
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 3. 自然语言问数
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'X-Bi-User: alice' -H 'X-Bi-Role: ANALYST' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"bi_ask","arguments":{"question":"上个月各区域的销量，环比"}}}'
```

MCP 客户端配置（以支持远程 URL 的客户端为例）：

```json
{
  "mcpServers": {
    "bi-agent": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "X-Bi-User": "alice",
        "X-Bi-Role": "ANALYST"
      }
    }
  }
}
```

只支持 stdio 的客户端可以套一层 `mcp-remote`：

```json
{
  "mcpServers": {
    "bi-agent": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote", "http://localhost:8080/mcp",
        "--header", "X-Bi-Role: ANALYST"
      ]
    }
  }
}
```

安全边界：MCP 端点复用与 HTTP 相同的 `X-Bi-User / X-Bi-Role / X-Bi-Region-Id`，生产环境应放在网关注册/鉴权之后；`bi_ask` 与 `bi_trace` 都会走现有权限校验并写审计。

### 5.5 LLM 规划配置

LLM 只负责生成 `BiQuerySpec`，统计仍由引擎执行。配置前缀 `bi.agent.llm`：

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | `BI_LLM_ENABLED` | `true` | 是否启用 LLM 规划 |
| `base-url` | `BI_LLM_BASE_URL` | 复用 `kb.chat.base-url` | OpenAI 兼容地址 |
| `api-key` | `BI_LLM_API_KEY` | 复用 `kb.chat.api-key` | 为空则直接走规则解析 |
| `model` | `BI_LLM_MODEL` | `Qwen/Qwen2.5-7B-Instruct` | 规划模型 |
| `timeout-ms` | `BI_AGENT_LLM_TIMEOUT_MS` | `15000` | 超时后回退 |
| `fallback-to-rule` | `BI_AGENT_LLM_FALLBACK_TO_RULE` | `true` | 失败是否回退规则 |
| `failure-cooldown-seconds` | `BI_AGENT_LLM_FAILURE_COOLDOWN_SECONDS` | `60` | 失败熔断时间 |
| `json-mode` | `BI_AGENT_LLM_JSON_MODE` | `false` | 是否传 `response_format=json_object` |

返回的 `meta.planner` 会标明本次规划来源：

- `LLM`：模型规划成功
- `RULE_FALLBACK`：模型失败/超时/JSON 非法，已回退规则
- `RULE`：LLM 未启用、未配置 key，或处于熔断期

审计表同时记录 `planner`、`llm_model`、`llm_raw_output`、`llm_error`，前端结果卡片与「审计记录」都能看到。

> 实测当前 Nacos 里复用的 SiliconFlow key 对 `chat/completions` 返回 `20015 parameter invalid`，因此默认会走规则回退；要真正启用 LLM，请用 `BI_LLM_API_KEY` + `BI_LLM_MODEL` 指定一个有权限的模型。LLM 成功/回退两条链路都已用本地 mock LLM 验证。

---

## 6. 权限模型

| 角色 | 数据范围 | 导出 | 历史 |
|---|---|---|---|
| `ADMIN` | 全部区域 | 允许 | 全部 |
| `ANALYST` | 全部区域 | 允许 | 全部 |
| `BUSINESS_USER` | **必须**指定 `X-Bi-Region-Id`，含下级 | 允许（聚合结果） | 仅自己 |
| `VIEWER` | 全部区域 | **拒绝** | 仅自己 |

关键行为：

1. 业务用户未带区域范围 → 403；
2. 问题点名授权范围外区域 → 403（例如华东业务用户问“华南各区域成交额”）；
3. 问题没点名区域 → 自动按授权范围统计，回答中标注“数据范围：华东（含下级）”；
4. 导出按 `traceId` 取结果快照，不是重新取数；跨用户导出非管理员/分析师会被拒绝。

生产接入建议：把 `X-Bi-*` 换成 Spring Security / 网关认证后的 `Authentication`，角色与区域范围从权限中心加载；默认策略改为拒绝。

---

## 7. 结果可追溯

每次 `ask` 都会写 `bi_audit_log`：

- 谁：`user_id`、`role`、`scope_label`
- 问了什么：`question`
- 按什么算：`metric_key/name/caliber`、`date_from/to`、`group_by`、`compare_type`、`filters_json`
- 算出什么：`row_count`、`total_amount`、`total_quantity`、`result_json`
- 怎么规划的：`planner`（LLM/RULE/RULE_FALLBACK）、`llm_model`、`llm_raw_output`、`llm_error`
- 是否可信：`result_hash`（SHA-256 结果指纹）、`status`、`duration_ms`
- 是否导出：`exported`、`exported_at`

`GET /api/bi/agent/trace/{traceId}` 可查看上述信息；导出的 CSV 文件头也带同一组信息，便于业务方复核“这份数是怎么来的”。

进入统计链路的失败和越权同样落审计（`FAILED` / `DENIED`），traceId 在解析前生成。

---

## 8. 自然语言支持范围

| 类型 | 示例 |
|---|---|
| 指标 | 成交额、GMV、销售额、营收、金额；销量、件数；订单、流水、记录数、笔数 |
| 时间 | 今天/昨天/前天、本周/上周、本月/上月、本季度/上季度、今年/去年、最近 N 天/周/月/年、9月、2026年9月、9月1日到9月15日、2026-09-01 到 2026-09-30、Q3 |
| 分组 | 各渠道/按渠道、各商品/按商品、各品类、各区域、各活动、按天/每周/每月趋势 |
| 筛选 | 渠道名/编码/别名（天猫、京东、抖音、拼多多、微信、门店）、商品名/SKU/别名、品类、区域名、活动名、线上/线下 |
| 对比 | 环比、同比、和上月比、和去年比 |
| 占比 | 占比、份额、比例、贡献度 |
| 下钻 | 下钻、细分、展开 |
| TopN | 前 5、Top10、卖得最好的 5 个 |

已知边界：

- 规则解析对“指代不明/多意图”问题只取最明确的那个；可用 `/parse` 查看解析结果；
- 指标目前是单指标（合计里始终附带成交额/销量/流水数）；
- 区域数据只到省/直辖市两级；
- 当前在应用层对日期区间明细做聚合，适合演示与中小数据量；大数据量应把 `query/aggregate` 换成 SQL 聚合，`BiQuerySpec` 与结果结构不变；
- 导出只提供聚合结果，不提供逐行明细（合规默认）；
- H2 为内存库，重启后审计与假数据重置；接 MySQL 后持久化。

---

## 9. 运行

### 9.1 后端（默认 Nacos + MySQL）

项目默认 profile 已改为 `mysql`，连接 Nacos（配置中心 + 注册中心）与 MySQL：

```bat
cd /d D:\ai_work\insight-server
mvnw.cmd -DskipTests package
run-mysql.cmd
```

`run-mysql.cmd` 会自动补 `JAVA_HOME`、以 **UTF-8 JVM** 启动并激活 `mysql` profile。等价的直接命令：

```bat
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 ^
  -jar target\insight-server-0.0.1-SNAPSHOT.jar ^
  --spring.profiles.active=mysql
```

> **必须加 `-Dfile.encoding=UTF-8`**。Spring Cloud Alibaba 2023.0.1.3 在解析 Nacos 配置时使用平台默认字符集（Windows 中文环境是 GBK）把内容再编码一次，Nacos 上的中文 YAML 会触发 `MalformedInputException`；加上 UTF-8 后配置可正常加载。`run-mysql.cmd` 与 `pom.xml` 的 `spring-boot:run` 都已带该参数。

MySQL 连接默认值（可用环境变量覆盖）：

| 变量 | 默认 |
|---|---|
| `MYSQL_HOST` | `192.168.1.120` |
| `MYSQL_PORT` | `3306` |
| `MYSQL_DATABASE` | `ai_work` |
| `MYSQL_USERNAME` | `mori` |
| `MYSQL_PASSWORD` | `Qaz123123` |

首次可执行 `src/main/resources/db/schema.sql` 建表；开发期 `ddl-auto=update` 也会自动建表。

> 审计表的大文本列必须是 `LONGTEXT`。如果历史版本已把 `bi_audit_log.answer/result_json/question/...` 建成 `tinytext`，会导致审计写入被截断/失败，执行 `schema.sql` 中对应定义或删表重建即可。

H2 仅作为离线回退：

```bat
run-local.cmd
```

### 9.2 前端（insight-ai-web）

对话页面已放在 `D:\ai_work\insight-ai-web`（Vue 3 + Vite），不再由 Spring Boot 内置静态页提供。

```bat
cd /d D:\ai_work\insight-ai-web
npm install
npm run dev
```

打开：

- 数据统计 Agent：<http://localhost:5173/?view=bi-agent>
- 也可从顶部导航「📊 数据统计 Agent」进入
- 后端指标目录：<http://localhost:8080/api/bi/agent/metrics>

Vite 已配置 `/api` 代理到 `http://localhost:8080`；如果后端端口不同，复制 `.env.example` 为 `.env.local` 并修改 `VITE_API_TARGET`。

生产构建：

```bat
npm run build
```

---

## 10. 本次验证

- 后端编译/打包：`BUILD SUCCESS`（JDK 17，跳过测试）
- **Nacos + MySQL 启动成功**：Nacos 配置 `ai-knowledge` 正常加载、MySQL Hikari 连接池建立、服务注册到 Nacos；BI 业务数据已存在（2977 条流水）
- 冒烟问题 7 个：渠道分组、区域环比、活动对比、品类占比、7 天趋势、区域下钻、Top5 商品，均返回 200 且分组/对比/下钻符合预期
- 业务用户越权（华东用户问华南）：403
- 只读用户导出：403
- 按 traceId 导出 CSV：200，文件头含口径/筛选/角色/traceId/结果指纹
- 审计表修正为 `LONGTEXT` 后，`/api/bi/agent/history` 能返回真实查询记录
- **LLM 规划成功链路**：本地 mock LLM 返回固定 Spec，`meta.planner=LLM`、`plannerModel=mock-model`，分组/日期/环比均按 Spec 执行
- **LLM 回退链路**：mock 返回非法 JSON 时 `meta.planner=RULE_FALLBACK` 并记录原因；随后 60 秒熔断期内为 `RULE`
- **审计**：`planner / llm_model / llm_raw_output / llm_error` 已落库，`/trace` 可查
- **MCP**：`initialize`、`notifications/initialized`（202）、`ping`、`tools/list`（4 个工具）、`tools/call` 的 `bi_metrics` / `bi_ask` / `bi_trace` 与未知工具错误均验证通过
- 前端 `insight-ai-web`：`npm run build` 成功；无头浏览器打开 `/?view=bi-agent` 验证了欢迎页、快捷问题、KPI、分组表、环比卡片与审计历史
- 当前 SiliconFlow key 调 `chat/completions` 返回 `20015 parameter invalid`，真实 LLM 请通过 `BI_LLM_API_KEY` / `BI_LLM_MODEL` 指定可用模型；失败会自动回退规则，不影响统计

未新增自动化测试（按要求跳过）；原有知识库测试保持原样。

