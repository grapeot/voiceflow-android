# VoiceFlowKit (Android) RFC

## 设计判断

这个 repo 交付一个独立的 Kotlin Android library —— VoiceFlowKit，是 iOS 仓库
`voiceflow` 里 Swift Package 的 Android 移植。公开 facade、wire protocol、断线恢复
语义都以 iOS 仓库的 Swift 源为权威；语言/平台 idiom 按 Kotlin/Android 习惯翻译。

实现采用 OkHttp（WebSocket + HTTP）、`AudioRecord`（mic capture）、kotlinx-coroutines
（并发）、`org.json`（消息解析，对齐经确认可用的 Android 参考实现）。不引入 Hilt、
Compose、Retrofit、kotlinx-serialization。库 DI-agnostic，只暴露普通构造函数和工厂
函数。

公开仓库：<https://github.com/grapeot/voiceflow-android>（默认分支 `main`）。

## 模块划分

独立 Gradle 工程（自带 wrapper + settings），不是挂到别的工程下的模块。library 模块
文件夹 `voiceflowkit`，artifactId `voiceflowkit`，Maven group `com.yage`。

```text
voiceflow-android/                       # repo root（独立 Gradle 工程）
  settings.gradle.kts                    # rootProject = "voiceflowkit"; include(":voiceflowkit", ":app")
  build.gradle.kts                       # root；plugins apply false
  gradle.properties
  gradle/wrapper/                        # Gradle 9.3.1 wrapper
  gradle/libs.versions.toml              # version catalog（library 不引 Compose/Hilt；Compose 仅参考 app 用）
  voiceflowkit/                          # library 模块
    build.gradle.kts                     # com.android.library；namespace com.yage.voiceflowkit
    consumer-rules.pro / proguard-rules.pro
    src/main/AndroidManifest.xml         # 声明 RECORD_AUDIO + INTERNET
    src/main/java/com/yage/voiceflowkit/
      VoiceFlowKit.kt                    # 版本常量 object
      VoiceFlowConfig.kt                 # 公开配置 data class + TokenProvider typealias
      VoiceFlowClient.kt                 # 公开入口（suspend funcs + Mutex），makeStub 工厂
      VoiceFlowSession.kt                # 公开 realtime 会话 + SessionEventBridge
      VoiceFlowEvent.kt                  # sealed VoiceFlowEvent + enum VoiceFlowConnectionPhase
      VoiceFlowError.kt                  # sealed class : Exception + from(internal error)
      TranscriptionResult.kt             # data class(text, requestId)
      VoiceFlowMicrophone.kt             # 公开 mic 封装（需 Context），audioLevel: Flow<Float>
      VoiceFlowAudioMetering.kt          # object normalizedLevel(ByteArray): Float
      StreamCaption.kt                   # StreamCaption + StreamCaptionStore
      internal/                          # 全部标 Kotlin internal，不进 ABI
        RealtimeTranscriptionConfig.kt   # 常量
        RealtimeTranscriptEvent.kt       # event / status / phase / error 类型层 + RealtimeSessionContext
        RealtimeMessageParser.kt         # JSON → event；构造 start 控制消息
        RealtimeApiUrlBuilder.kt         # normalize base / 拼 API url / http→ws/wss
        Pcm16WavWriter.kt               # 写/读 44-byte RIFF/WAVE header（24kHz mono PCM16 LE）
        AudioChunkCache.kt              # 磁盘 PCM cache（断线重放）
        AudioChunkEncoder.kt            # PCM 分片聚合 helper
        TranscriptHelpers.kt            # TranscriptDeltaReducer + FinalizeTranscriptAccumulator + RealtimeTranscriptionSupport
        RealtimeWebSocketSession.kt     # 单条 live WS 连接（OkHttp WebSocketListener）
        RealtimeLiveSessionHandle.kt    # recovery / cache replay / finalize 重试 orchestrator
        RealtimeTranscriptionClient.kt  # RealtimeTranscribing 实现：beginLiveSession + transcribeBulkPcm + makeSession
        BulkTranscriptionProgress.kt    # bulk accumulator（finished-vs-error 顺序修正）
        AndroidAudioRecorder.kt         # AudioRecord 24kHz PCM16 mono 采集
        AIBuilderConnectionClient.kt    # testConnection over OkHttp
        MockRealtimeTranscriptionClient.kt  # offline stub transcriber（makeStub 用）
    src/test/java/com/yage/voiceflowkit/  # JVM 单元测试 + live 集成测试
    src/test/resources/fixtures/tts_all_caps_24k.wav  # live 测试用 24kHz TTS 音频
  app/                                   # 参考 app（已交付，对齐 iOS src/VoiceFlow/）
    build.gradle.kts                     # com.android.application；applicationId com.yage.voiceflow；依赖 project(":voiceflowkit")
    src/main/AndroidManifest.xml         # RECORD_AUDIO；voiceflow://record deep link
    src/main/java/com/yage/voiceflow/
      MainActivity.kt                    # Compose 入口 + deep link 处理
      VoiceFlowApp.kt                    # 根 Composable
      MainViewModel.kt                   # 持 VoiceFlowClient facade、live session + 事件消费、跨页面状态
      data/SettingsStore.kt              # token/password 存 EncryptedSharedPreferences，其余存 SharedPreferences
      i18n/                              # 应用内语言切换（CompositionLocal + createConfigurationContext）
      model/                             # RecordingStatus / TranscriptHistory / OpenCodeSendStatus / ConnectionTestStatus / AppLanguage / DeepLink ...
      service/OpenCodeClient.kt          # app 专属"发送到 OpenCode"HTTP relay（不进 Kit）
      ui/record/                         # RecordScreen
      ui/settings/                       # SettingsScreen
      ui/components/                     # WaveformView / CapsuleButton / StatusText / RecordingTimer / GhostIconButton
      ui/theme/                          # Theme + DesignTokens
    src/main/res/
      values/ values-zh-rCN/             # en + 简体中文 strings
      drawable/voiceflow_logo.png        # 由 iOS logo 移植，用作 launcher icon
      mipmap-*/                          # launcher icons
      xml/network_security_config.xml
  docs/prd.md / docs/rfc.md / docs/working.md
  skills/adding_voice_input_with_voiceflowkit_android.md  # host AI 集成指南
  scripts/test_live_integration.sh       # 跑 live 集成测试（消耗 API 额度）
```

