#!/usr/bin/env bash
# Opt-in live WebSocket integration test for VoiceFlowKit-android.
#
# Runs LiveBackendPromptFollowingTest against the REAL AI Builder backend:
# feeds the checked-in 24 kHz TTS WAV through VoiceFlowClient.transcribe and
# asserts the prompt reaches the model. Consumes API credits.
#
# Kotlin analog of the iOS scripts/test_live_integration.sh. Loads
# AI_BUILDER_TOKEN / AI_BUILDER_SPACE_ENDPOINT from .env (gitignored).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Build runs on Android Studio's bundled JBR.
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# Load AI_BUILDER_TOKEN / AI_BUILDER_SPACE_ENDPOINT from a local .env
# (gitignored). Copy .env.example to .env and fill in a real token.
ENV_FILE=""
if [[ -f "$ROOT/.env" ]]; then
  ENV_FILE="$ROOT/.env"
fi

if [[ -n "$ENV_FILE" ]]; then
  set -a
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line//[[:space:]]/}" ]] && continue
    export "$line"
  done < "$ENV_FILE"
  set +a
fi

TOKEN="${AI_BUILDER_TOKEN:-${VOICEFLOW_AI_BUILDER_TOKEN:-}}"
if [[ -z "$TOKEN" || "$TOKEN" == "replace-with-your-real-token" ]]; then
  echo "Error: set AI_BUILDER_TOKEN (or VOICEFLOW_AI_BUILDER_TOKEN) in .env — see .env.example" >&2
  exit 1
fi

export VOICEFLOW_LIVE_WS=1

echo "Running live WebSocket integration test (consumes AI Builder API credits)."
echo "Endpoint: ${AI_BUILDER_SPACE_ENDPOINT:-https://space.ai-builders.com/backend}"

cd "$ROOT"
# --rerun-tasks so the live call isn't skipped as UP-TO-DATE on repeat runs.
./gradlew :voiceflowkit:testDebugUnitTest \
  --tests "com.yage.voiceflowkit.LiveBackendPromptFollowingTest" \
  --rerun-tasks
