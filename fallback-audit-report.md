# Response Fallback & Mock Mechanisms Audit

## Summary

**No dedicated fallback controller, circuit breaker, or mock data generator exists.** The observed JSON response (`query`, `count`, `items`, each item having `score`) is produced solely by `KbRetrievalController.search()` calling the real retrieval pipeline (BM25 + vector hybrid search). It is NOT served from a template, error payload, or mock stub.

## Code Paths Found

### 1. Primary response generator: `KbRetrievalController.search()`
- **File:** `src/main/java/com/example/demo/knowledgebase/web/KbRetrievalController.java` (lines 32-40)
- Produces exactly: `{ "query": ..., "count": ..., "items": [...] }`
- Items are `RetrievedItem` records with fields: `chunkId`, `documentId`, `documentTitle`, `content`, `score`, `matchedBy`
- `score` comes from the RRF fusion formula in `HybridRetrievalService.rrfFuse()` — a computed value, not a placeholder.
- Calls `retrievalService.retrieve(query, topK)` → `HybridRetrievalService` (real BM25 + vector pipeline).

### 2. No circuit breaker dependency
- **pom.xml:** Only `spring-cloud-starter-alibaba-nacos-discovery` and `spring-cloud-starter-alibaba-nacos-config` are included.
- **No** `spring-cloud-starter-circuitbreaker`, `resilience4j`, `sentinel`, or any other fault-tolerance library is present.
- Therefore there is **no** circuit-breaker-triggered fallback route.

### 3. Exception handling: `KbExceptionHandler`
- Returns `{ "status": 400/500, "message": ... }` on validation or service errors.
- Does NOT return a structured search result — only plain error bodies.
- A `KbServiceException` propagates through; no catch-and-fallback-to-mock logic.

### 4. Named "fallback" in HybridRetrievalService (not a mock)
- Lines 85-99: A local variable named `fallback` stores MySQL lookups for chunks whose content was not loaded via the vector hit projection.
- This is **data-fetching fallback**, not an API-level fallback or mock data source. It still reads from the real database.

### 5. Nacos relationship — service runs independently
- `application.yml` line 27: `- optional:nacos:ai-knowledge-base.yaml` — the `optional:` prefix means the app starts even if Nacos is unavailable.
- `SpringbootDemoApplication.java`: Has `@EnableDiscoveryClient` but there is **no** `@ConditionalOnBean` gating on Nacos services. Controllers are always registered.
- Nacos registration/discovery is purely for service mesh coordination; the `/api/kb/search` endpoint does **not** depend on successful Nacos registration to function.

### 6. Vector roadblock fallback (graceful degradation)
- `HybridRetrievalService.retrieve()` lines 60-64: If the embedding client throws (e.g., AI API unreachable), it catches the exception, logs a warning, sets `vectorHits = List.of()`, and continues with pure BM25.
- This is graceful degradation, not a mock — it still returns real BM25 results.

## Response Field Comparison Against Observed Output

| Field | Source | Matches observed? |
|-------|--------|-------------------|
| `query` | From `SearchRequest.query()` request body | Yes |
| `count` | `items.size()` | Yes |
| `items` | `List<RetrievedItem>` from hybrid retrieval | Yes |
| items[].chunkId | RetrievedItem constructor | Yes |
| items[].documentId | From vector hit or null | Yes |
| items[].documentTitle | From vector hit or null | Yes |
| items[].content | From vector hit projection, or DB lookup | Yes |
| items[].score | RRF fused score (bm25Weight/(rrfK+rank) + ...) | Yes — 0.0327... matches plausible BM25 rank contribution |
| items[].matchedBy | Set-based tag: "BM25", "VECTOR", or "BM25+VECTOR" | Yes |

All fields match the actual code path — no discrepancy suggesting a separate mock/template generator.

## Conclusion

- **No mock or fallback service generates the response.** The JSON comes from the live retrieval pipeline.
- **Nacos registration is not required** for the `/api/kb/search` endpoint. The `optional:` import prefix and lack of conditional beans ensure the controller works even if Nacos is down.
- **Vector roadblock degrades to BM25-only** but never returns dummy/placeholder data.
- **Score values are genuine computed RRF scores**, not fixed mocks.
- To reproduce identical responses across environments, check: (a) the BM25 index contains the same chunk text ("差旅报销制度"), (b) the same Jieba tokenizer output, (c) consistent vector embeddings, and (d) matching RRF weights/top-K settings.
