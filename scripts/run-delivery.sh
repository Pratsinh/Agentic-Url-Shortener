#!/usr/bin/env bash
# Run the agentic delivery pipeline (requirements -> design -> development -> code review + QA -> release gate)
# against this repository and write the evidence to docs/delivery. JDK 21 only; run `mvn verify` first so the QA
# agent can read each module's target/site/jacoco/jacoco.xml (without them, unit coverage is reported as not measured).
# Usage: ./scripts/run-delivery.sh [--out docs/delivery] [--target 100] [--interactive]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=orchestrator/target/core-classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" $(find orchestrator/src/main/java -name '*.java')
ARGS=("$@")
[[ " ${ARGS[*]-} " == *" --out "* ]] || ARGS+=(--out docs/delivery)
java -cp "$OUT" com.ascs.orchestrator.agents.DeliveryRunner . "${ARGS[@]}"
