#!/usr/bin/env bash
# Validates everything in one go:
#   1. build + all tests + coverage gate (mvn verify; the Redis/Cassandra tests run only if Docker is available)
#   2. the role-agent delivery pipeline (writes docs/delivery)
#   3. the three governance scenarios
#   4. starts the service and runs the HTTP smoke test against it
#   5. an agent operates that service through the governed tool gateway (generated OpenAPI client)
# Usage: [PORT=18080] ./scripts/validate-all.sh [--cassandra]
#   --cassandra   run the service with the "cassandra" profile. Uses the running Cassandra in CASSANDRA_CONTACT_POINTS
#                 (and REDIS_URL if set; otherwise no cache), or else starts both with docker compose. The storage
#                 tests in step 1 use the same variables.
# Needs JDK 21+ and Maven (set MVN=/path/to/mvn if mvn is not on PATH).
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${MVN:-mvn}"
PORT="${PORT:-18080}"   # not 8080: that port is often taken by other local servers
BASE="http://localhost:$PORT"
PROFILE=""
[ "${1:-}" = "--cassandra" ] && PROFILE="cassandra"

step() { echo; echo "=== $* ==="; }
mkdir -p target   # this script's logs and run records

step "1/5 build, tests, coverage (mvn verify)"
LOG="$(mktemp)"
"$MVN" -B verify > "$LOG" 2>&1 || { tail -60 "$LOG"; echo "mvn verify FAILED (full log: $LOG)"; exit 1; }
grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|coverage checks" "$LOG"
echo "coverage reports: <module>/target/site/jacoco/index.html for orchestrator, orchestrator-tools, shortener-service (Docker-only tests are skipped when Docker is unavailable)"

step "2/5 agent delivery pipeline"
./scripts/run-delivery.sh > target/delivery-run.md
grep -E '^\*\*Outcome|^\| [a-z-]+ \| [A-Z_]+ \|' target/delivery-run.md
grep -h -E '^\- \*\*(Files reviewed|Verdict|Recommendation)' docs/delivery/code-review.md docs/delivery/release-readiness.md

step "3/5 governance scenarios"
./scripts/run-scenarios.sh > target/scenarios.md
grep -E '^\*\*Run `' target/scenarios.md

step "4/5 service + HTTP smoke test${PROFILE:+ (profile: $PROFILE)}"
EXTRA=()
if [ -n "$PROFILE" ]; then
  if [ -n "${CASSANDRA_CONTACT_POINTS:-}" ]; then
    # an already running Cassandra (e.g. ./scripts/start-local-cassandra.sh); Redis only if REDIS_URL is set
    echo "using Cassandra at $CASSANDRA_CONTACT_POINTS; Redis cache: ${REDIS_URL:-none}"
    [ -z "${REDIS_URL:-}" ] && EXTRA+=(--shortener.cache.redis-url=)
  else
    docker compose up -d --wait
  fi
fi
JAR=$(ls shortener-service/target/shortener-service-*.jar | grep -v original | head -1)
java -jar "$JAR" --server.port="$PORT" ${PROFILE:+--spring.profiles.active=$PROFILE} "${EXTRA[@]}" > target/service.log 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 90); do
  # ready only when OUR service answers (it always sets X-Request-Id), not whatever else listens on the port
  curl -s -D - -o /dev/null "$BASE/api/v1/audit" | grep -qi "^x-request-id:" && break
  kill -0 $PID 2>/dev/null || { echo "service failed to start:"; tail -30 target/service.log; exit 1; }
  sleep 1
done
kill -0 $PID 2>/dev/null || { echo "service exited:"; tail -30 target/service.log; exit 1; }
./scripts/smoke-test.sh "$BASE"

step "5/5 governed tool gateway against the running service"
TOOLS_JAR=$(ls orchestrator-tools/target/orchestrator-tools-*-exec.jar | head -1)
java -jar "$TOOLS_JAR" "$BASE" > target/tool-gateway-demo.md || { cat target/tool-gateway-demo.md; exit 1; }
grep -E '^#[0-9]+ (TOOL_CALL|APPROVAL)' target/tool-gateway-demo.md
# the gateway's correlation id must arrive at the service as X-Request-Id and appear on its own log lines
if grep -q 'tools-demo.link-ops.a1.c1' target/service.log; then
  echo "correlation id tools-demo.link-ops.a1.c1 found in the service log"
else
  echo "correlation id missing from target/service.log"; exit 1
fi
echo; echo "ALL VALIDATIONS PASSED (service log: target/service.log)"
