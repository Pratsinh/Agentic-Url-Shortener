# ascs-url-shortener

**URL shortener with a governed agentic SDLC orchestrator.** Agents execute under defined autonomy boundaries;
humans own oversight, approvals and final quality. The shortener is the reference system the orchestrator plans and changes.

Java 21, Spring Boot 3.3, built as six Maven modules; the orchestrator and the shortener never depend on each other's code:

| Module | What it is | Depends on |
|---|---|---|
| `shortener-api` | OpenAPI 3 contract of the shortener (`openapi/shortener-v1.yaml`): the only thing a client may depend on | nothing |
| `shortener-service` | The URL shortener microservice (Spring Boot); serves its contract at `/api/v1/openapi.yaml` and fails the build if controllers and contract drift (`ApiContractTest`) | `shortener-api` |
| `shortener-client` | Java client **generated at build time** from `shortener-api` (openapi-generator, `java.net.http` + Jackson); no hand-written code | `shortener-api` |
| `orchestrator` | The governed workflow engine, role agents and the **tool gateway**; plain Java, no framework | nothing (JDK only) |
| `orchestrator-tools` | Adapters that expose external systems as gateway tools; the shortener tools use `shortener-client` | `orchestrator`, `shortener-client` |
| `agentic-sdlc` | **LLM agents (Claude)** on the orchestrator: requirement -> analysis -> human clarification -> plan [approval] -> parallel implementation -> real build/tests -> review \|\| docs -> security scan -> release [approval] -> commit on an isolated branch -> pull request for human review | `orchestrator`, Anthropic Java SDK |

The orchestrator works on the shortener as a target system: it reads its source tree (impact analysis, review, QA),
and an agent calls the running service only through the governed tool gateway and the client generated from the contract.

