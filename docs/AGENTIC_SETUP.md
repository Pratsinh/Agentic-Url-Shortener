# Agentic SDLC with Claude (module `agentic-sdlc`)

The `orchestrator` module is the governed engine; its built-in role agents are deterministic and rule-based. The
`agentic-sdlc` module puts **real LLM agents (Claude, via the official Anthropic Java SDK)** on that same engine:
a requirement in plain English goes in, and a reviewed, tested, human-approved commit on an isolated branch comes out.
The model proposes; the engine, deterministic checks and humans decide.

## Execution model

Two workflow graphs run on `WorkflowEngine` (`agentic-sdlc/.../pipeline/Graphs.java`). Discovery is fixed; delivery
is **compiled from the human-approved plan**, so the plan decides the shape (which tasks, which run in parallel) and
the code decides every governance control around it (gates, retries, fallbacks, approvals, rollback). No model ever
decides a gate, an approval or a rollback.

```
discovery:  analyze (LLM) -> clarify (human) -> plan (LLM) [human approval]
delivery:   baseline -> impl-T1 .. impl-Tn (LLM, parallel where the plan allows) -> verify (fallback: repair, LLM)
            -> { review (LLM, read-only; fallback: review-fix) || docs (LLM) } -> security (deterministic)
            -> release [HIGH impact, human approval] -> commit (on branch agentic/<runId>)
```

| Stage | Agent | Kind | Control |
|---|---|---|---|
| analyze | `RequirementAnalystAgent` | LLM | Normalized problem, acceptance criteria, ambiguities, codebase impact; output must pass a JSON schema |
| clarify | `ClarificationAgent` | human | Every ambiguity is answered or its assumption explicitly approved; nothing is assumed silently; rejection safe-stops |
| plan | `PlannerAgent` + `PlanValidator` | LLM + deterministic | Task DAG validated (acyclic, scope inside writable modules, every criterion owned); tasks touching the same file are serialized by the orchestrator; human approves, rejects or answers `revise` (re-plan) |
| baseline | `VerificationAgent` | deterministic | Re-checks the approved plan's version and SHA-256, runs the tests on the untouched base |
| impl-T* | `ImplementerAgent` | LLM | Writes only files in its task's write scope (`WriteScope`, derived from the approved plan) |
| verify | `VerificationAgent` | deterministic | Full real Maven build and tests; fails on any failure or on fewer tests than baseline; fallback `RepairAgent`, then run rollback |
| review | `ReviewerAgent` | LLM, separate conversation, read-only | Must assess every acceptance criterion with evidence; gate requires APPROVE; fallback `ReviewFixAgent` |
| docs | `DocsAgent` | LLM | Change documentation |
| security | `SecurityScanAgent` | deterministic | Scans the added lines of the final diff; CRITICAL/HIGH findings stop the run before release |
| release | `ReleaseReadinessAgent` | human go/no-go | HIGH impact, so the engine refuses to run it without an approval checkpoint |
| commit | `CommitAgent` | deterministic | Commits on the run branch; merging stays a human decision outside the system |

### Autonomy boundaries

* **Tools only through `ToolGateway`.** The `AgentLoop` gives the model the tools granted to its stage plus a strict
  "submit" tool; every call is checked for grants, arguments and policies, and audited.
* **Workspace isolation.** Each run works in its own git worktree on `agentic/<runId>` (`GitWorkspace`); the main
  working tree is never touched; only fixed git invocations exist (no model-supplied git arguments).
* **Least-privilege writes.** `PathGuard` (relative paths, no `..`, no `.git`, symlinks resolved inside the
  workspace) and `WorkspaceWritePolicy`: CI/CD, VCS internals, the system's own state and scripts are CRITICAL
  (stage safe-stops, never retried); build descriptors and paths outside the approved scope are BLOCK.
* **Separation of duties.** `HumanApprovalGateway` accepts decisions only from a named person who is not an agent
  identity, so no model can approve its own work. The file inbox lives outside the worktree, so no agent can write
  an answer. No answer before the timeout means rejection (fail closed).
