#!/usr/bin/env bash
# Build the launcher and publish it for over-the-air update.
#
#   ./scripts/publish-update.sh "what changed"
#
# Bumps versionCode/versionName, builds, copies the APK into backend/dist/, writes version.json,
# then commits and pushes so Render redeploys. The glasses pick it up within 5 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:-}"

source ./env.sh >/dev/null

GRADLE=launcher/app/build.gradle.kts
CODE=$(grep -oE 'versionCode = [0-9]+' $GRADLE | grep -oE '[0-9]+')
NEW_CODE=$((CODE + 1))
NEW_NAME="0.$NEW_CODE"
sed -i '' "s/versionCode = $CODE/versionCode = $NEW_CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NEW_NAME\"/" $GRADLE
echo "==> building $NEW_NAME ($NEW_CODE)"

(cd launcher && ./gradlew assembleDebug -q --no-daemon)
APK=~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk
mkdir -p backend/dist
cp "$APK" backend/dist/app.apk

SHA=$(shasum -a 256 backend/dist/app.apk | cut -d' ' -f1)
SIZE=$(stat -f%z backend/dist/app.apk)
cat > backend/dist/version.json <<JSON
{
  "version_code": $NEW_CODE,
  "version_name": "$NEW_NAME",
  "file": "app.apk",
  "size": $SIZE,
  "sha256": "$SHA",
  "notes": "$NOTES"
}
JSON

git add -A
git commit -q -m "Publish launcher $NEW_NAME${NOTES:+: $NOTES}

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push -q
echo "==> published $NEW_NAME ($SIZE bytes). Render redeploys; the glasses offer it in the app picker."
