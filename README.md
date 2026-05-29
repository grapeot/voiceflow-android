# VoiceFlow (Android)

This repo ships the Android port of VoiceFlowKit: a standalone Kotlin Android
library that wraps real-time audio capture + transcription (PCM16 24 kHz mono →
WebSocket → partial transcripts → final text). Drop it into any Android app
(minSdk 26) and add voice input in roughly 50 lines.

It mirrors the iOS VoiceFlowKit Swift Package — same public facade, same wire
protocol, same recovery semantics — translated to idiomatic Kotlin.

Public repo: <https://github.com/grapeot/voiceflow-android>

### Designed as a generative kernel for AI integrators

VoiceFlowKit is built around the [generative kernel](https://yage.ai/ai-software-engineering.html)
idea: the library itself is the **core kit** (mic capture + WS transport +
transcription pipeline), `skills/adding_voice_input_with_voiceflowkit_android.md`
is the **guiding knowledge** written for AI agents reading it inside another
host's codebase, and `VoiceFlowClient.makeStub(...)` / `VoiceFlowAudioMetering` /
the typed `VoiceFlowError` cases are **leverage tools** the AI can compose
without going to the raw WebSocket layer.

Concretely that means:

- Errors come back as typed `VoiceFlowError` subclasses (`HttpError(statusCode)`,
  `ConnectionLost(detail)`, `WebsocketError(detail)`, …) — not a wrapped
  "something went wrong." Agents can pattern-match and react instead of guessing.
- The facade is small and stable, but every primitive an integrator might need
  (offline stub client, audio metering helper, two-layer caption store) is
  exposed. No "hidden for your own good" knobs.
- The internal pipeline lives in `com.yage.voiceflowkit.internal` and is marked
  Kotlin `internal`, so it is not part of the published ABI. Hosts depend on the
  facade only.

> AI agents who want to add voice input to their host's Android app should read
> `skills/adding_voice_input_with_voiceflowkit_android.md` end-to-end before
> touching the kit.

## VoiceFlowKit (the library)

### Identity

- Maven coordinates: group `com.yage`, artifact `voiceflowkit`
- Package root: `com.yage.voiceflowkit`
- Internal pipeline (not exported): `com.yage.voiceflowkit.internal`
- minSdk 26, compiled against SDK 35, Java/Kotlin target 17

Default endpoint: `https://space.ai-builders.com/backend`. Default model:
`gpt-realtime`.

### Quick start

The library is published via JitPack from this repo's Git tags, so a host
project only needs to add the JitPack repository and depend on the tag.

Add JitPack to your repositories in `settings.gradle.kts` (or the root
`build.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then depend on the tag from the host app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.grapeot:voiceflow-android:0.1.0")
}
```

JitPack rewrites the group to `com.github.grapeot` and uses the repo name
`voiceflow-android` as the artifact, so the import package stays
`com.yage.voiceflowkit`.

For local development against an unpublished checkout, you can substitute the
module with a Gradle composite build instead, pointing at a sibling clone of
this repo:

```kotlin
// settings.gradle.kts of the host project
includeBuild("../voiceflow-android") {
    dependencySubstitution {
        substitute(module("com.yage:voiceflowkit")).using(project(":voiceflowkit"))
    }
}
```

```kotlin
// build.gradle.kts of the host app module
dependencies {
    implementation("com.yage:voiceflowkit")
}
```

The library declares `RECORD_AUDIO` and `INTERNET` in its manifest; host apps
inherit them via manifest merge but must still request `RECORD_AUDIO` at runtime
(Android cannot grant it from a library).

Five lines to start streaming:

```kotlin
import com.yage.voiceflowkit.*

val config = VoiceFlowConfig(tokenProvider = { yourToken })
val client = VoiceFlowClient(config)
val session = client.startSession()
// → push PCM16 chunks via session.sendAudioChunk(chunk)
// → collect partial transcripts from session.events
// → finalize with session.commitAndStop()
```

### Public surface

| Type | What it does |
|---|---|
| `VoiceFlowConfig` | endpoint + suspend `tokenProvider` + optional prompt/terms |
| `VoiceFlowClient` | session factory + bulk transcribe + connection test (suspend funcs, internal `Mutex`) |
| `VoiceFlowSession` | one live recording session — send audio, ping, commit, cancel; exposes `events: Flow<VoiceFlowEvent>` |
| `VoiceFlowMicrophone` | mic capture (needs `Context`); permission check + PCM16/24kHz/mono `start(onPCMChunk)` + `stop()`; `audioLevel: Flow<Float>` |
| `VoiceFlowEvent` | sealed class: `PartialTranscript / PhaseChanged / RecoveryStarted / RecoveryFailed` |
| `VoiceFlowConnectionPhase` | enum: `Connecting / Connected / Recovering / Generating / Disconnected` |
| `VoiceFlowError` | sealed class (extends `Exception`): `MissingToken / InvalidEndpoint / HttpError / SessionUnavailable / WebsocketError / ConnectionLost / EmptyTranscript / MicrophoneUnavailable / AudioConversionFailed / Underlying` |
| `VoiceFlowClient.makeStub(...)` | offline stub client for instrumentation tests + previews — no WebSocket, canned final transcript |
| `VoiceFlowAudioMetering` | `normalizedLevel(pcm16le): Float` — same 0..1 RMS→dB→linear level the mic uses |
| `TranscriptionResult` | `(text, requestId)` returned by one-shot `transcribe()` |
| `StreamCaption` / `StreamCaptionStore` | optional two-layer caption helper (persistent + transient flash) for "Reconnecting…" / "Stream restored." prompts |

Two work modes: **live streaming** (`startSession` → push chunks →
`commitAndStop`) or **bulk** (`transcribe(wavFile)` for one-shot WAV files).

### Idiom mapping from Swift

| Swift | Kotlin |
|---|---|
| `actor` | class with `suspend` funcs + internal `Mutex` |
| `AsyncStream<VoiceFlowEvent>` | `Flow<VoiceFlowEvent>` (backed by a `SharedFlow`) |
| `@Sendable () async throws -> String` | `typealias TokenProvider = suspend () -> String` |
| enum with associated values | sealed class |
| `OSLog` subsystem | `android.util.Log` tags (the `loggerSubsystem` field is dropped) |
| `AVAudioEngine` tap | `AudioRecord` (24 kHz PCM16 mono, no resampling) |
| `URLSession` WebSocket | OkHttp `WebSocket` + `WebSocketListener` |

### Integration guide for AI agents

The full integration walkthrough — reference pipeline, the live-streaming and
bulk modes, common traps, and acceptance criteria — is in
`skills/adding_voice_input_with_voiceflowkit_android.md`. Read it before writing
client code; it is treated as a first-class deliverable, not an afterthought.

### Backend / wire protocol

Default endpoint is AI Builder Space (`https://space.ai-builders.com/backend`).
`POST /v1/audio/realtime/sessions` returns a ticketed `ws_url`, then the client
opens a WebSocket for PCM16 24 kHz mono streaming. Model is `gpt-realtime`. Swap
the endpoint via `VoiceFlowConfig.endpoint` for a compatible backend. Full
protocol details are in `docs/rfc.md`.

## VoiceFlow (the app)

A reference app lives under `app/` (`applicationId com.yage.voiceflow`), the
Android analog of the iOS VoiceFlow app, built entirely on top of VoiceFlowKit
with Jetpack Compose. It has two screens: **Record** and **Settings**.

**Record**: start/stop recording with a live partial transcript, auto-copy to
the clipboard on stop, history navigation (prev/next), save the recording as a
WAV file, manual copy, and an optional push to OpenCode when one is configured.
A waveform reflects the live audio level while recording.

**Settings**: AI Builder token entry with a Test button (the token is kept in
`EncryptedSharedPreferences` and surfaced only as a saved/not-saved state), a
fixed default endpoint, optional OpenCode configuration (server URL + username
stored on-device, password in encrypted storage) with its own Test button, the
transcription prompt and terms inputs for shaping recognizer output, and a
language preference toggle (System / English / 简体中文) that switches the UI
in place.

### Deep link

```text
voiceflow://record
```

Opening this URL launches the app and starts recording immediately. See
`docs/rfc.md` for the deep-link handling.

### Install (download)

Prebuilt APKs are attached to each [GitHub release](https://github.com/grapeot/voiceflow-android/releases).
Download `voiceflow-<version>.apk`, open it on the device, and allow installing
from this source. The release APK is signed with the Android debug key, so it
installs directly without a Play Store account. Maintainers cut a release with
`scripts/release.sh <version>`.

## Building

This is a standalone Gradle project with its own wrapper (Gradle 9.3.1). The
system `java` is not on PATH, so export the Android Studio JBR first:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# library:
./gradlew :voiceflowkit:assembleDebug
./gradlew :voiceflowkit:testDebugUnitTest   # unit tests

# reference app:
./gradlew :app:assembleDebug
./gradlew :app:installDebug                 # install on a connected device/emulator
```

## Privacy & credentials

The repo only contains fake examples. Real tokens are supplied by the host app
through `VoiceFlowConfig.tokenProvider`; the library never persists them. Tokens
do not appear in logs or error messages.

## Documentation

- `docs/prd.md` — product scope (the library as a generative kernel)
- `docs/rfc.md` — module structure, facade contract, wire protocol
- `docs/working.md` — daily change log
- `skills/adding_voice_input_with_voiceflowkit_android.md` — integration walkthrough for AI agents
- `AGENTS.md` — working language and hard rules for agents touching this repo
