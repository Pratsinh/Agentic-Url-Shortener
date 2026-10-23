#!/usr/bin/env bash
# Compile the orchestrator module (no dependencies) and run the three scenarios (JDK 21 only; no Maven, no Spring).
# Usage: ./scripts/run-scenarios.sh [--out docs/SCENARIOS.md] [--interactive]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=orchestrator/target/core-classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" $(find orchestrator/src/main/java -name '*.java')
java -cp "$OUT" com.ascs.orchestrator.scenario.ScenarioRunner . "$@"
