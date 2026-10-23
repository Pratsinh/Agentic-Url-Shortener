# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Java 21 / Spring Boot 3.3 multi-module Maven project with two decoupled parts and a contract between them:

- **Orchestrator** (module `orchestrator`, `com.ascs.orchestrator`): the actual product. A governed agentic SDLC engine that turns a requirement into a DAG of stages and runs them with gates, policy guardrails, human approvals, retries/fallback, rollback, safe-stop, re-planning, a hash-chained audit log and metrics.
- **URL shortener** (module `shortener-service`, `com.ascs.shortener` + `AscsApplication`): the reference system the orchestrator plans and changes, an independent microservice. Hexagonal: `domain` / `app` / `infra` / `web`.
- **Generated client** (module `shortener-client`): `com.ascs.contract.shortener.*`, generated at build time from `shortener-api` by openapi-generator (`native` library); never hand-edit or check in generated code.
- **Tool adapters** (module `orchestrator-tools`, `com.ascs.orchestrator.tools.shortener`): expose shortener operations as gateway tools over the generated client; also builds `*-exec.jar` running `ToolGatewayDemo`.
- **LLM agents** (module `agentic-sdlc`, `com.ascs.agentic`): Claude agents (Anthropic Java SDK) on the orchestrator engine. Discovery graph (analyze -> human clarify -> plan [approval]) then a delivery graph compiled from the approved plan (`pipeline/Graphs.java`), ending release [approval] -> commit -> pull-request (opt-in `AGENTIC_PR=on` + `GITHUB_TOKEN`; `vcs/PullRequests` can open but never merge a PR). Runs in a git worktree on `agentic/<runId>`; records under `.agentic/runs/`. Run with `./scripts/run-agentic.sh` (needs `ANTHROPIC_API_KEY`); tests use `ScriptedProvider` and need no key. See `docs/AGENTIC_SETUP.md`.
- **Contract** (module `shortener-api`): `openapi/shortener-v1.yaml`, the shortener's OpenAPI contract. `shortener-service` depends on it and serves it at `/api/v1/openapi.yaml`; `ApiContractTest` fails the build if controllers and contract drift, so an endpoint change means editing the YAML too. Clients (`shortener-client`) are generated from it, never written against service code.

Read `docs/TECHNICAL_DESIGN.md` (complete technical design) and `docs/DESIGN.md` (control flow, concurrency model, requirement-to-code map). `docs/PRODUCTION_READINESS.md` is the honest status of what is built vs. designed.

## Commands

Orchestrator only (needs just a JDK 21, no Maven; the module has no dependencies; compiles into `orchestrator/target/core-classes`):
```bash
./scripts/run-scenarios.sh                          # greenfield / brownfield / ambiguous scenarios with governance trace
./scripts/run-scenarios.sh --out docs/SCENARIOS.md  # regenerate the scenario report
./scripts/run-scenarios.sh --interactive            # act as the human approver on the terminal
./scripts/run-core.sh                               # minimal 7-stage SDLC demo (OrchestratorDemo)
./scripts/run-delivery.sh                           # role-agent pipeline -> docs/delivery (run `mvn verify` first for coverage data)
```

End-to-end validation (tests, agent pipeline, scenarios, then the service on port 18080 + `scripts/smoke-test.sh` + the tool-gateway demo against it): `./scripts/validate-all.sh [--cassandra]`.

Full build (JDK 21 + Maven):
```bash
mvn verify                                          # all modules: tests + JaCoCo (<module>/target/site/jacoco) + per-module coverage gate
mvn -pl orchestrator test -Dtest=WorkflowEngineTest # one class
mvn -pl orchestrator test -Dtest=ScenarioTest#greenfieldSucceedsUsingRetryFallbackAndAHumanApproval   # one method
mvn -pl shortener-service -am test -Dtest=ShortenerApiTest -Dsurefire.failIfNoSpecifiedTests=false
mvn install -DskipTests && mvn -pl shortener-service spring-boot:run   # shortener on :8080
```

The scripts are bash; on Windows run them via Git Bash. Coverage gate (root `pom.xml` pluginManagement, applied per module): 99% lines/branches, 100% methods; the only uncovered code is documented as unreachable in `docs/CODE_REVIEW.md` — do not lower the gate to make new code pass, test it.

## Architecture rules that span files

