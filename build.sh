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
# Needs: Java 17+ and the Android SDK ($ANDROID_HOME, default ~/Android/Sdk) with a platform
# and build-tools; Gradle fetches the player's dependencies.
# Signing: $DASHCAM_KEYSTORE (default ../dashcam-signing/dashcam.jks), alias dashcam, password
# from $DASHCAM_KEY_PASS or else pass.txt beside the keystore. Every release must use the same
# key, or installs over an older one fail.
set -euo pipefail

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

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The stubs stand in for hidden framework classes at compile time; the unit's framework provides them.
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$work/stubs" \
    $(find "$HERE/stubs" -name '*.java')
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -cp "$work/stubs" -d "$work/classes" \
    $(find "$HERE/src" -name '*.java')
"$D8" --min-api 19 --lib "$ANDROID_JAR" --classpath "$work/stubs" --output "$work/dashcam.jar" \
    $(find "$work/classes" -name '*.class')

# Player app
(cd "$HERE/player" && ANDROID_HOME=$ANDROID_HOME ./gradlew -q assembleRelease -PdashcamVersion="$VERSION")
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias dashcam --ks-pass "$KS_PASS" --min-sdk-version 19 \
    --out "$work/DashcamPlayer.apk" "$HERE/player/app/build/outputs/apk/release/app-release-unsigned.apk"
rm -f "$work/DashcamPlayer.apk.idsig"
"$BT/apksigner" verify --min-sdk-version 19 "$work/DashcamPlayer.apk" >/dev/null 2>&1 || die "player signature doesn't verify for API 19"

stage=$work/package
cp -R "$HERE/package" "$stage"
cp "$work/dashcam.jar" "$stage/dashcam.jar"
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