公开表面只有 `com.yage.voiceflowkit` 包下的 facade。`internal` 包下所有类/函数标
Kotlin `internal`，不属于 published ABI —— 库内部可以自由演进。host 只 import facade。

`app/` 模块（包名 `com.yage.voiceflow`）是 library 的消费方示范，对齐 iOS
`src/VoiceFlow/`。它只通过公开 facade 用 Kit：`MainViewModel` 持有 `VoiceFlowClient`，
live session 通过 `startSession()` 拿 `VoiceFlowSession`、在 viewModel scope 里 drain
`session.events`，bulk 重发走 `transcribe(wavFile)`，连接测试走 `testConnection()`。
分层与 iOS 同构 —— 底层 audio/WS pipeline 在 `voiceflowkit`，上层 app 业务行为
（OpenCode relay、设置持久化、历史/剪贴板、i18n）在 `app/` 自己的包里，UI 不直接碰
Kit internal。本地开发时 app 通过 Gradle 工程内 `project(":voiceflowkit")` 直接依赖
library；远程消费走 JitPack（见"消费方式"）。

### 救援门控：保存 / 重发录音

`UiState` 上的派生属性 `canSaveRecording` / `canResendRecording` 决定三点菜单里
"保存录音""重发录音"两项的 enabled 状态。这两项是用户的兜底抢救路径，必须在转写卡死
（停在 `RecordingStatus.Transcribing`）时仍然可用，因此门控只看"是否已有持久化的音频
文件"：

- `canSaveRecording = hasRecordingFile`
- `canResendRecording = hasToken && (recordingStatus == Recording || hasRecordingFile)`

刻意去掉了原先的 `canNavigateTranscriptHistory`（仅 Idle/Ready 可导航）依赖——它会在
Transcribing 时变 false，把救援按钮一起锁死。`hasRecordingFile` 只在 Stop 成功落盘 WAV
后置 true、新会话开始即清掉，录音进行中恒为 false，所以纯靠它门控天然排除了"录音未落盘"
的情况，不需要再叠 `canNavigateTranscriptHistory`。`MainViewModel.resendLastRecording`
的内部守卫同步放开：非 Recording 分支不再要求 `canNavigateTranscriptHistory`，只要
WAV 存在即把状态打回 Transcribing 并强制重新转写（关闭当前 WS、用已落盘 WAV 重走
`finishTranscriptionFromLastRecording`），替换掉卡住的 in-flight 尝试。`RecordScreen`
里"重发录音"菜单项也去掉了 `recordingStatus != Transcribing` 的额外禁用条件，直接绑
`canResendRecording`。与 iOS 行为对齐。

