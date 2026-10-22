# Code review record

Two layers of review cover every line of code in the repository:

1. **Automated review by the code-review agent** (`CodeReviewAgent`). It runs as a stage of the delivery pipeline over
   *every* Java file in `src/main/java` and `src/test/java` of every module, and lists each file as reviewed. Its latest report is
   [`docs/delivery/code-review.md`](delivery/code-review.md). It reports per-file results, the rules applied, every
   finding and how each was resolved. A release needs no open HIGH or MEDIUM finding.
2. **Manual review**, recorded below: a line-by-line review of the whole codebase against the delivery requirements
   (requirements, design, error handling, logging, auditing, tests). Every issue found is listed with its resolution.

## How findings are resolved

- **Fix the code** (the default). The agent re-runs on every pipeline execution, so a fixed finding disappears from the report.
- **Documented waiver.** Used only when the flagged code is intentional. The waiver is a comment on the same or the
  preceding line: `// review:accepted <rule> <reason>`. The agent reports the finding as ACCEPTED, with the reason, so
  every waiver remains visible. A waiver for a different rule does not count.

Current waivers (5), all deliberate:

| Rule | Location | Reason |
|---|---|---|
| CR-01 | `orchestrator/scenario/ScenarioAgents.java` | AWS's documented example key id (`AKIA...EXAMPLE`, not a credential), injected to prove the secret policy blocks it |
| CR-01 | `PolicyGuardrailTest`, `ReplanTest`, `ScenarioTest` | The same example key: the input the policy must reject, or the value the audit log must never contain |
| CR-04 | `orchestrator/approval/ConsoleApprovalGateway.java` | The interactive approver's user interface is the terminal |

## Manual review findings

Severity: HIGH = wrong or unsafe behaviour, or a missing required capability. MEDIUM = a robustness or security
weakness. LOW = maintainability or hygiene.

| # | Severity | Area | Finding | Resolution |
|---|---|---|---|---|
| R-01 | HIGH | Build | The Spring web layer and `ShortenerApiTest` had never been compiled or run. | Built and run with Maven: the layer compiles and all API tests pass. `mvn verify` is now the quality gate. |
| R-02 | HIGH | Logging | The shortener had no logging at all. | Core: `System.Logger` (no framework dependency), routed to SLF4J under Spring Boot. Web: `RequestIdFilter` sets an `X-Request-Id` correlation id (MDC and response header) and writes one access-log line per request. |
| R-03 | HIGH | Auditing | No business audit trail for link creation or redirects (only the orchestrator was audited). | `AuditTrail` port with a bounded `InMemoryAuditTrail`. Every create and redirect, successful or rejected, is recorded. New endpoints: `GET /api/v1/links/{code}/audit` and `GET /api/v1/audit?limit=`. |
| R-04 | MEDIUM | Error handling | Spring MVC errors (malformed JSON, unknown route, wrong method) bypassed the RFC 7807 contract. An unexpected exception fell through to the default error page. | `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`, so every MVC error is a problem response. A catch-all returns a 500 problem with an `errorRef` that is logged with the stack trace. No internals reach the client. |
| R-05 | MEDIUM | Security | Log forging: caller-supplied codes, including newlines, were echoed into exception messages that are logged and audited. | `ShortenerException` neutralises and truncates echoed codes. A malformed code is never stored as a link code in the audit trail. |
| R-06 | MEDIUM | Security | A logged or audited full URL could leak tokens in query strings. | Logs and audit records carry the host only. |
| R-07 | MEDIUM | Testability | `ConsoleApprovalGateway` was hard-wired to `System.in`/`System.out` (0% covered). | Streams are injected; the default constructor keeps the terminal behaviour. |
| R-08 | HIGH | Agents (new code) | `CommandRunner` read the process pipe before waiting, so a hung `git` would block forever and the timeout could not fire. Waiting first could deadlock on a full pipe. | Output is redirected to a temp file, then the runner waits with a timeout and kills the process on expiry. Regression-tested with a command that never ends. |
| R-09 | MEDIUM | Agents (new code) | The design agent flagged its own string literal as Spring usage, and review rule CR-04 missed `System.out` passed as an argument. | Rule patterns are assembled so they cannot match themselves, and CR-04 matches `System.out` as a word. |
| R-10 | LOW | Concurrency | `lastAccessedAt` could move backwards when concurrent clicks finish out of order. | Monotonic update with `AtomicReference.accumulateAndGet`. |
| R-11 | LOW | Robustness | `Base62CodeGenerator` accepted a null `Random`. | `requireNonNull`. |
| R-12 | LOW | Dead code | Unreachable branches: `inbox.take()` in the engine loop (a deadline always exists when work is in flight), a non-exhaustive `instanceof` chain for events, `host.isBlank()` (URI never returns a blank host), a null check in the impact analyzer, and synthetic enum-switch defaults. | Removed or restructured: sealed `Event` with a pattern switch, a single timed poll, an if/else failure strategy, and an `EnumMap` for run outcomes. |
| R-13 | LOW | Docs | 11 public types had no Javadoc (agent rule CR-07). | Documented. |
| R-14 | LOW | Repo | On Windows checkouts, `.sh` scripts would receive CRLF line endings and fail under bash. | `.gitattributes` forces LF for `*.sh` and `*.java`. |
| R-15 | INFO | VCS | The development agent flagged commit `c77eca1`: its subject is 74 characters (limit 72). | Left as recorded (rewriting history only to shorten a subject is not worth it). Later commits comply. |
| R-16 | INFO | Test environment | Mockito's Byte Buddy (via Spring Boot 3.3) does not support JDK 25, the only JDK available during review. | Web tests use a hand-written stub service instead of a mocking library, so they run on any JDK. |

