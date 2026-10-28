#!/bin/bash
# Build the Gen5W dashcam NEMESIS package: the recorder service and the player app.
#
# usage: ./build.sh <version>        e.g. ./build.sh 0.1
#
# Outputs in dist/:
#   dashcam-<version>.zip       NEMESIS package (nemesis install --update over ADB)
#   dashcam-<version>-usb.zip   the same, wrapped for USB install: extract onto a drive's root
#   DashcamPlayer.apk           the player app on its own
#   SHA256SUMS
#
# Needs: Java 17+ and the Android SDK ($ANDROID_HOME, default ~/Android/Sdk) with a platform,
# build-tools and NDK r25 (the last for Android 4.4: sdkmanager "ndk;25.2.9519653"); Gradle
# fetches the player's dependencies.
# Signing: $DASHCAM_KEYSTORE (default ../dashcam-signing/dashcam.jks), alias dashcam, password
# from $DASHCAM_KEY_PASS or else pass.txt beside the keystore. Every release must use the same
# key, or installs over an older one fail. The build stops if the signer isn't SIGNING_CERT_SHA256
# (forks with their own key put its digest here, from apksigner verify --print-certs).
set -euo pipefail

SIGNING_CERT_SHA256=668a8439a1526d82bbb02fb26e831711345f98d72f47923c22503207568d4b4a

VERSION=${1:?usage: build.sh <version>}
HERE=$(cd "$(dirname "$0")" && pwd)
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
D8=$BT/d8
DIST=$HERE/dist
KEYSTORE=${DASHCAM_KEYSTORE:-$HERE/../dashcam-signing/dashcam.jks}
if [ -n "${DASHCAM_KEY_PASS:-}" ]; then KS_PASS=env:DASHCAM_KEY_PASS
else KS_PASS=file:$(dirname "$KEYSTORE")/pass.txt; fi

die() { echo "build: $*" >&2; exit 1; }
[[ $VERSION =~ ^[0-9][0-9.]*$ ]] || die "version must look like 0.1"
[ -f "$ANDROID_JAR" ] || die "no android.jar under $ANDROID_HOME/platforms"
[ -x "$D8" ] && [ -x "$BT/apksigner" ] || die "no build-tools (d8, apksigner) under $ANDROID_HOME/build-tools"
[ -f "$KEYSTORE" ] || die "keystore not found: $KEYSTORE"
NDK=$(ls -d "$ANDROID_HOME"/ndk/25.* 2>/dev/null | sort -V | tail -1)
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi19-clang
[ -x "$CC" ] || die "no NDK r25 under $ANDROID_HOME/ndk (sdkmanager \"ndk;25.2.9519653\")"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stubs stand in for hidden framework classes at compile time; the unit's framework provides them.
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$work/stubs" \
    $(find "$HERE/stubs" -name '*.java')
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -cp "$work/stubs" -d "$work/classes" \
    $(find "$HERE/src" -name '*.java')
"$D8" --min-api 19 --lib "$ANDROID_JAR" --classpath "$work/stubs" --output "$work/dashcam.jar" \
    $(find "$work/classes" -name '*.class')
# The recorder's per-pixel work in C (native/): ARMv7 with NEON. Old Android's loader needs SysV hashes.
"$CC" -O3 -mfpu=neon -mfloat-abi=softfp -fPIC -shared -Wall -Wextra -Werror -Wl,--hash-style=both -s \
    -o "$work/libdashcam.so" "$HERE/native/dashcam.c"

# Player app
(cd "$HERE/player" && ANDROID_HOME=$ANDROID_HOME ./gradlew -q assembleRelease -PdashcamVersion="$VERSION")
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias dashcam --ks-pass "$KS_PASS" --min-sdk-version 19 \
    --out "$work/DashcamPlayer.apk" "$HERE/player/app/build/outputs/apk/release/app-release-unsigned.apk"
rm -f "$work/DashcamPlayer.apk.idsig"
certs=$("$BT/apksigner" verify --min-sdk-version 19 --print-certs "$work/DashcamPlayer.apk" 2>/dev/null) \
    || die "player signature doesn't verify for API 19"
grep -qi "certificate SHA-256 digest: $SIGNING_CERT_SHA256\$" <<< "$certs" \
    || die "player signed with the wrong key (expected certificate SHA-256 $SIGNING_CERT_SHA256)"

stage=$work/package
cp -R "$HERE/package" "$stage"
cp "$work/dashcam.jar" "$stage/dashcam.jar"
cp "$work/libdashcam.so" "$stage/libdashcam.so"
mkdir -p "$stage/app"
cp "$work/DashcamPlayer.apk" "$stage/app/DashcamPlayer.apk"
cp "$HERE/LICENSE" "$stage/LICENSE"
python3 -I - "$stage/NEMESIS.data" "$VERSION" <<'EOF'
import sys
p, v = sys.argv[1], sys.argv[2]
s = open(p).read()
assert "@VERSION@" in s
open(p, "w").write(s.replace("@VERSION@", v))
EOF

rm -rf "$DIST" && mkdir -p "$DIST"
python3 -I - "$stage" "$DIST/dashcam-$VERSION.zip" "$DIST/dashcam-$VERSION-usb.zip" <<'EOF'
import sys, zipfile
from pathlib import Path
src, out, usb = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob("*")):
        if p.is_file() and not p.name.startswith((".", "._")):
            z.write(p, p.relative_to(src).as_posix())
# NEMESIS's USB handler wants a type=install descriptor at the drive root.
with zipfile.ZipFile(usb, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("NEMESIS.data", "[nemesis]\nversion=1.0\ntype=install\npath=packages/dashcam.zip\n")
    z.write(out, "packages/dashcam.zip")
EOF
cp "$work/dashcam.jar" "$DIST/dashcam.jar"
cp "$work/DashcamPlayer.apk" "$DIST/DashcamPlayer.apk"
(cd "$DIST" && sha256sum -- *.zip *.apk > SHA256SUMS)
echo "built:"
ls -l "$DIST" | tail -n +2
