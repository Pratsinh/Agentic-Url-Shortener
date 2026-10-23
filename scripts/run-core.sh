#!/usr/bin/env bash
# Compile and run the orchestrator (a module without dependencies) with only a JDK 21: no Maven, no Spring.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=orchestrator/target/core-classes
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" $(find orchestrator/src/main/java -name '*.java')
java -cp "$OUT" com.ascs.orchestrator.sdlc.OrchestratorDemo "$@"