- **The orchestrator and the shortener never depend on each other at compile time** (enforced by the module graph and the design agent's conformance check). The orchestrator module has no dependencies at all; the scripts compile it with plain `javac`.
- **Only `shortener/web` and `AscsApplication` may depend on Spring**; `shortener/infra/cassandra` and `shortener/infra/redis` are the only other code needing driver libraries. `web/AppConfig` wires the core; `web/ExternalStorageConfig` holds the beans that need real Cassandra/Redis.
- **Storage** is selected by `shortener.storage=memory|cassandra` (profile `cassandra`, infra from `docker-compose.yml`); a non-empty `shortener.cache.redis-url` wraps the store in `CachingLinkRepository` (cache-aside, negative caching, read-through when Redis fails). Driver errors must surface as `ShortenerException.StorageUnavailable` (503), never raw driver exceptions; a failed click write must not fail a redirect.
- **Single-writer coordinator** (`WorkflowEngine`): one thread owns all run state; agent results, human decisions, change requests and the kill switch arrive as events on one queue. Do not add locks around run state or mutate it from worker threads. Stale results/decisions are discarded via a per-stage *generation* counter.
- **Agents only return values** (`Agent` → `AgentResult`); they see an immutable `StageInput` containing only *ancestor* artifacts. The engine validates and commits. Commit order is: exit gates → policies → (approval if required) → commit to `ArtifactStore`. Rejected output must never reach downstream stages.
- **Agents reach external systems only through `orchestrator/tools/ToolGateway`.** Order: revoked run / call budget -> tool exists and granted to the stage -> argument validation -> `ToolPolicy`s (secret check always on) -> human approval for `Risk.MUTATING` -> execute with correlation id (`X-Request-Id`) -> output truncated / withheld if it looks like a credential. Read-only calls retry on 5xx/unreachable; mutating calls are never retried (no idempotency keys yet). Audit records tool, argument names and outcome, never argument values. The `ToolPlanner` (LLM seam) only proposes calls.
- **Shortener tool specs come from the OpenAPI document** (`ShortenerContract`: operationId, summary, parameters; risk = GET read-only, else mutating). `ShortenerTools` refuses to start if a binding's arguments differ from the contract's, so changing an endpoint means updating the YAML, the binding, and regenerating the client.
- **Policy severity semantics**: `BLOCK` fails the attempt (retryable); `CRITICAL` fails the stage and safe-stops the run and is never retried.
- **Governance is validated at construction**: `WorkflowGraph` rejects cycles/duplicates/unknown deps; a HIGH-impact stage without `requiresApproval` is refused. Gates fail closed.
- **Re-planning** (`submitChange`) creates a new artifact version and invalidates only stages that consumed the old one; re-runs pass all gates/policies/approvals again.
- `AuditLog` is hash-chained (`verify()` detects tampering) and must never echo secrets.
- `planner/CodebaseImpactAnalyzer` scans the **real source tree** of `shortener-service` (`Scenarios.TARGET_SYSTEM`; textual class references). The brownfield scenario and `ScenarioTest` therefore depend on actual shortener class names/references — renaming or re-wiring shortener classes can change scenario output and assertions.
- `docs/SCENARIOS.md` is generated by `ScenarioRunner` from real audit logs; regenerate it rather than hand-editing.
- `docs/delivery/*` is generated by `DeliveryRunner` (the role agents in `orchestrator/agents`); regenerate it rather than hand-editing. The design, code-review and QA agents scan `src/main/java` and `src/test/java` of every module (`SourceFile.modules`), and QA merges every module's JaCoCo and surefire reports, so test fixtures that contain rule triggers (fake keys, empty catch blocks, `@Verifies(`, `@Test`) must be assembled from string fragments, or they are counted as real findings/claims.
- Shortener core logs via `System.Logger` (no logging-framework dependency outside `web`); logs and audit records carry the URL host only, never the full URL. Every create/redirect outcome goes to the `AuditTrail` port.

## Testing conventions

- Core tests run without Spring. `orchestrator/src/test/java/com/ascs/orchestrator/Fx.java` holds the fixtures (stage `x` produces `x.txt`; `ok`/`boom` agents); tests that need the repository root use `RepoRoot.PATH` (Maven runs tests with the module as working directory). `shortener/MutableClock` controls time for TTL tests.
- `@Verifies` (`com.ascs.Verifies`, source retention) exists once per module's test tree on purpose: the modules share no code.
- Tests assert governance behaviour precisely: exact agent invocation counts under retry, commit-after-approval ordering, rollback order, CRITICAL-never-retried, secrets absent from audit. Concurrency tests use real threads, latches and barriers — keep them deterministic (no sleeps-as-synchronization).
- Tag tests with `@Verifies({"AC-n.m"})` (ids from `orchestrator/agents/StoryCatalog`); the QA agent builds functional coverage from these. New behaviour needs a criterion in the catalog and a verifying test.
- Code-review waivers are explicit comments on the same or preceding line: `// review:accepted CR-nn <reason>`.
- Spring tests: `ShortenerApiTest` (full context) and `ApiErrorHandlingTest` (web slice with a hand-written stub service). Docker-backed tests (`CassandraStorageTest`, `RedisCacheTest`, `CassandraProfileApiTest`) use `@Testcontainers(disabledWithoutDocker = true)`: skipped without Docker, and the QA agent then reports their criteria as not passed. The coverage gate excludes the driver adapters and `ExternalStorageConfig` for that reason. Avoid Mockito: its Byte Buddy version in Spring Boot 3.3 does not support JDKs newer than 23.

## Known limitations (don't assume otherwise)

The `orchestrator` role agents and the tool-gateway demo planner are deterministic and rule-based (no LLM; `ToolPlanner` is the seam); the scenario agents are stubs; the `agentic-sdlc` LLM agents have not completed a live end-to-end run yet; all state is in memory (no durability/resume); control-plane operations are unauthenticated; the Cassandra/Redis tests run only where Docker (or `CASSANDRA_CONTACT_POINTS`/`REDIS_URL`) is available.

## Shortener API

`POST /api/v1/links` `{"url","ttlSeconds"}` → create; `GET /{code}` → 302 redirect and counts a click; `GET /api/v1/links/{code}/stats`; `GET /api/v1/links/{code}/audit`; `GET /api/v1/audit?limit=`; `GET /api/v1/openapi.yaml` (the contract). Every error is an RFC 7807 problem response (incl. Spring MVC errors and a 500 with `errorRef`): 400 invalid URL/TTL/body, 404 unknown, 410 expired, 503 code space exhausted or Cassandra unavailable. Responses carry `X-Request-Id`. `UrlValidator` includes SSRF / obfuscated-IP checks.
