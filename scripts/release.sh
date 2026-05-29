#!/usr/bin/env bash
# Cut a GitHub release for VoiceFlow Android.
#
# Builds the signed release APK of the reference app and publishes a GitHub
# release with that APK attached. The release build is signed with the
# auto-generated Android debug keystore (see app/build.gradle.kts) so the APK
# is directly installable — this is a reference app, not a Play Store upload,
# so we deliberately avoid managing a private upload key.
#
# Usage:
#   scripts/release.sh <version>
#   scripts/release.sh 0.1.1
#
# What it does:
#   1. Builds :app:assembleRelease (signed, installable APK).
#   2. Tags the current commit as <version> and pushes the tag (JitPack builds
#      the library from this tag; see README).
#   3. Creates a GitHub release for <version> with the APK attached.
#
# Requirements: gh (authenticated), a clean committed tree, JDK 17 (Android
# Studio's bundled JBR is used below).
set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: scripts/release.sh <version>   e.g. scripts/release.sh 0.1.1" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Building signed release APK"
./gradlew :app:assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "Release APK not found at $APK" >&2; exit 1; }
RELEASE_APK="$ROOT/app/build/outputs/apk/release/voiceflow-${VERSION}.apk"
cp "$APK" "$RELEASE_APK"
echo "==> APK: $RELEASE_APK"

echo "==> Tagging $VERSION"
if git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "    tag $VERSION already exists, skipping tag"
else
  git tag -a "$VERSION" -m "VoiceFlow Android $VERSION"
  git push origin "$VERSION"
fi

echo "==> Creating GitHub release $VERSION"
gh release create "$VERSION" "$RELEASE_APK" \
  --title "VoiceFlow Android $VERSION" \
  --notes "VoiceFlow Android $VERSION.

The attached \`voiceflow-${VERSION}.apk\` is the reference app, signed with the
Android debug key and directly installable on a device (enable installing from
unknown sources). The library is consumed via JitPack — see the README.

Install: download the APK, open it on the device, allow install from this source." \
  --latest

echo "==> Done. https://github.com/grapeot/voiceflow-android/releases/tag/$VERSION"