## VoiceFlowKit 公开 API

下面是暴露给 host 的 surface（对齐 iOS 版，名字按 Kotlin 习惯）：

- `VoiceFlowKit`（object）：`VERSION` 常量（`"0.1.0-dev"`）。
- `VoiceFlowConfig`（data class）：`endpoint` / `tokenProvider`（`suspend () -> String`） /
  `model` / `prompt` / `terms`。companion 暴露 `DEFAULT_ENDPOINT` /
  `DEFAULT_MODEL`。注意没有 `language` 字段 —— 语言提示当 prompt 拼接，用户自己写。
- `VoiceFlowClient`（class）：入口。`constructor(config)`（prod 路径，内部 new
  `RealtimeTranscriptionClient()`）；`updateConfig(config)` / `currentConfig()`
  （内部 `Mutex` 守护）；`startSession(): VoiceFlowSession`；
  `transcribe(wavFile, onPartialTranscript?): TranscriptionResult`；
  `transcribe(preservedAudio, onPartialTranscript?): TranscriptionResult`；
  `discardPreservedAudio(preservedAudio)`；`testConnection()`。
  companion `makeStub(config, liveTranscript, bulkTranscript)` 返回 offline client。
- `VoiceFlowSession`（class）：实时会话句柄。`sendAudioChunk(chunk)` 推 PCM、`ping()`
  心跳、`commitAndStop(onPartialTranscript?): String` 收口、`cancel()` 取消并清理缓存、
  `abortPreservingAudio(): VoiceFlowPreservedAudio?` 关闭连接但保留已录 PCM 供后续重试、
  `connectionPhase(): VoiceFlowConnectionPhase` 读相位、`events: Flow<VoiceFlowEvent>`
  订阅事件。`events` 由 `SessionEventBridge` 背书的 `SharedFlow`（replay 可配，
  extraBufferCapacity 16，对齐 Swift `AsyncStream` 的 `bufferingNewest(16)`）；调用方
  应在 session 启动前/启动时就开始 collect 以捕获早期事件。
- `VoiceFlowMicrophone`（class，需 `Context`）：mic 封装。`hasPermission()` /
  `requestPermission()`（只返回当前授予状态，库无法从 library 拉起系统弹窗，host 自己
  用 ActivityResult 请求）/ `start(persist, onPCMChunk)`（PCM16 24kHz mono）/
  `stop(): File?`（可选写 WAV）/ `discard()`；`audioLevel: Flow<Float>`（0..1，EMA
  平滑：0.7 旧 + 0.3 新）；`recordingFile: File?`（persist 后设置）。
- `VoiceFlowAudioMetering`（object）：`normalizedLevel(pcm16le): Float`，RMS → dB
  （min -50、max -10）→ linear → *0.9 → clamp 0..1，直接对齐 Swift。
- `VoiceFlowEvent`（sealed class）：`PartialTranscript(text)` /
  `PhaseChanged(phase)` / `RecoveryStarted` / `RecoveryFailed(message)`。
- `VoiceFlowConnectionPhase`（enum）：`Connecting` / `Connected` / `Recovering` /
  `Generating` / `Disconnected`。
- `VoiceFlowError`（sealed class : `Exception`）：`InvalidEndpoint` / `MissingToken` /
  `HttpError(statusCode)` / `SessionUnavailable` / `WebsocketError(detail)` /
  `ConnectionLost(detail)` / `AudioConversionFailed` / `EmptyTranscript` /
  `MicrophoneUnavailable` / `Underlying(detail)`。companion `from(RealtimeTranscriptionError)`
  在 facade 边界把内部 error 翻译成公开 error（对齐 Swift 的 `init(_:)`）。
- `TranscriptionResult`（data class）：`(text, requestId)`。`requestId` 是一个新 UUID。
- `VoiceFlowPreservedAudio`（class）：`abortPreservingAudio()` 返回的轻量句柄，公开
  `id` / `byteCount`，底层临时 PCM 文件只由 Kit 管理。
- `StreamCaption` / `StreamCaptionStore`：双层 caption 模型（persistent + transient
  ~3 秒闪现），`StreamCaptionStore.caption` 是 `StateFlow`，`flashTransient` 在内部
  scope 上 `delay` 后清除，重复调用重置计时。存 localization key 而非显示字符串。

