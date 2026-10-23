# Production-readiness assessment

## Verdict
**Not production-ready as a deployable system. It is a strong, well-tested prototype whose control-plane core is
designed to production standards.** The parts that are hard to retrofit (concurrency model, governance semantics,
failure handling, audit design) are done and tested. The parts that make something *operable* (durability,
identity, isolation, persistence, observability export, deployment) are not built.

Legend: **Solid** = implemented and tested to a production standard for its scope. **Partial** = works, with a known gap.
**Missing** = not built.

## A. Orchestrator control plane
| Area | Status | Evidence | Gap to production |
|---|---|---|---|
| Concurrency correctness | **Solid** | single-writer coordinator, generation tokens; 77 tests x 23 runs incl. CPU contention, no flakes | Formal model/stress at higher scale; long soak test |
| Governance semantics | **Solid** | gates fail closed; policies BLOCK/CRITICAL; approval before commit; HIGH needs approval (refused at construction) | Policy versioning and an exception/waiver workflow |
| Resilience controls | **Solid** | bounded retries, fallback, timeouts, failure & replan budgets, rollback with ordered compensation, safe-stop | Compensation actions are stubs in demos; must be idempotent and tested per real side effect |
| Dynamic re-planning | **Solid** | minimal invalidation; re-run passes gates/approvals again; budgeted | Reviewer UI is not told when its request was cancelled (no cancel callback on `ApprovalGateway`) |
| Audit trail | **Partial** | hash chain, tamper test, secrets never echoed | In memory; chain not anchored externally (someone with write access can rewrite all of it); unbounded growth; no retention/export |
| Metrics | **Partial** | success rate, retry/rollback rate, MTTR, p50/p95 | No exporter (Prometheus/OpenTelemetry), no per-stage breakdown, no tracing, no alerting |
| Durability and recovery | **Missing** | engine is single-use and in memory | Persist run state/events (event sourcing fits the design); resume after crash; idempotency keys for agent side effects |
| Scale and tenancy | **Missing** | one engine per run, caller supplies the pool | Run scheduler, queue/backpressure, quotas, bounded pools, multi-tenant isolation |
| Control-plane security | **Missing** | `submitChange`, kill switch and approvals trust the caller | AuthN/Z, approver identity, separation of duties (author != approver), multi-party approval, signed decisions |
| Agent isolation | **Partial** | `agentic-sdlc`: each run in its own git worktree, path guard, write scope from the approved plan; otherwise agents run in-process and cancel = thread interrupt | Sandboxed execution (container/VM), resource and network limits, handling of agents that ignore interrupts |
| Real agents | **Partial** | deterministic role agents producing real evidence from this repository; Claude agents in `agentic-sdlc` (analysis, planning, implementation, repair, review, docs) with schema-validated outputs, bounded turns, a per-run cost cap, call ledger and redacted transcripts, tested with scripted model turns | No live end-to-end LLM run has completed yet (recorded attempts: API 400 at the first call); no evaluation harness or prompt versioning; a non-retryable API error still uses the stage's second attempt |
| Requirement analysis | **Partial** | keyword rules in `orchestrator`; LLM analyst plus mandatory human clarification of every ambiguity in `agentic-sdlc` | LLM path not yet proven on a live run |
| Policies | **Partial** | secrets, protected paths, required evidence | Regex scanners miss obfuscation; need real SAST/secret scanners and licence/dependency checks |

## B. URL shortener service
| Area | Status | Evidence | Gap to production |
|---|---|---|---|
| Core rules and tests | **Solid** (for scope) | validation incl. SSRF and obfuscated IPs, TTL boundaries, collision retry, bounded attempts, 2,000-way concurrent counting | DNS-rebinding not covered (no resolution at create or redirect time) |
| Persistence | **Partial** | Cassandra storage (LWT creates, counter clicks) + Redis cache-aside with negative caching and read-through on Redis outage; Testcontainers tests against real Cassandra 5 and Redis 7 | Run those tests in CI (needs Docker); schema migrations; multi-DC replication settings; durable audit storage |
| Abuse protection | **Missing** | none | Rate limiting, quotas, auth/API keys, malicious-URL reputation checks, code-enumeration defence |
| Spring web layer | **Solid** (for scope) | compiled and tested: full-context API tests plus a web-slice error-contract test; `mvn verify` green with a coverage gate | Contract tests against an OpenAPI document |
| Error handling and logging | **Partial** | RFC 7807 for every error incl. MVC and unexpected (500 with `errorRef`); `X-Request-Id` correlation in MDC and an access log; no log forging; URLs logged by host only | JSON log encoder and shipping, log-based alerting |
| Business audit trail | **Partial** | every create/redirect (incl. rejected) audited; per-link and recent-activity endpoints; bounded retention | Durable, append-only store; authentication on the audit endpoints; retention policy |
| Operability | **Missing** | none | Health/readiness probes, metrics, per-environment config, graceful shutdown, Dockerfile, CI/CD |
| Analytics | **Partial** | exact in-memory counters | Hot-link write amplification and eventual consistency design pending (buffering in Redis, flush to Cassandra) |
| API contract | **Partial** | RFC 7807 errors | No OpenAPI document, no versioning/deprecation policy, no pagination |

## C. What would be needed, in order
1. **CI:** run `mvn verify` (tests + coverage gate) and `scripts/run-delivery.sh` on every change. (The build itself is now proven locally.)
2. **Durability:** event-sourced run store (the audit events already carry the information) and resumable runs.
3. **Control-plane security:** authenticated approvals and change requests, separation of duties, signed decisions.
4. **Real agents behind the existing `Agent` interface**, sandboxed, with schema-validated outputs and cost budgets.
5. **Persistence:** `LinkRepository` split + Cassandra adapter + Redis cache decorator, with Testcontainers integration tests.
6. **Observability and abuse controls:** metrics export and tracing; rate limiting, auth and URL reputation on the shortener.
7. **Deploy:** container image, health probes, config management, runbook, load and failure-injection tests.

## D. What this build does demonstrate
Controlled autonomy is enforced by the engine rather than requested of the agents: an agent cannot commit, approve,
bypass a policy, or hide its work, and every deviation (retry, fallback, violation, approval, re-plan, rollback)
is recorded in a tamper-evident log. `docs/SCENARIOS.md` shows each control firing in a real execution.
