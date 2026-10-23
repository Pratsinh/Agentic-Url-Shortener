#!/usr/bin/env bash
# Run the LLM-driven agentic SDLC (module agentic-sdlc): requirement -> analysis -> human clarification -> plan
# [human approval] -> parallel implementation -> build/test verification -> review || docs -> security scan ->
# release [human approval] -> commit on an isolated branch -> (opt-in, AGENTIC_PR=on + GITHUB_TOKEN) push and open a pull
# request for human review and merge. Every model call goes through the governed orchestrator.
# Needs JDK 21, Maven, git and ANTHROPIC_API_KEY (read by the Anthropic SDK; never printed or written to the run record).
# Usage: ./scripts/run-agentic.sh doctor                                  check toolchain + one live model call
#        ./scripts/run-agentic.sh run --scenario greenfield|brownfield|ambiguous [--approval console|file]
#        ./scripts/run-agentic.sh run --requirement "TEXT"
#        ./scripts/run-agentic.sh list | verify-audit RUN_ID
# The agents work on a git worktree of the committed HEAD, so commit local changes first. Run records: .agentic/runs/.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${MVN:-mvn}"
export AGENTIC_MODEL="${AGENTIC_MODEL:-claude-sonnet-5-5}"
JAR=$(ls agentic-sdlc/target/agentic-sdlc-*-exec.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
  "$MVN" -B -q -pl agentic-sdlc -am package -DskipTests
  JAR=$(ls agentic-sdlc/target/agentic-sdlc-*-exec.jar | head -1)
fi
exec java -jar "$JAR" "$@"
