#!/bin/bash
# Builds native/test.c and runs it on the head unit over ADB: checks the NEON conversion against
# plain C and times both. Leaves nothing on the unit.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=$(ls -d "$ANDROID_HOME"/ndk/25.* | sort -V | tail -1)
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi19-clang
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}  # may include -s <serial>
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"$CC" -O3 -mfpu=neon -mfloat-abi=softfp -Wall -Wextra -Werror -o "$out/convert-test" "$HERE/test.c" "$HERE/dashcam.c"
$ADB push "$out/convert-test" /data/local/tmp/dashcam-convert-test > /dev/null
$ADB shell 'chmod 755 /data/local/tmp/dashcam-convert-test && /data/local/tmp/dashcam-convert-test; result=$?; rm /data/local/tmp/dashcam-convert-test; exit $result'
"$CC" -O2 -Wall -Wextra -Werror -o "$out/overlay-test" "$HERE/overlay_test.c"
$ADB push "$out/overlay-test" /data/local/tmp/dashcam-overlay-test > /dev/null
$ADB shell 'chmod 755 /data/local/tmp/dashcam-overlay-test && /data/local/tmp/dashcam-overlay-test; result=$?; rm /data/local/tmp/dashcam-overlay-test; exit $result'
