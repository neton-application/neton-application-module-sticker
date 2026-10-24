#!/bin/bash
set -euo pipefail
# The installed Kotlin/Native launcher reads JAVA_OPTS, not KONAN_JVM_ARGS.
export JAVA_OPTS="${JAVA_OPTS:-} -XX:ReservedCodeCacheSize=256m"
module=$(cd "$(dirname "$0")/../.." && pwd)
classpath=${1:?Pass the resolved module-sticker macosArm64 test-classpath file}
out=${2:-$(mktemp -d /tmp/sticker-packs.native.XXXXXX)}
mkdir -p "$out"
native="$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20/bin/konanc"
args=()
while IFS= read -r library; do
  case "$library" in ''|*/klib/platform/*|*/klib/common/*|*.jar) continue;; esac
  [[ -e "$library" ]] || { echo "Missing compiled dependency: $library" >&2; exit 1; }
  args+=(-library "$library")
done < <(cat "$classpath"; echo)
"$native" -target macos_arm64 -generate-test-runner -Xpartial-linkage-loglevel=error "${args[@]}" \
  -friend-modules "$module/build/classes/kotlin/macosArm64/main/klib/module-sticker" \
  -linker-option -L/Users/zoujiaqing/projects/Neton/neton/neton-core/build/nativeInterop/macosArm64 -linker-option -lenv \
  "$module/src/commonTest/kotlin/StickerRulesTest.kt" \
  "$module/src/commonTest/kotlin/sticker/logic/StickerArchiveTest.kt" \
  "$module/src/commonTest/kotlin/sticker/logic/PagImportTest.kt" \
  "$module/src/commonTest/kotlin/sticker/logic/StickerDefaultContractTest.kt" \
  "$module/src/nativeTest/kotlin/sticker/logic/ExternalPagInspectorTest.kt" \
  "$module/src/macosArm64Test/kotlin/StickerDefaultInstallPostgresTest.kt" \
  "$module/src/macosArm64Test/kotlin/StickerPackHttpHostTest.kt" \
  -o "$out/default-pack-tests" > "$out/compile.log" 2>&1
printf '%s\n' "$out/default-pack-tests.kexe"
