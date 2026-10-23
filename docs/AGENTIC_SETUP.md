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
            -> pull-request (push + open PR; a human reviews and merges on GitHub)
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
| docs | `DocsAgent` | LLM | Writes `docs/changes/<runId>.md`: **user stories** ("As a … I want … so that … (AC-n)") tracing every acceptance criterion, API examples, behaviour, compatibility, testing. Updates `docs/TECHNICAL_DESIGN.md` (its only other writable file), which is **mandatory when the API contract changed**. Both rules are checked deterministically. |
| security | `SecurityScanAgent` | deterministic | Scans the added lines of the final diff; CRITICAL/HIGH findings stop the run before release |
| release | `ReleaseReadinessAgent` | human go/no-go | HIGH impact, so the engine refuses to run it without an approval checkpoint |
| commit | `CommitAgent` | deterministic | Commits on the run branch, with the approvers and the plan hash in the message |
| pull-request | `PullRequestAgent` | deterministic | Opt-in. Re-checks the release approval, pushes only the run branch, opens a PR whose description is the release evidence. Retried once; a failure never undoes the commit (`DELIVERED_PR_FAILED`). It has no merge operation: merging is a human decision on GitHub |

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

**Pull requests (optional).** To have each approved run open a PR for review instead of only committing locally:

```bash
export AGENTIC_PR=on
export GITHUB_TOKEN=...                 # fine-grained token: this repository only, Contents + Pull requests: read and write
export AGENTIC_PR_BASE=main             # optional: target branch (default main)
export AGENTIC_PR_REMOTE=origin         # optional: remote to push to (default origin)
export AGENTIC_PR_REPO=owner/name       # optional: otherwise parsed from the remote's GitHub URL
./scripts/run-agentic.sh doctor         # shows "pull requests: on, remote origin -> base main, token (set)"
```
The token is held only by the pull-request component: it is not in the configuration or run record, is scrubbed from
every child process except the one `git push` (which reads it through an inline credential helper, never from the
command line), and is redacted from errors, the audit log and the PR description. `AGENTIC_GITHUB_API` points at
GitHub Enterprise if needed.

Configuration (environment): `AGENTIC_MODEL` (the script defaults it to `claude-sonnet-5-5`; the CLI's own default is
`claude-opus-5-5`), `AGENTIC_REVIEW_MODEL`, `AGENTIC_EFFORT` (default `high`), `AGENTIC_MAX_TOKENS` (16000),
`AGENTIC_MAX_RUN_USD` (40), `AGENTIC_MAX_LLM_TURNS` (60), `AGENTIC_MAX_PARALLEL` (2), `AGENTIC_STAGE_TIMEOUT_MINUTES`
(60), `AGENTIC_APPROVAL_TIMEOUT_MINUTES` (120), `AGENTIC_BUILD_TIMEOUT_MINUTES` (20), `AGENTIC_REFUSAL_FALLBACK=off`.

Each run is recorded under `.agentic/runs/<runId>/`: `summary.md` (stages, human decisions, governance events,
reliability metrics, model calls), `audit.jsonl`, `metrics.json`, `llm/calls.jsonl`, `llm/transcripts/`, `artifacts/`.

## Walkthrough: run the full SDLC on your own requirement

1. **Prepare.** Commit or stash local changes (the run's worktree is created from the committed HEAD), set
   `ANTHROPIC_API_KEY`, then check toolchain and credentials:
   ```bash
   ./scripts/run-agentic.sh doctor          # must end with "doctor: all checks passed"
   ```
2. **Start the run** with a requirement in plain English (or `--requirement-file PATH` for a longer one):
   ```bash
   ./scripts/run-agentic.sh run --requirement "Add DELETE /api/v1/links/{code} so the owner can remove a link; \
   a deleted code returns 404" --approval console --approver "Your Name"
   ```
   It prints the run id and its record folder `.agentic/runs/<runId>/`.
3. **Answer the clarification** (`clarify` stage). The analyst lists ambiguities as `AMB-1`, `AMB-2`, ... with a
   proposed assumption each. Type `answer`, then one line per question, then a line with only `.`:
   ```
   > decision: answer
   AMB-1: accept
   AMB-2: deleting an expired link also returns 204
   .
   ```
   `reject` stops the run here. Every ambiguity must be addressed; nothing is assumed silently.
4. **Approve the plan** (`plan` stage). You see the design, the task graph (which tasks run in parallel), the files
   each task may change and which task owns each acceptance criterion. Answer `approve`, `reject`, or `revise` with
   feedback: the planner then re-plans with it and asks again.
5. **Let the delivery run.** No input needed: baseline tests, parallel implementation, the full Maven build and test
   suite (a failure goes to the repair agent once, otherwise the run rolls back), independent review and docs, then
   the security scan. Progress is visible in `.agentic/runs/<runId>/audit.jsonl`.
6. **Release go/no-go** (`release` stage, HIGH impact). You see the diff summary, test results against the baseline,
   the review verdict per acceptance criterion and the security findings. `approve` commits on `agentic/<runId>`
   and, with pull requests enabled, pushes the branch and opens a PR (the run prints its URL); `reject` discards the
   worktree and the branch, and nothing is pushed.
7. **Inspect the evidence.**
   ```bash
   cat .agentic/runs/<runId>/summary.md                 # stages, human decisions, governance events, metrics, model calls
   ls  .agentic/runs/<runId>/artifacts/                 # requirement-analysis.md, plan.md, review, docs, security, release notes
   ./scripts/run-agentic.sh verify-audit <runId>        # re-verify the persisted hash chain
   git log -1 --stat agentic/<runId>                    # the approved commit
   git diff HEAD...agentic/<runId>                      # the full change
   ```
8. **Review and merge (your decision, outside the system).** With pull requests enabled, review the PR on GitHub:
   its description is the release evidence plus the approvals and run id; the CI workflow (`.github/workflows/ci.yml`: `validate-all.sh`, i.e. all tests, the coverage gate, scenarios and the live service checks) runs on it automatically;
   merge or close it there. Without them, review the branch locally, run `./scripts/validate-all.sh` on it, then
   push it and open a PR yourself (`git push origin agentic/<runId>`) or delete it (`git branch -D agentic/<runId>`).

Final status printed by the run: `DELIVERED` (commit on the branch, and the PR when enabled), `DELIVERED_PR_FAILED`
(approved commit kept on the local branch; push or PR creation failed, see `summary.md`), `REJECTED_AT_RELEASE`, `ROLLED_BACK`
(verification failed even after repair; branch discarded), `STOPPED_IN_DISCOVERY_<outcome>` (e.g. clarification or
plan rejected, or a model call failed; nothing implemented) or `STOPPED_IN_DELIVERY_<outcome>` (e.g. a security
finding safe-stopped the run). Each outcome is a governed result, not a crash; `summary.md` says why.

For an unattended setup use `--approval file`: each question appears as
`.agentic/runs/<runId>/inbox/<id>.request.md`; answer by writing `<id>.response.txt` in the format above.
To try the built-in requirements instead, use `--scenario greenfield | brownfield | ambiguous`.

## Testing

The module's 46 tests run without a key or network: `ScriptedProvider` replays model turns and `FakeGitHub` stands in
for the GitHub API (pushes go to a local bare repository), so the pipeline, the pull-request hand-off,
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