公开 facade 把内部细分事件（`error` / `disconnected`）合并进 `PhaseChanged` 和
`RecoveryFailed`；recoverable 的 "buffer too small" 噪音在 kit 内部就过滤掉，不进
`events` stream。这与 iOS 版 PR #38 收紧后的 facade-only 表面一致。

## 内部架构

### 类型层

`RealtimeTranscriptEvent`（sealed class）：`Status(RealtimeServerStatus)` /
`TextDelta(content, isNewResponse)` / `ErrorEvent(message)` / `Disconnected` /
`RecoveryStarted` / `RecoveryFailed(message)`。`RealtimeServerStatus`（enum）：
`Idle` / `Connecting` / `Connected` / `Generating`。`RealtimeConnectionPhase`
（enum）：`Disconnected` / `Connecting` / `Connected` / `Generating` / `Recovering`。
`RealtimeTranscriptionError`（sealed class : `Exception`）：`InvalidBaseUrl` /
`MissingToken` / `InvalidMessage` / `ConnectionLost(detail)` /
`WebsocketError(detail)` / `SessionUnavailable` / `EmptyTranscript` /
`AudioConversionFailed` / `HttpError(statusCode)`。

### transcribing 抽象

```text
internal interface RealtimeTranscribing
  suspend beginLiveSession(baseURL, token, model, context, onEvent): RealtimeLiveTranscriptionSession
  suspend transcribeBulkPcm(pcm, baseURL, token, model, context, onPartial): String

internal interface RealtimeLiveTranscriptionSession
  suspend appendAudioChunk(chunk)
  suspend heartbeat()
  suspend finalize(onPartial): String
  suspend cancel()
  suspend abortPreservingAudio(): VoiceFlowPreservedAudio?
  suspend connectionPhase(): RealtimeConnectionPhase
```

`RealtimeTranscriptionClient` 是 prod 实现；`MockRealtimeTranscriptionClient` 是
offline stub（`makeStub` 用）。`VoiceFlowClient` 持有一个 `RealtimeTranscribing`，
internal constructor 接受注入，public constructor 默认 new prod 实现。

### 核心恢复机制（对齐经确认的 Android 参考 + iOS 仓库）

1. **AudioChunkCache**：所有 PCM 写入临时磁盘 `.pcm` 文件（`voiceflow-stream-<uuid>.pcm`，
   在 cacheDir）；`@Synchronized` append / readChunk（`RandomAccessFile` seek + readFully，
   越界返回空数组）；断线后可从 offset 0 重放。构造接受 `directory: File` 以便脱离
   `Context` 单测。
2. **RealtimeWebSocketSession**：单条 live WS 连接，在 `session_ready` 握手后才构造。
   OkHttp `WebSocket` + `WebSocketListener` 把 parse 后的 event 推给 `onEvent` 回调，
   检测到 `transcript_completed` 自动发 `stop`。`sendAudioChunk` 由 `Mutex` 串行化
   （= Swift `RealtimeWebSocketSender`），committed/closed 后 no-op，记 enqueued 字节。
   `sendCommit` 守 `minCommitAudioBytes=4800`（不足抛 `WebsocketError`）。`ping`
   检查 closed flag，已关抛 `ConnectionLost`。
3. **RealtimeLiveSessionHandle**：recovery / finalize orchestrator。持 `AudioChunkCache`、
   `makeSession: suspend () -> RealtimeWebSocketSession`、`onEvent` 回调、当前 session、
   `isRecovering` 门闩、phase、finalize 状态。
   - `appendAudioChunk`：先写 cache，再 send；失败 → `recover()`。
   - `heartbeat()`：ping；失败 → `recover()`。
   - `recover(reason)`：守 `!isRecovering`；phase=Recovering；emit `RecoveryStarted`；
     close 旧 session；循环 `MAX_RECOVER_ATTEMPTS` 带退避
     `RECOVER_BACKOFF_BASE_MS * 2^(attempt-1)`；`makeSession` + `replayCache`；耗尽则
     phase=Disconnected + emit `RecoveryFailed`。
   - `replayCache`：按 `REPLAY_CHUNK_SIZE` 窗口读，追上 live tail 时 `delay(20)`。
   - `finalize(onPartial)`：2 次重试循环 —— `ensureSessionReadyForFinalize`（等
     `!isRecovering`，session 为 null 则 recover）→ cache-vs-pendingCommitAudioBytes
     resync → `waitForFinalizeResult`（30s 超时，racing idle/disconnect/error 信号）→
     `preserveForRetry` / `restoreAfterRetry`；resolved 为空映射 `EmptyTranscript`。
   - `ingestServerEvent` / `shouldNotifyUI`：复刻 Swift 的 finalize-aware 过滤（非
     finalize 期抑制 textDelta；非 finalize 期抑制 recoverable "buffer too small" error）。
     这条门也实现了产品上"录音期不输出转写"的取舍（见 `docs/prd.md`）：录音时实时
     textDelta 被抑制以避免零散语音拉低识别质量，只有 Stop 后 finalize 期才逐个回调
     delta。**库每个 delta 回调一次**，逐 delta 打字机的责任在 app 层 —— app 必须把
     这些回调按序喂给 UI 而不能让 conflating StateFlow 把中间快照吞掉（参考 app 的
     channel-drain 管线见 `docs/working.md` 2026-05-30 条目）。
   - `abortPreservingAudio()`：关闭当前 WebSocket、停止 recovery、保留 `AudioChunkCache`
     的 PCM 文件并返回 `VoiceFlowPreservedAudio`。旧 `cancel()` 语义保持不变，仍会删除缓存。
   - `attachInitialSession(session)`：初始连接先 replay 再 attach。
