#!/usr/bin/env bash
set -euo pipefail

# Namaz Birim Testleri Çalıştırıcı (Cihazsız / Headless)
# Açılışta sürüm kontrolü ve güncelleme zorlaması mantığını test eder.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

OUT_DIR="$DIR/build/test-bin"
mkdir -p "$OUT_DIR"

SDK_PLATFORM="/mnt/HC_Volume_107358856/ajan/tasinan/android-sdk-hazirlik/sdk/platforms/android-35/android.jar"
KOTLIN_LIB="/mnt/HC_Volume_107358856/ajan/tasinan/android-sdk-hazirlik/gradle/gradle-8.7/lib"
JSON_JAR="/home/ajan/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar"
JUNIT_JAR="$KOTLIN_LIB/junit-4.13.2.jar"
HAMCREST_JAR="$KOTLIN_LIB/hamcrest-core-1.3.jar"
STDLIB_JAR="$KOTLIN_LIB/kotlin-stdlib-1.9.22.jar"

echo "==> Derleniyor: Namaz kaynak kodları ve birim testleri..."
java -cp "$KOTLIN_LIB/*" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib \
  -cp "$SDK_PLATFORM:$STDLIB_JAR:$JSON_JAR:$JUNIT_JAR" \
  -d "$OUT_DIR" \
  "$DIR/app/src/main/java/tr/veli/namaz/" \
  "$DIR/app/src/test/java/tr/veli/namaz/R.kt" \
  "$DIR/app/src/test/java/tr/veli/namaz/UpdaterUnitTest.kt"

echo "==> Çalıştırılıyor: JUnit 4 birim testleri..."
java -cp "$OUT_DIR:$JSON_JAR:$SDK_PLATFORM:$STDLIB_JAR:$JUNIT_JAR:$HAMCREST_JAR" \
  org.junit.runner.JUnitCore tr.veli.namaz.UpdaterUnitTest
