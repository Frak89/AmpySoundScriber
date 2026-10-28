#!/usr/bin/env bash
# Compila ed esegue i test dei moduli Kotlin/JVM puri SENZA Android SDK.
# Usa il compilatore Kotlin indicato da KOTLIN_LIB (cartella con kotlin-compiler-embeddable*.jar
# e relative dipendenze, es. la cartella lib/ di una distribuzione Gradle 8.x o di kotlinc).
#
#   KOTLIN_LIB=/opt/gradle/lib tools/verify_core.sh [modulo...]
#
# Con un Android SDK installato è preferibile:  ./gradlew test
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLIN_LIB="${KOTLIN_LIB:-/opt/gradle/lib}"
OUT=build-verify
mkdir -p "$OUT"

jar() { ls "$KOTLIN_LIB"/$1 2>/dev/null | head -1; }
STDLIB=$(jar 'kotlin-stdlib-[0-9]*.jar')
COMPILER_CP="$(jar 'kotlin-compiler-embeddable-*.jar'):$STDLIB:$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'annotations-[0-9]*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
JUNIT="$(jar "junit-4*.jar"):$(jar "hamcrest-core-*.jar")"

declare -A DEPS=(
  [ampy-core]=""
  [ampy-diagnostics]="ampy-core"
  [ampy-audio-engine]="ampy-core"
  [ampy-rhythm-lab]="ampy-core ampy-audio-engine"
  [ampy-music-brain]="ampy-core ampy-audio-engine ampy-rhythm-lab"
  [ampy-midi-lab]="ampy-core ampy-audio-engine"
  [ampy-score-engine]="ampy-core ampy-rhythm-lab ampy-music-brain"
  [ampy-transcriber]="ampy-core ampy-audio-engine ampy-rhythm-lab"
  [ampy-separator]="ampy-core ampy-audio-engine"
  [ampy-instrument-ai]="ampy-core ampy-audio-engine"
  [ampy-composer-ai]="ampy-core ampy-audio-engine ampy-rhythm-lab ampy-music-brain ampy-midi-lab"
  [ampy-model-manager]="ampy-core"
  [ampy-learning-lab]="ampy-core ampy-audio-engine ampy-rhythm-lab ampy-transcriber ampy-midi-lab"
)
ORDER="ampy-core ampy-diagnostics ampy-audio-engine ampy-rhythm-lab ampy-music-brain ampy-midi-lab ampy-score-engine ampy-transcriber ampy-separator ampy-instrument-ai ampy-composer-ai ampy-model-manager ampy-learning-lab"
SELECTED="${*:-$ORDER}"

kotlinc() { java -Xmx3g -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }

FAILED=0
for m in $ORDER; do
  [[ " $SELECTED " == *" $m "* ]] || continue
  cp="$STDLIB"
  for d in ${DEPS[$m]}; do cp="$cp:$OUT/$d/main"; done
  echo "=== $m: compilazione"
  rm -rf "$OUT/$m"; mkdir -p "$OUT/$m/main" "$OUT/$m/test"
  kotlinc -cp "$cp" -d "$OUT/$m/main" $(find "modules/$m/src/main/kotlin" -name '*.kt') 2>&1 | grep -v -e "^warning" -e "Picked up JAVA_TOOL_OPTIONS" || true
  [[ -n "$(find "$OUT/$m/main" -name '*.class' | head -1)" ]] || { echo "!!! compilazione fallita: $m"; FAILED=1; continue; }
  tests=$(find "modules/$m/src/test/kotlin" -name '*.kt' 2>/dev/null || true)
  if [[ -n "$tests" ]]; then
    kotlinc -cp "$cp:$OUT/$m/main:$JUNIT" -d "$OUT/$m/test" $tests 2>&1 | grep -v -e "^warning" -e "Picked up JAVA_TOOL_OPTIONS" || true
    classes=$(cd "$OUT/$m/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | tr '\n' ' ')
    [[ -n "$classes" ]] || { echo "!!! compilazione test fallita: $m"; FAILED=1; continue; }
    echo "=== $m: test"
    if ! java -cp "$cp:$OUT/$m/main:$OUT/$m/test:$JUNIT" -Dampy.testdata="$PWD/testdata" org.junit.runner.JUnitCore $classes > "$OUT/$m/test.log" 2>&1; then
      FAILED=1; echo "!!! test falliti: $m"; tail -40 "$OUT/$m/test.log"
    else
      grep -E "^OK" "$OUT/$m/test.log"
    fi
  fi
done
exit $FAILED