4. **isRecovering 门闩**：恢复期间暂停 live send，避免与 replay 交错。

### bulk 路径

`transcribeBulkPcm`：一条 session，按 `REPLAY_CHUNK_SIZE` 步进发 PCM，`sendCommit`，
轮询 `BulkTranscriptionProgress` 直到 finished 或 30s。`BulkTranscriptionProgress`
（`Mutex` 守护）按 `TranscriptDeltaReducer` 累积，`Status(Idle)` 置 finished；
**关键顺序修正** —— 一旦 finished，忽略后续 textDelta，绝不用 trailing disconnect/error
覆盖成功结果（对齐 iOS PR #34 的 race 修复）。

## Wire protocol

```http
POST {endpoint}/v1/audio/realtime/sessions
Authorization: Bearer <token>
Content-Type: application/json

{ "model": "gpt-realtime", "vad": false, "silence_duration_ms": 1200,
  "prompt": "...optional...", "terms": ["...optional..."] }

→ { "session_id": "...", "ws_url": "..." }
```

`ws_url` 可能是相对路径；`RealtimeApiUrlBuilder` resolve 后把 http→ws / https→wss，
保留 ticket query。打开 WebSocket（ticket 在 query 里，升级不带 Bearer），等
`{"type":"session_ready"}`，发 text 控制 `{"type":"start","model":...,"vad":false,
"silence_duration_ms":1200}`，然后以 BINARY frame 流 PCM16 24kHz mono。finalize 发
`{"type":"commit"}`，收到 `transcript_completed` 后发 `{"type":"stop"}`。

控制消息：

| type | 方向 | 含义 |
|---|---|---|
| `start` | client → server | 开始 live 段 |
| `commit` | client → server | 提交音频，请求 finalize |
| `stop` | client → server | 结束 realtime session |
| `session_ready` | server → client | WS 已就绪 |
| `transcript_delta` | server → client | 增量转写（`text` 或 `content`） |
| `transcript_completed` | server → client | 一轮完整转写 |
| `session_stopped` | server → client | 会话结束 |
| `error` | server → client | 错误文案（`message` 或 `code`） |

`RealtimeMessageParser` 映射：`session_ready` / `speech_started` / `speech_stopped`
→ `Status(Connected)`；`transcript_delta` → `TextDelta(content, isNewResponse=false)`
（空则 null）；`transcript_completed` → `TextDelta(content, isNewResponse=true)`；
`session_stopped` → `Status(Idle)`；`error` → `ErrorEvent(message ?: code ?:
"Unknown websocket error")`；其他 → null。

## 连接测试

`AIBuilderConnectionClient.testConnection(baseURL, token)` 走 OkHttp。**实现选择**：
iOS 版用 GET `/v1/usage/summary` 期望 2xx；经确认可用的 Android 参考用 POST
`/v1/embeddings {"input":"ok"}` 期望 <400。本移植按"复用经确认可用的 Android 机制"
原则采用后者，并在此记录这一处分歧。失败抛 `HttpError` / `Underlying`；
`VoiceFlowClient.testConnection` 把非 `VoiceFlowError` 包成 `Underlying`（对齐 Swift）。

## 关键常量（`RealtimeTranscriptionConfig`）

