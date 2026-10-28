#!/usr/bin/env bash
# Compila il codice dell'app privo di dipendenze Android (data/ProjectRepository, pipeline/AmpyPipeline)
# contro i moduli core ed esegue il collaudo end-to-end tools/jvm/PipelineE2E.kt.
# Richiede: tools/verify_core.sh eseguito prima; Python con onnxruntime (PYTHON=/percorso/python).
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLIN_LIB="${KOTLIN_LIB:-/opt/gradle/lib}"
PYTHON="${PYTHON:-python3}"
jar() { ls "$KOTLIN_LIB"/$1 2>/dev/null | head -1; }
STDLIB=$(jar 'kotlin-stdlib-[0-9]*.jar')
COMPILER_CP="$(jar 'kotlin-compiler-embeddable-*.jar'):$STDLIB:$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'annotations-[0-9]*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
CP="$STDLIB"; for d in build-verify/*/main; do CP="$CP:$d"; done
OUT=build-verify/app-pipeline; rm -rf "$OUT"; mkdir -p "$OUT"
java -Xmx3g -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp "$CP" -d "$OUT" \
  app/src/main/kotlin/org/ampy/app/data/ProjectRepository.kt app/src/main/kotlin/org/ampy/app/pipeline/AmpyPipeline.kt tools/jvm/PipelineE2E.kt 2>&1 | grep -v -e "^warning" -e "Picked up" || true
java -Xmx2g -cp "$CP:$OUT" PipelineE2EKt "$PYTHON" tools/jvm/ort_server.py "$PWD" 2>&1 | grep -v "Picked up"
