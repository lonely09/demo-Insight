# Request Routing & Load Balancer Behavior Analysis

**Task:** t_b432db63 — Trace Request Routing & Load Balancer Behavior  
**Service:** `ai-knowledge-base` (springboot-demo)  
**Codebase:** `D:/ai_work/springboot-demo/`

---

## 1. Incoming Request Path

**No API gateway or load balancer exists inside this codebase.**

- The service listens on **port 8080** directly via `server.port: 8080`.
- All incoming requests hit `http://<host>:8080/api/kb/*` directly.
- A simple `@Configuration` CORS filter allows preflight requests from `localhost:5173` and `127.0.0.1:5173` only.

### Conclusion
If there is an external API gateway or load balancer in front of multiple instances, it is deployed outside this repository (e.g., nginx, Kong, K8s Ingress, cloud ALB). Its logs/config are not present in this codebase.

---

## 2. Outgoing HTTP Calls — Hardcoded Endpoints, No Client-Side LB

### 2.1 Embedding API

```java
// OpenAiCompatibleEmbeddingClient.java line 68-70
String resp = restTemplate.postForObject(
    trimSlash(cfg.getBaseUrl()) + "/embeddings", ...);
```

Base URL (from application.yml):
```
${KB_EMBEDDING_BASE_URL:https://ws-uvwphlew3rkrvbbn.cn-beijing.maas.aliyuncs.com/compatible-mode/v1}
```

### 2.2 Chat / LLM API

```java
// OpenAiCompatibleChatClient.java line 60-61
restTemplate.postForObject(base + "/chat/completions", ...);
```

Base URL (from application.yml):
```
${KB_CHAT_BASE_URL:https://ws-uvwphlew3rkrvbbn.cn-beijing.maas.aliyuncs.com/compatible-mode/v1}
```

### 2.3 MySQL Database

Direct JDBC connection:
```
jdbc:mysql://${MYSQL_HOST:192.168.1.120}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:ai_work}
```

### Key Evidence: No Load Balancer Configuration

| Check | Result |
|---|---|
| `pom.xml` contains `spring-cloud-starter-loadbalancer`? | ❌ Not present |
| `pom.xml` contains `spring-cloud-starter-gateway`? | ❌ Not present |
| Any `@LoadBalanced` bean definition? | ❌ None found (search returned 0 matches) |
| Any `DiscoveryClient` usage for consuming services? | ❌ Only `@EnableDiscoveryClient` on main class (registration only) |
| Any `RestTemplate` configured with LB? | ✅ Two `RestTemplate` instances exist but both are constructed raw via `new RestTemplate(factory)` — no LB interceptor |

### Verdict on Outgoing Traffic

All outbound HTTP calls use **direct, unbalanced RestTemplate** connections to single hardcoded upstream endpoints. There is zero client-side load balancing, zero retry logic, zero circuit breaking. If the remote endpoint is down, the call fails outright.

---

## 3. Nacos Usage — Registration Only, No Discovery of Other Services

### Registration (Outbound)

```java
// SpringbootDemoApplication.java
@EnableDiscoveryClient
@SpringBootApplication
public class SpringbootDemoApplication { ... }
```

This causes the service to register itself into Nacos at `192.168.1.120:8848` under namespace `ba3dfbf5-f6df-40af-9652-66d05c59bcba`, group `DEFAULT_GROUP`. Other consumers *could* discover it if they query Nacos.

### Config Center (Pulling Centralized Config)

```yaml
# application.yml lines 24-27
spring.config.import:
  - optional:nacos:ai-knowledge-base.yaml
```

This imports centralized configuration from Nacos but is one-way pull-only. It does not enable client-side service discovery for consuming other registered services.

### Key Finding

Nacos in this codebase serves two purposes only:
1. **Service registration** — so others can find this service (passive consumption by others).
2. **Config center** — pulling shared configuration values.

There is **zero code that discovers or routes to other Nacos-registered services**. The `HybridRetrievalService`, `KbDocumentController`, and `KbRetrievalController` all operate on local state backed by MySQL.

---

## 4. Multi-Instance Support (Acknowledged but No Built-in LB)

The README explicitly acknowledges multiple instances:

> "BM25索引在单进程内存：多实例各自持索引、以MySQL为准重建（提供`/api/kb/documents/index/rebuild`）"

The `/index/rebuild` POST endpoint exists (`Bm25IndexInitializer`) and triggers a full rebuild of the in-memory BM25 inverted index from MySQL data. This confirms the design intention that multiple instances may coexist, each maintaining its own in-memory index synced to MySQL.

However, **the codebase provides no mechanism for distributing requests across instances**. If multiple instances are deployed behind a reverse proxy or cloud LB, the distribution is entirely handled externally.

---

## 5. Exact Endpoint / IP Handling Requests

### For incoming traffic to `ai-knowledge-base`:
- **Exact endpoint:** `http://<instance-ip-or-host>:8080/api/kb/*`
- **Routing decision:** Handled by whatever infrastructure deploys the instance — e.g., Docker compose networking, Kubernetes Service/Ingress, nginx reverse proxy, or cloud load balancer. None of this is captured in the codebase.

### For outgoing traffic from `ai-knowledge-base`:
| Target | Exact Endpoint | LB? | Notes |
|---|---|---|---|
| Embedding API | `https://ws-uvwphlew3rkrvbbn.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings` | ❌ No direct RestTemplate to single hardcoded endpoint |
| Chat / LLM API | `https://ws-uvwphlew3rkrvbbn.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions` | ❌ Same single hardcoded endpoint |
| MySQL DB | `192.168.1.120:3306` (database `ai_work`) | ❌ Direct JDBC, no pooling-aware failover beyond HikariCP's internal health checks |
| Nacos Registry/Config | `192.168.1.120:8848` | N/A — service registration and config pull, not traffic routing |

---

## 6. Summary Answers to Acceptance Criteria

> Pinpoint the exact endpoint/IP handling the request and explain the routing decision.

| Question | Answer |
|---|---|
| Which backend host processes incoming requests? | Each running instance of `ai-knowledge-base` on port 8080 processes requests locally. No internal router or gateway distributes traffic. |
| Is Nacos used for discovery to route requests? | ❌ No. Nacos is used for self-registration and pulling config. Nothing in the code discovers other services. |
| Are IPs hardcoded, DNS-based, or cache-based? | Outgoing calls use hardcoded Bailian cloud endpoints. MySQL uses hardcoded IP `192.168.1.120`. No DNS or cache layer. |
| Do multiple instances exist? | Yes, per the README (`/index/rebuild` endpoint confirms multi-instance design), but inter-instance request distribution is delegated to external infrastructure. |
| What handles the actual load balancing? | An external component — likely an nginx reverse proxy, Kubernetes Ingress/Service, or cloud provider load balancer — deployed alongside these instances. This is outside the codebase scope. |

---

## 7. Recommendations

1. **If you need client-side LB in front of multiple instances:** Add `spring-cloud-starter-alibaba-nacos-discovery` consumer pattern with `@LoadBalanced RestTemplate` or switch to WebFlux/Reactor Netty for reactive LB.
2. **For better resilience on external AI APIs:** Implement retry logic (Spring Retry or Resilience4j) rather than relying on a single hardcoded Bailian endpoint.
3. **For MySQL high availability:** Consider HikariCP `connectionTestQuery` or switching to a proxied MySQL endpoint (e.g., ProxySQL) if primary fails over.
4. **To debug which instance receives a request in production:** Enable detailed access logging (e.g., Tomcat AccessLogValve) and correlate with deployment metadata (pod name, host header).