* **Bounded autonomy.** Turns per agent (`AGENTIC_MAX_LLM_TURNS`), invalid submissions, stage retries and a per-run
  cost cap (`AGENTIC_MAX_RUN_USD`, from the call ledger) are all bounded.
* **Traceability.** Every model call is in the ledger (model, request id, tokens, cache use, latency, estimated cost)
  with a redacted transcript; the hash-chained audit log is persisted and can be re-verified (`verify-audit`).

## Setup

Needs JDK 21, Maven, git, and an Anthropic API key. The key is read by the Anthropic SDK from the environment; it is
never printed, and the `Redactor` removes it (and other credential shapes) from transcripts, ledger, audit and errors.

```bash
export ANTHROPIC_API_KEY=...            # Windows: setx ANTHROPIC_API_KEY ... then open a new terminal
export MAVEN_HOME=/path/to/maven        # used by the build runner (otherwise mvn from PATH)
git commit ...                          # the run's worktree is created from the committed HEAD

./scripts/run-agentic.sh doctor                          # toolchain + one live model call
./scripts/run-agentic.sh run --scenario greenfield       # link preview endpoint (well-defined, new feature)
./scripts/run-agentic.sh run --scenario brownfield       # custom short codes on the existing create API
./scripts/run-agentic.sh run --scenario ambiguous        # "links shouldn't live forever ..." (needs clarification)
./scripts/run-agentic.sh run --requirement "TEXT"        # any requirement
./scripts/run-agentic.sh list                            # recorded runs and their status
./scripts/run-agentic.sh verify-audit RUN_ID             # re-verify a run's persisted hash chain
```

Approvals come on the terminal (`--approval console`) or as files (`--approval file`): the run writes
`.agentic/runs/<runId>/inbox/<id>.request.md` and waits for `<id>.response.txt`:

```
decision: approve | reject | revise | answer
by: <your name>
<comment, feedback or answers>
```

Configuration (environment): `AGENTIC_MODEL` (the script defaults it to `claude-sonnet-5-5`; the CLI's own default is
`claude-opus-5-5`), `AGENTIC_REVIEW_MODEL`, `AGENTIC_EFFORT` (default `high`), `AGENTIC_MAX_TOKENS` (16000),
`AGENTIC_MAX_RUN_USD` (40), `AGENTIC_MAX_LLM_TURNS` (60), `AGENTIC_MAX_PARALLEL` (2), `AGENTIC_STAGE_TIMEOUT_MINUTES`
(60), `AGENTIC_APPROVAL_TIMEOUT_MINUTES` (120), `AGENTIC_BUILD_TIMEOUT_MINUTES` (20), `AGENTIC_REFUSAL_FALLBACK=off`.

Each run is recorded under `.agentic/runs/<runId>/`: `summary.md` (stages, human decisions, governance events,
reliability metrics, model calls), `audit.jsonl`, `metrics.json`, `llm/calls.jsonl`, `llm/transcripts/`, `artifacts/`.

## Testing

The module's 36 tests run without a key or network: `ScriptedProvider` replays model turns, so the pipeline,
gates, approvals, rollback, write policies, path guard, schema validation, redaction and the error path of the
provider are tested deterministically (`mvn -pl agentic-sdlc -am test`). Only `doctor` and `run` call the real API.

## Status and limitations (honest)

* **No live end-to-end run has completed yet.** The two recorded live attempts stopped in the first stage with an
  Anthropic API `400 Bad Request`; the engine behaved as designed (bounded retry, downstream stages blocked, worktree
  discarded, audit chain intact), but the API's error detail was not recorded then. The provider now keeps that
  detail (redacted, truncated), so the next `doctor` or `run` will show the cause.
* A non-retryable API error (400) still uses the stage's second attempt, which costs one extra failed call.
* Run state is persisted for evidence but a crashed run cannot be resumed.
* The writable scope is fixed to `shortener-service` and `shortener-api`; dependency changes need a human.
* Costs are estimates from list prices, not billing data.