```text
DEFAULT_MODEL = "gpt-realtime"
SAMPLE_RATE = 24000
CHUNK_DURATION_SECONDS = 0.5
chunkByteSize = 24000            # (SAMPLE_RATE * CHUNK_DURATION_SECONDS).toInt() * 2
REPLAY_CHUNK_SIZE = 240000
MIN_COMMIT_AUDIO_BYTES = 4800    # (SAMPLE_RATE * 0.1).toInt() * 2
HEARTBEAT_INTERVAL_SECONDS = 12
MAX_RECOVER_ATTEMPTS = 5
RECOVER_BACKOFF_BASE_MS = 300
SILENCE_DURATION_MS = 1200
FINALIZE_TIMEOUT_MS = 30000
SESSION_CREATE_PATH = "/v1/audio/realtime/sessions"
COMMIT_MESSAGE = {"type":"commit"}
STOP_MESSAGE = {"type":"stop"}
JSON_MEDIA_TYPE = "application/json; charset=utf-8"
connect / read / write timeouts = 15 / 60 / 60 s
OkHttpClient pingInterval = HEARTBEAT_INTERVAL_SECONDS
```

这些值逐字对齐 iOS 仓库；改任何一个都要同步 iOS 端并更新 `docs/working.md`。

## 测试

JVM 单元测试在 `voiceflowkit/src/test/`（`testOptions.unitTests.isReturnDefaultValues
= true`）。已交付 10 个测试文件：

- `RealtimeApiUrlBuilderTest`（10）— normalize / 拼 url / ws-wss swap / ticket 保留。
- `RealtimeMessageParserTest`（13）— 各 type → event 映射；start 控制消息。
- `Pcm16WavWriterTest`（5）— WAV header roundtrip。
- `AudioChunkCacheTest`（5）— append / readChunk / 越界 / byteCount。
- `VoiceFlowAudioMeteringTest`（5）— RMS → 0..1 level。
- `TranscriptHelpersTest`（11）— delta reducer / finalize resolve / recoverable-error 判定。
- `BulkTranscriptionProgressTest`（6）— bulk accumulator 与 finished-vs-error 顺序修正。
- `StreamCaptionStoreTest`（4）— 双层 caption 状态机 / transient 闪现。
- `VoiceFlowClientStubTest`（11）— `makeStub` 行为。
- `LiveBackendPromptFollowingTest`（1）— live 集成测试，见下。

普通单元测试不依赖网络。`LiveBackendPromptFollowingTest` 是 opt-in 的 live 集成测试：
把 checked-in 的 `voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav`（24kHz
TTS 音频）经 `VoiceFlowClient.transcribe` 喂给真实 AI Builder backend，断言 prompt 确实
到达模型。它消耗 API 额度，靠环境变量 `VOICEFLOW_LIVE_WS=1` + `.env` 里的 token 触发，
默认不跑。封装脚本 `scripts/test_live_integration.sh` 设好 JBR、加载 `.env`、用
`--rerun-tasks` 单独跑这个测试，对齐 iOS 的 `scripts/test_live_integration.sh`。

## 验证要求

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :voiceflowkit:assembleDebug
./gradlew :voiceflowkit:testDebugUnitTest
./gradlew :app:assembleDebug      # 构建参考 app（含未签名 release：:app:assembleRelease）
./gradlew :app:installDebug       # 装到已连接设备/模拟器
```

变更记录写 `docs/working.md`。

## 消费方式

远程消费走 JitPack：从 GitHub tag 构建，无需自建 Maven repo。consumer 在
`settings.gradle.kts`（或 root `build.gradle.kts`）的 repositories 里加 JitPack，再按
`com.github.grapeot:voiceflow-android:<tag>` 引用 —— JitPack 把 group 改写成
`com.github.grapeot`、artifact 取仓库名：

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }

// app build.gradle.kts → dependencies
implementation("com.github.grapeot:voiceflow-android:0.1.0")
```

本地开发（同时改 library 和宿主 app）可用 Gradle composite build 直接替换成本地工程，
不必先发 tag：

```kotlin
// 宿主工程 settings.gradle.kts
includeBuild("../voiceflow-android") {
    dependencySubstitution {
        substitute(module("com.yage:voiceflowkit")).using(project(":voiceflowkit"))
    }
}
```

仓库内的参考 app 不走以上两条，直接 `project(":voiceflowkit")` 依赖同一工程的 library
模块。

## 后续可选

JitPack 发布已配置，是默认远程消费路径（见"消费方式"）；library / 参考 app / 单元测试
/ live 集成测试 / 集成 skill 均已交付。当前没有阻塞性的待办，后续视需要再考虑发到
Maven Central 等中心仓库。