### Second round: Cassandra storage, Redis cache, validation tooling

| # | Severity | Area | Finding | Resolution |
|---|---|---|---|---|
| R-17 | HIGH | Resilience | As first written, a Cassandra outage surfaced as a raw driver exception (500), and even cached redirects failed because recording the click threw. That contradicts the design ("hot links still redirect"). | Driver errors become `StorageUnavailable` (503). A click that cannot be stored is logged and dropped, and the redirect still succeeds. Tested in `StorageOutageTest` and `ApiErrorHandlingTest`. |
| R-18 | MEDIUM | Correctness | Cassandra keeps milliseconds, so the create response (nanosecond `createdAt`) disagreed with later reads. | The service truncates creation times to milliseconds for every store. |
| R-19 | MEDIUM | Config | `shortener.base-url` was hard-coded to port 8080, so short URLs were wrong whenever the service ran on another port. | It derives from `server.port` unless `SHORTENER_BASE_URL` is set. |
| R-20 | MEDIUM | QA accuracy | The QA agent counted an acceptance criterion as verified because a test *declared* it, even when that test was skipped (for example, Docker-only tests without Docker). | Criteria count only when a declaring test ran and passed (surefire results). Skipped, failed or missing tests are marked in the matrix. |
| R-21 | MEDIUM | Tooling | The first smoke run hit a different server on port 8080 (Jenkins) and two checks passed vacuously on empty values. | Validation uses port 18080, readiness requires the service's own `X-Request-Id` header, and the vacuous checks now require a real value. |
| R-22 | LOW | Security | The keyspace name is concatenated into CQL. | Restricted to a plain identifier (`CassandraSchema.checkKeyspace`) and tested. |
| R-23 | LOW | Build | Testcontainers 1.19.8 (Spring Boot 3.3 default) uses a Docker API that current engines reject. | Pinned to 1.21.4. |

## Coverage not achieved (by design)

**Code that needs Docker.** The Cassandra and Redis driver adapters (`infra/cassandra`, `infra/redis`) and
`web/ExternalStorageConfig` can only be exercised against real servers. `CassandraStorageTest`, `RedisCacheTest` and
`CassandraProfileApiTest` cover them with Testcontainers when Docker is available. Without Docker those tests are
skipped, the classes appear as gaps in the QA report, and the coverage gate excludes them so that a machine without
Docker can still build. Run `./scripts/validate-all.sh --cassandra` on a machine with Docker to verify that path.

**Unreachable code.** Everywhere else, 2 lines and 1 branch are not covered. All three are defensive code that
cannot be reached:

| Location | Why it cannot be covered |
|---|---|
| `orchestrator/util/Hashing.java` catch block (2 lines) | `NoSuchAlgorithmException` for SHA-256 cannot occur: every Java platform must provide SHA-256. |
| `WorkflowEngine.cancel` `future != null` (1 branch) | A RUNNING stage always has a submitted future; if submission is rejected, the stage fails instead of running. The check stays as a guard against future changes. |

The QA agent lists these gaps, with line numbers, in [`docs/delivery/qa-report.md`](delivery/qa-report.md).
