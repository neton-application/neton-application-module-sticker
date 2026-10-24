#!/bin/bash
set -euo pipefail
export JAVA_OPTS="${JAVA_OPTS:-} -XX:ReservedCodeCacheSize=256m"
module=$(cd "$(dirname "$0")/../.." && pwd)
classpath=${1:?Pass resolved module-sticker macosArm64 test-classpath file}
out=${2:-$(mktemp -d /tmp/sticker-archive.native.XXXXXX)}
mkdir -p "$out"
args=()
while IFS= read -r library; do
  case "$library" in ''|*/klib/platform/*|*/klib/common/*|*.jar) continue;; esac
  [[ -e "$library" ]] || { echo "Missing compiled dependency: $library" >&2; exit 1; }
  args+=(-library "$library")
done < <(cat "$classpath"; echo)
"$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20/bin/konanc" \
  -target macos_arm64 -generate-test-runner -Xpartial-linkage-loglevel=error "${args[@]}" \
  -friend-modules "$module/build/classes/kotlin/macosArm64/main/klib/module-sticker" \
  -linker-option -L/Users/zoujiaqing/projects/Neton/neton/neton-core/build/nativeInterop/macosArm64 -linker-option -lenv \
  "$module/src/commonTest/kotlin/sticker/logic/StoredStickerZipTest.kt" \
  "$module/src/macosArm64Test/kotlin/StickerDeliveryPostgresTest.kt" \
  "$module/src/macosArm64Test/kotlin/StickerPackHttpHostTest.kt" \
  -o "$out/archive-tests" > "$out/compile.log" 2>&1
printf '%s\n' "$out/archive-tests.kexe"
