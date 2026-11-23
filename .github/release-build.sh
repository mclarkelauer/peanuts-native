#!/bin/bash
# Builds the Peanuts Native release APK of the mclarkelauer/peanuts-native fork (the AYN Thor fork) from a tag, the way upstream builds its own release
# (android/app/build.gradle -PnxRelease, peanuts/docs/CONTRIBUTING.md "Release prewarm inputs" and "Checks of a release APK"): the games of
# peanuts/release-titles.txt, the real on-device packer (android/ondevice/build.sh's stage, made here by peanuts/scripts/ondevice-stage.sh), the prewarm
# seeds of upstream's signed APK of the same version (checked against peanuts/prewarm-sha256.tsv by the build), signed with the fork's release key.
# .github/workflows/release.yml runs it on a tag push; it runs the same on a Linux machine.
#
# Usage: .github/release-build.sh <tag> <out dir>     tag: v<upstream version>-thor.<n>, e.g. v0.1.7-thor.1 (peanuts/VERSION must say the upstream version)
#   → <out dir>/peanuts-native-<tag>-arm64-v8a.apk
# Environment: ANDROID_HOME, ANDROID_NDK_HOME (the NDK of build.gradle's ndkVersion), NX_SIGN_STORE, NX_SIGN_ALIAS, NX_SIGN_PASS (the release key; see README
#   "Release"); RELEASE_WORK (default <out dir>/work: the packer stage and the seeds, kept between runs); UPSTREAM_TAG (default v<upstream version>-1,
#   the upstream release whose APK gives the seeds). Needs a JDK the Gradle wrapper runs on (17 or 21), zsh, cmake, ninja, curl, python3, unzip and the
#   .NET 10 SDK (the translator, dm/gfx/tools/dmsh_xlate_android).
set -euo pipefail
TAG=${1:?usage: release-build.sh <tag> <out dir>}; OUT=${2:?usage: release-build.sh <tag> <out dir>}
REPO=$(cd "$(dirname "$0")/.." && pwd)
die() { printf '[RED] %s\n' "$*" >&2; exit 1; }

[[ $TAG =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-thor\.([0-9]+)$ ]] || die "$TAG: a release tag is v<major>.<minor>.<patch>-thor.<n>, e.g. v0.1.7-thor.1"
BASE=${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}; N=${BASH_REMATCH[4]}
VERSION=${TAG#v}
# One versionCode per tag, rising with every upstream version and every fork release of it: 0.1.7-thor.1 → 10701
CODE=$(( BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 10000 + BASH_REMATCH[3] * 100 + N ))
TREE=$(tr -d '[:space:]' < "$REPO/peanuts/VERSION")
[ "$TREE" = "$BASE" ] || die "$TAG names upstream version $BASE but peanuts/VERSION says $TREE"
for v in ANDROID_HOME ANDROID_NDK_HOME NX_SIGN_STORE NX_SIGN_ALIAS NX_SIGN_PASS; do [ -n "${!v:-}" ] || die "set $v"; done
[ -f "$NX_SIGN_STORE" ] || die "NX_SIGN_STORE: $NX_SIGN_STORE is not a file"
for t in zsh cmake ninja curl dotnet python3 unzip; do command -v $t >/dev/null || die "$t is needed"; done

mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
WORK=${RELEASE_WORK:-$OUT/work}; mkdir -p "$WORK"
GAMES=$(tr -d '[:space:]' < <(sed 's/$/,/' "$REPO/peanuts/release-titles.txt")); GAMES=${GAMES%,}
echo "== $TAG: Peanuts Native $VERSION (versionCode $CODE), $(wc -l < "$REPO/peanuts/release-titles.txt") games"

# The prewarm seeds: upstream's release APK of the same version carries them as assets/profiles/<title ID>.seed (CONTRIBUTING.md "Release prewarm inputs")
SEEDS=$WORK/seeds
if [ ! -f "$SEEDS/.from" ] || [ "$(cat "$SEEDS/.from")" != "${UPSTREAM_TAG:-v$BASE-1}" ]; then
  UP=${UPSTREAM_TAG:-v$BASE-1}
  echo "== seeds from igawa6/peanuts-native $UP"
  rm -rf "$SEEDS"; mkdir -p "$SEEDS"
  curl -sSfL -o "$WORK/upstream.apk" "https://github.com/igawa6/peanuts-native/releases/download/$UP/PeanutsNative-$BASE.apk"
  unzip -q -o -j "$WORK/upstream.apk" 'assets/profiles/*.seed' -d "$SEEDS"
  echo "$UP" > "$SEEDS/.from"
fi

# The on-device packer's native side; the translator in it must come from this tree (build.gradle nxPackXlateCheck), so a cached stage is rebuilt when the tree's translator changed
STAGE=$WORK/ondevice/stage
if ! python3 "$REPO/dm/gfx/tools/xlate_tree.py" --check-lib "$STAGE/lib/libdmsh_xlate.so" >/dev/null 2>&1 || [ ! -f "$STAGE/python/bin/nxpack_worker" ]; then
  echo "== packer stage → $WORK/ondevice"
  zsh "$REPO/peanuts/scripts/ondevice-stage.sh" "$WORK/ondevice"
fi

echo "== APK"
export PN_PREWARM_SEEDS=$SEEDS
cd "$REPO/android/app"
rm -rf out
${GRADLE:-./gradlew} --no-daemon -q apk -PnxApp=peanuts -PnxLabel="Peanuts Native" -PnxRelease=true -PnxVersion="$VERSION" -PnxVersionCode="$CODE" \
  -PnxGames="$GAMES" -PnxPackStage="$STAGE"
APK=out/peanuts-native-$VERSION.apk
[ -f "$APK" ] || die "no $APK"

echo "== checks"
python3 "$REPO/peanuts/scripts/check-min-api.py" "$APK"
python3 "$REPO/peanuts/scripts/check-apk-holes.py" "$APK"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -E '^Signer #1 certificate (DN|SHA-256)'
"$BT/aapt2" dump badging "$APK" | grep -E "^package:|^native-code:|^application-label:"
"$BT/aapt2" dump badging "$APK" | grep -q "versionName='$VERSION'" || die "the APK's versionName is not $VERSION"

cp "$APK" "$OUT/peanuts-native-$TAG-arm64-v8a.apk"
ls -l "$OUT/peanuts-native-$TAG-arm64-v8a.apk"