**End-to-end architecture diagram:** [`docs/TECHNICAL_DESIGN.md` §4.0](docs/TECHNICAL_DESIGN.md#40-end-to-end-view).
**Generated design document** (component and sequence diagrams derived from the code): [`docs/delivery/design.md`](docs/delivery/design.md).
**Run the full SDLC on your own requirement:** [`docs/AGENTIC_SETUP.md`, walkthrough](docs/AGENTIC_SETUP.md#walkthrough-run-the-full-sdlc-on-your-own-requirement).

Start with the technical design document `docs/TECHNICAL_DESIGN.md` (architecture, data model,
API, security, failure modes, decisions); `docs/PRODUCTION_READINESS.md` gives an honest status.

## Agentic delivery: role agents and their evidence

A governed pipeline runs five role agents against this repository. Each agent's output must pass gates and policies
before it is committed, and the release decision needs a human approval:

```
requirements -> design -> development -+-> code-review --+-> release-readiness (HIGH impact: human approval)
                                       +-> qa -----------+
```

| Agent | Produces | Evidence |
|---|---|---|
| Requirements | User stories with Given/When/Then acceptance criteria (11 stories, 49 criteria) | [`docs/delivery/user-stories.md`](docs/delivery/user-stories.md) |
| Design | Design document, Mermaid component diagram derived from real package references, sequence diagrams, architecture-conformance check | [`docs/delivery/design.md`](docs/delivery/design.md), [`docs/DESIGN.md`](docs/DESIGN.md) |
| Development | Error handling (RFC 7807 everywhere), logging (request ids, access log), business audit trail; a Conventional Commits audit of the git history and a proposed commit plan | [`docs/delivery/development-report.md`](docs/delivery/development-report.md), `git log` |
| Code review | Every main and test file reviewed, with findings and their resolution | [`docs/delivery/code-review.md`](docs/delivery/code-review.md), [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) |
| QA | Unit tests; unit coverage (JaCoCo); functional coverage (each acceptance criterion traced to the tests that verify it); every gap against the 100% target | [`docs/delivery/qa-report.md`](docs/delivery/qa-report.md), `<module>/target/site/jacoco/index.html` |

The full run, including the hash-chained audit trail, is in [`docs/delivery/pipeline-run.md`](docs/delivery/pipeline-run.md).
These role agents are deterministic and rule-based, so their output is reproducible and testable.

## LLM agents with Claude (`agentic-sdlc`)

The same engine also runs real LLM agents: a plain-English requirement becomes a reviewed, tested, human-approved
commit on branch `agentic/<runId>`, optionally pushed and opened as a pull request for human review. Two workflow graphs: discovery is fixed, delivery is **compiled from the
human-approved plan** (the plan decides the tasks and their parallelism; the code decides every gate, retry,
fallback, approval and rollback; no model ever decides those).

```
discovery:  analyze (LLM) -> clarify (human) -> plan (LLM) [human approval; approve | reject | revise -> re-plan]
delivery:   baseline -> impl-T1..Tn (LLM, parallel) -> verify (real mvn; fallback repair) -+-> review (LLM, read-only) -+-> security (deterministic)
                                                                                         +-> docs (LLM) --------------+   -> release [HIGH, human] -> commit -> pull-request
```

Autonomy boundaries: tools only through `ToolGateway`; an isolated git worktree per run; writes limited to the files
the approved plan gave each task; build descriptors, CI/CD and VCS internals are refused by policy; a human who is not
an agent identity must answer every ambiguity and approve plan and release; turns, retries and cost per run are
bounded; every model call is ledgered with a redacted transcript and the audit chain is persisted. With
`AGENTIC_PR=on` and a `GITHUB_TOKEN`, an approved run pushes its branch and opens a pull request whose description is
the release evidence; the system can open a PR but has no way to merge one, so final review and merge stay human.

```bash
./scripts/run-agentic.sh doctor                     # toolchain + one live model call (needs ANTHROPIC_API_KEY)
./scripts/run-agentic.sh run --scenario greenfield  # or brownfield | ambiguous | --requirement "TEXT"
```
**Live run evidence:** [`docs/live-runs/custo-20261022-232230.md`](docs/live-runs/custo-20261022-232230.md): real Claude
(Sonnet 5.5) took a new requirement through analysis, human clarification, plan approval, two implementation tasks and
real verification (72 tests, 0 failures, +15 tests), docs and an independent review that caught real defects; it stopped
before release because the API key was invalidated mid-run, so nothing was committed.
Setup, configuration, approvals and the honest status (no live run has reached release yet) are in
[`docs/AGENTIC_SETUP.md`](docs/AGENTIC_SETUP.md). Its 51 tests run without a key (scripted model turns, a fake GitHub API).

## Governed tool gateway: how an agent (LLM) calls the shortener API

An agent never calls an API itself. Its planner (where an LLM plugs in: `ToolPlanner`) only *proposes* tool calls;
the `ToolGateway` decides, and the engine still gates and commits the stage's outputs:

```
planner (LLM or script) --proposes ToolCall--> ToolGateway --HTTP + X-Request-Id--> shortener-service
                                                   |  1. run not revoked, call budget left
                                                   |  2. tool exists and is granted to this stage
                                                   |  3. arguments fit the tool's spec
                                                   |  4. policies (secret leak always on; BLOCK / CRITICAL)
                                                   |  5. MUTATING -> human approval (reject / timeout = deny)
                                                   |  6. execute; read-only retried on 5xx/unreachable, mutating never
                                                   |  7. output truncated, withheld if it looks like a credential,
                                                   v     framed as untrusted data for the model
                                       hash-chained AuditLog (tool, argument names, outcome; never values)
```

The shortener tools (`getLinkStats`, `getLinkAudit`, `getRecentAudit`, `createLink`) are derived from the OpenAPI
contract: name = `operationId`, description = summary, parameters from the operation, and risk from HTTP semantics
(GET is read-only, anything else mutating, so a new endpoint fails safe). Each binding to the generated client is
checked against the contract at start-up. Every call carries a correlation id (`run.stage.a<attempt>.c<n>`) that
the service logs as its request id, so the orchestrator's audit trail and the service's logs can be joined.

```bash
java -jar orchestrator-tools/target/orchestrator-tools-0.1.0-SNAPSHOT-exec.jar http://localhost:8080 [--interactive]
```
The demo agent creates a link (approved by a scripted release manager, or by you with `--interactive`), reads its
stats, makes one malformed call that the gateway refuses, reads the link's audit history and prints the audit trail.
The planner is a deterministic stand-in for an LLM; an LLM implementation of `ToolPlanner` sees the same tool specs
(`ToolSpec.inputSchemaJson()` is a JSON Schema) and the same untrusted-data framing of results.

## Run

**Validate everything with one command** (JDK 21+, Maven; Git Bash on Windows). Run it before merging any change,
including the pull requests the agents open (there is no hosted CI yet):
```bash
./scripts/validate-all.sh              # tests + coverage gate, agent pipeline, scenarios, then starts the service,
                                      # runs the HTTP smoke test and lets an agent operate it through the tool gateway
./scripts/validate-all.sh --cassandra  # same, but on Cassandra + Redis started with docker compose (needs Docker)
```
The service in that run listens on port 18080 (`PORT=...` to change), because 8080 is often taken.

**Run the service yourself:**
```bash
mvn install -DskipTests                                     # once: puts shortener-api where the service build finds it
mvn -pl shortener-service spring-boot:run                   # in-memory storage, no infrastructure, port 8080
mvn -pl shortener-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8081   # if 8080 is taken
java -jar shortener-service/target/shortener-service-0.1.0-SNAPSHOT.jar                    # or the packaged jar

docker compose up -d --wait                                 # Cassandra 5 + Redis 7 (first start ~1 min)
mvn -pl shortener-service spring-boot:run -Dspring-boot.run.profiles=cassandra   # links in Cassandra, redirects cached in Redis

./scripts/smoke-test.sh http://localhost:8080              # check a running service end to end
```
Short URLs are built from `shortener.base-url`, which follows the port unless you set `SHORTENER_BASE_URL`.

**Tests and agents:**
```bash
mvn verify                                          # all modules: tests, JaCoCo report and coverage gate per module
mvn -pl orchestrator verify                         # one module (the orchestrator needs nothing else)
mvn -pl shortener-service -am test -Dtest=ShortenerApiTest -Dsurefire.failIfNoSpecifiedTests=false   # one class
./scripts/run-delivery.sh                           # role-agent pipeline -> docs/delivery (run `mvn verify` first)
./scripts/run-delivery.sh --interactive             # you are the release manager on the terminal
./scripts/run-scenarios.sh                          # three governance scenarios (--out docs/SCENARIOS.md to save)
./scripts/run-core.sh                               # minimal 7-stage SDLC demo
./scripts/run-agentic.sh run --scenario greenfield  # LLM agents (needs ANTHROPIC_API_KEY, see docs/AGENTIC_SETUP.md)
```
Tests against real Cassandra and Redis (`CassandraStorageTest`, `RedisCacheTest`, `CassandraProfileApiTest`) use a
running server if `CASSANDRA_CONTACT_POINTS` / `REDIS_URL` are set, otherwise Docker (Testcontainers), otherwise
they are reported as skipped. Note: Cassandra 4+ does not run natively on Windows; use Docker, WSL2 or a Linux host.

## Shortener API
```bash
curl -i -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com/docs","ttlSeconds":3600}'
curl -i localhost:8080/<code>                   # 302 redirect, counts a click
curl    localhost:8080/api/v1/links/<code>/stats
curl    localhost:8080/api/v1/links/<code>/audit  # audit history of one link
curl    'localhost:8080/api/v1/audit?limit=50'    # most recent audit events, incl. rejected requests
curl    localhost:8080/api/v1/openapi.yaml        # the OpenAPI contract the service implements
```
Errors are RFC 7807 problem responses: 400 invalid URL/TTL or malformed body, 404 unknown code, 410 expired,
503 code space exhausted or Cassandra unavailable, 500 unexpected (carries an `errorRef` that matches the server log). Every response carries
an `X-Request-Id` header (a safe incoming id is reused), and every log line of the request includes it.

## Layout
```
shortener-api/src/main/resources/openapi/shortener-v1.yaml   the service contract (OpenAPI 3)
shortener-service/src/main/java/com/ascs/...                  AscsApplication + shortener/*
orchestrator/src/main/java/com/ascs/orchestrator/...          the orchestrator/* packages below

orchestrator/agents    role agents (requirements, design, development, code review, QA, release) + delivery pipeline
orchestrator/model     stage definitions, statuses, retry/approval/rollback types
orchestrator/core      WorkflowGraph (DAG), WorkflowEngine (governed executor), ArtifactStore (versions + lineage)
orchestrator/policy    SecretLeak, ProtectedPath, RequiredEvidence policies
orchestrator/approval  ApprovalGateway port (scripted + console implementations)
orchestrator/audit     hash-chained AuditLog          orchestrator/metrics   success rate, MTTR, latency
orchestrator/planner   requirement analyzer, task decomposition, DAG planner, codebase impact analyzer
orchestrator/scenario  greenfield / brownfield / ambiguous scenarios and report generator
orchestrator/tools     ToolGateway, ToolSpec/ToolCall/ToolResult, ToolPolicy, ToolPlanner (LLM seam), ToolUsingAgent
orchestrator-tools/.../tools/shortener   ShortenerContract (specs from OpenAPI), ShortenerTools (generated client), demo
agentic-sdlc/.../agentic   LLM agents (agents), workflow graphs + pipeline (pipeline), tool loop (loop), Claude provider,
                           call ledger, redaction (llm), human channels (human), worktree, write policy, path guard (workspace, tools)
shortener/{domain,app,infra,web}   hexagonal URL shortener with audit trail (only web uses Spring)
shortener/infra/{cache,redis,cassandra}   Redis cache-aside decorator, Lettuce and Cassandra driver adapters
shortener/web          Spring MVC: controller, contract endpoint, error handler, request-id filter, configuration
```

## Testing approach
The orchestrator and shortener core are framework-free, so they are tested without Spring in milliseconds. Tests
assert behaviour that matters for governance: exact agent invocation counts under retry, commit-after-approval
ordering, that CRITICAL violations are never retried, that secrets never reach the audit log, rollback ordering,
minimal re-plan, tamper detection, and fault paths such as coordinator interrupts, stale results and failing
gateways. Concurrency tests use real threads and latches. Tests declare the acceptance criteria they verify with
`@Verifies("AC-n.m")`; the QA agent turns those declarations into the functional-coverage matrix.

Coverage: 198 tests (128 orchestrator, 13 orchestrator-tools, 57 shortener-service of which 8 need Docker); outside the Docker-only driver adapters, 99.9% of lines and branches and 100% of methods. The
remaining gaps are explained in `docs/CODE_REVIEW.md`. `agentic-sdlc` adds 51 tests that replay scripted model turns
(no key, no network); it is not under the coverage gate.

## Limitations
The role agents, scenario agents and the tool-gateway planner are deterministic and rule-based. The LLM agents of
`agentic-sdlc` are tested with scripted model turns and have run live through analysis, implementation, verification,
docs and review, but no live run has reached release, commit and pull request yet (the recorded one stopped when its API
key was invalidated; see `docs/live-runs/`);
mutating tool calls are not idempotent, so they are never retried; the audit trail and orchestrator state are in memory;
control-plane and audit endpoints are unauthenticated; the Cassandra/Redis path is verified by Testcontainers tests that
need Docker. Details in `docs/PRODUCTION_READINESS.md`.
