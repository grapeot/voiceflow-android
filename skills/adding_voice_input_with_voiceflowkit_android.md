# Skill: 给 Android App 加语音输入（用 VoiceFlowKit）

VoiceFlowKit 是一个 Kotlin Android library，把"麦克风录音 + 实时转写"封装成几个类。把它加进任何 minSdk 26+ 的 app 后，你能在 ~50 行 Kotlin 代码内得到一个能录音、能拿到逐字 partial transcript、能在停止时返回最终文本的语音输入组件。Backend 走 wss 到 AI Builder Space（OpenAI gpt-realtime），需要一个 token。这是 iOS VoiceFlowKit（grapeot/voiceflow）的 Android 平移，公开 API 一一对应。

## 元数据

- **类型**: API Guide / Tutorial
- **适用场景**: 想在 Android app 里加"按住说话 → 出文字"或"按一下开始 / 再按一下停止 → 出文字"的功能
- **依赖**: 通过 JitPack 从 GitHub tag 引入（`com.github.grapeot:voiceflow-android`），minSdk 26，Kotlin + OkHttp + kotlinx-coroutines。本地开发也可以用 Gradle composite build 直接指向 checkout 的 repo
- **不适用**: 离线转写（一律走 WebSocket）、非实时长录音批处理（`transcribe(wavFile)` 返回 `TranscriptionResult`，能跑短文件，但 V0 只支持 WAV 输入）、没有麦克风的设备
- **更新日期**: 2026-05-29

## 这个 skill 让你完成什么

读完之后，你能给一个现有的 Android app 加一个 voice input 组件，达成：

1. 用户在 Settings 里填一个 AI Builder Space token（你负责存 EncryptedSharedPreferences/DataStore，库不管）
2. 录音 UI 上有一个 button：第一次按 → 开始录音 + 显示 partial transcript；第二次按 → 停止录音 + 等几秒拿到 final transcript
3. 转写文本流向你的 EditText / Compose state / 聊天 input

整个组件应该 ≤200 行 Kotlin（不算 UI 样式）。写到 500 行还没跑通，说明走偏了，看下面的"参考实现"对照。

## Library 的形状

artifactId 是 `voiceflowkit`，package root `com.yage.voiceflowkit`。对外暴露这几个类型（internal pipeline 在 `com.yage.voiceflowkit.internal`，对你不可见）：

| 类型 | 干什么 |
|---|---|
| `VoiceFlowConfig` | data class：endpoint + `tokenProvider: suspend () -> String` + 可选 prompt/terms。一个 config = 一次 session 的参数 |
| `VoiceFlowClient` | `config` 给它 → 它给你 `VoiceFlowSession`（live）或一次性 `transcribe(wavFile)`（返回 `TranscriptionResult`）。suspend 方法，内部 Mutex 串行化 config |
| `TranscriptionResult` | data class：`text`（转写文本）+ `requestId`（每次请求一个 UUID，用于关联日志）。`transcribe(wavFile)` 的返回值 |
| `VoiceFlowSession` | 一次 live 录音会话。`sendAudioChunk` 喂 PCM、`ping` 保活、`commitAndStop` 拿 final、`cancel` 中止并清理缓存、`abortPreservingAudio` 中止但保留已录 PCM；`events: Flow<VoiceFlowEvent>` 拿 partial transcript 和连接相位 |
| `VoiceFlowPreservedAudio` | `abortPreservingAudio()` 返回的轻量句柄。公开 `id` / `byteCount`，可交给 `VoiceFlowClient.transcribe(preservedAudio)` 重试识别，完成后用 `discardPreservedAudio` 清理 |
| `VoiceFlowMicrophone` | 录音入口（构造要 `Context`）：`hasPermission()` → `start(onPCMChunk:)` → `stop()`。onPCMChunk 推 PCM16 24kHz mono chunk，直接转给 session。`audioLevel: Flow<Float>` 是 0..1 的 EMA 平滑电平，画波形用 |
| `VoiceFlowEvent` | sealed class：`PartialTranscript(text)` / `PhaseChanged(phase)` / `RecoveryStarted` / `RecoveryFailed(message)` |
| `VoiceFlowConnectionPhase` | enum：`Connecting / Connected / Recovering / Generating / Disconnected` |
| `VoiceFlowError` | sealed class : Exception：`MissingToken / InvalidEndpoint / HttpError(statusCode) / SessionUnavailable / WebsocketError(detail) / ConnectionLost(detail) / EmptyTranscript / MicrophoneUnavailable / AudioConversionFailed / Underlying(detail)` |
| `VoiceFlowClient.makeStub(...)` | companion factory。返回不开 WebSocket 的 stub client，行为完整（emit connected → idle、`commitAndStop` 返回 canned 文本）。给 instrumented test 和 preview 用 |

工作模式只有两种：

- **Live streaming**（推荐，默认）：`client.startSession()` → 边录音边收 partial → `session.commitAndStop()` 拿 final。延迟低，体验好。
- **Bulk**：`client.transcribe(wavFile)` 一次性传一个 WAV，返回 `TranscriptionResult`（读 `.text` 拿转写、`.requestId` 做关联）。签名是 `suspend fun transcribe(wavFile: File, onPartialTranscript: ((String) -> Unit)? = null): TranscriptionResult`，可选的 `onPartialTranscript` 回调能让你在转写过程中拿到中间结果。VoiceFlow 内部用它做"resend"——网络断了之后拿持久化录音重传。
- **Preserved retry**：live session 卡住或用户主动终止时，`session.abortPreservingAudio()` 关闭 WebSocket 但保留 session 内部磁盘 PCM；之后 `client.transcribe(preservedAudio)` 用同一段 PCM 重新识别，host 不需要自己复制 mic chunk。

## 集成步骤

### 1. 加 Gradle 依赖

库通过 JitPack 从 GitHub tag 发布，这是推荐的远程引入方式。先在你的 `settings.gradle.kts`（或 root `build.gradle.kts`）的 repositories 里加上 JitPack：

```kotlin
// settings.gradle.kts（你的 app）
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

然后在 app module 的 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.github.grapeot:voiceflow-android:0.1.0")
}
```

JitPack 会把 group 重写成 `com.github.grapeot`、artifact 用 repo 名（`voiceflow-android`），版本就是 GitHub tag。

如果你在本地同时 checkout 了这个 repo（开发或想直接改库代码），可以改用 Gradle composite build 指向本地目录，对应 iOS 的 SPM branch reference：

```kotlin
// settings.gradle.kts（你的 app）
includeBuild("../voiceflow-android") {
    dependencySubstitution {
        substitute(module("com.yage:voiceflowkit"))
            .using(project(":voiceflowkit"))
    }
}
```

走 composite build 时 app module 直接 `implementation("com.yage:voiceflowkit")` 即可。toolchain 对齐：AGP 9.1.0、Kotlin 2.2.10、OkHttp 4.12.0、coroutines 1.7.3。

### 2. 麦克风权限

库的 manifest 已经 merge 了 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`，所以你不用再声明。但 Android 的 runtime 授权只能由 Activity 触发，库做不了。在你的 Activity 里用 `ActivityResultContracts.RequestPermission` 申请：

```kotlin
private val requestMicPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else showMicDeniedHint()
    }

private fun onMicButtonTapped() {
    if (microphone.hasPermission()) {
        startRecording()
    } else {
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

`VoiceFlowMicrophone.requestPermission()` 只是 `hasPermission()` 的别名（为了和 iOS API 对齐），它**不会弹窗**。弹窗永远走上面的 ActivityResult。

### 3. 写一个 client factory

把 token、endpoint、prompt、terms 包成 `VoiceFlowConfig`，再构造 `VoiceFlowClient`。token 默认从你 app 自己的存储里读。

```kotlin
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowError

private fun makeVoiceFlowClient(): VoiceFlowClient {
    val token = aiBuilderToken.trim()
    if (token.isEmpty()) throw VoiceFlowError.MissingToken

    val config = VoiceFlowConfig(
        endpoint = VoiceFlowConfig.DEFAULT_ENDPOINT,   // 或你自己的 base URL
        tokenProvider = { token },                     // suspend () -> String
        prompt = customPrompt.ifEmpty { null },
        terms = termList,                              // List<String>
    )
    return VoiceFlowClient(config)
}
```

`tokenProvider` 是 `suspend () -> String`。想每次都重新从存储读（让用户清 token 时立刻生效），让 lambda 自己读；想整个 session 用同一个 snapshot，capture 一个本地变量（推荐）。lambda 内可以做 suspend IO（比如读 DataStore），库会在拿 token 时 await 它。

### 4. 写录音 + 转写主流程

最小版本（适合 chat composer 类 UI，跑在一个有 `lifecycleScope` 的 Activity/Fragment 或 ViewModel 里）：

```kotlin
private val microphone = VoiceFlowMicrophone(applicationContext)
private var session: VoiceFlowSession? = null
private var eventJob: Job? = null
private var heartbeatJob: Job? = null

private fun startRecording() {
    lifecycleScope.launch {
        val client = try { makeVoiceFlowClient() } catch (e: VoiceFlowError) { showError(e); return@launch }
        val session = client.startSession()   // 可能抛 VoiceFlowError
        this@MyActivity.session = session

        // 1) 先起 collector 消费 partial transcript + 相位变化
        eventJob = launch {
            session.events.collect { event ->
                when (event) {
                    is VoiceFlowEvent.PartialTranscript -> partialTranscript = event.text
                    is VoiceFlowEvent.PhaseChanged -> updatePhaseUi(event.phase)
                    VoiceFlowEvent.RecoveryStarted -> showHint("reconnecting…")
                    is VoiceFlowEvent.RecoveryFailed -> { showHint("connection lost"); stopRecording() }
                }
            }
        }

        // 2) Mic 把 PCM chunk 喂给 session
        microphone.start(persist = false) { chunk ->
            launch { session.sendAudioChunk(chunk) }
        }

        // 3) 12 秒心跳保活（避开 WS idle timeout）
        heartbeatJob = launch {
            while (isActive) {
                delay(12_000)
                runCatching { session.ping() }
            }
        }
    }
}

private fun stopRecording() {
    heartbeatJob?.cancel(); heartbeatJob = null
    val session = this.session ?: return
    this.session = null
    lifecycleScope.launch {
        microphone.stop()
        val finalText = try {
            session.commitAndStop { partial -> partialTranscript = partial }
        } catch (e: VoiceFlowError) {
            session.cancel(); showError(e); null
        }
        eventJob?.cancel(); eventJob = null
        finalText?.let { applyTranscript(it) }
    }
}
```

如果你的 host UI 需要一个"强制终止语音识别 / 重试上一段录音"按钮，使用 preserved retry，不要在 app 侧重复缓存音频 chunk：

```kotlin
private var preservedAudio: VoiceFlowPreservedAudio? = null

private fun abortSpeechRecognition() {
    heartbeatJob?.cancel(); heartbeatJob = null
    val session = this.session ?: return
    this.session = null
    lifecycleScope.launch {
        microphone.stop()
        preservedAudio = session.abortPreservingAudio()
    }
}

private fun retryPreservedAudio() {
    val preserved = preservedAudio ?: return
    lifecycleScope.launch {
        val client = makeVoiceFlowClient()
        val result = runCatching { client.transcribe(preserved) }.getOrNull()
        client.discardPreservedAudio(preserved)
        preservedAudio = null
        if (result != null) inputText = result.text
    }
}
```

这 ~50 行就是核心。`events` 是 SharedFlow（buffer newest 16），跟 iOS 的 `AsyncStream.bufferingNewest(16)` 对齐——**必须在 mic 开始前/同时起 collector**，否则最早的 partial 会被丢。`sendAudioChunk` 不会因为网络抖动抛错（库内部 recover + cache replay），只在 session 已 cancel 或磁盘缓存写失败时抛。

### 5. Test / preview 用 stub client

instrumented test 或 debug build 里通常会有个 flag。检测到时，把 prod 的 `VoiceFlowClient(config)` 换成 `VoiceFlowClient.makeStub(...)`。Stub 不开 WebSocket，`commitAndStop` 直接返回 `liveTranscript`，`events` 按 connected → idle emit。跑得快，不依赖网络和 token。

```kotlin
private fun makeClientForTestsOrProd(): VoiceFlowClient =
    if (BuildConfig.UI_TEST_MODE) {
        VoiceFlowClient.makeStub(liveTranscript = "Mock transcription")
    } else {
        makeVoiceFlowClient()
    }
```

## 验收标准

- [ ] 用户在 Settings 填 token 并存到加密存储（你的代码，库不管）
- [ ] 按一下 mic button，没权限时弹系统授权框；Allow 之后开始录音
- [ ] 录音时说一句话，partial transcript 实时显示在 UI 上
- [ ] 再按一次停止，1-3 秒内 final transcript 出现，替代或追加到 input
- [ ] 录音中途断网再恢复，UI 不崩；断 < 5 秒自动 recover 继续，> 5 秒 emit `RecoveryFailed`，你的 UI 提示并清理
- [ ] 录音中切后台再回前台仍能正常 finalize（保守做法：在 `onStop()` 调 `session.cancel()`）
- [ ] token 错或 endpoint 错时，`startSession`/`commitAndStop` 抛 `VoiceFlowError.MissingToken` / `.InvalidEndpoint` / `.HttpError(statusCode)`，UI 展示对应错误
- [ ] stub 路径跑过：`makeStub()` 不起 WebSocket，`commitAndStop` ~100ms 内返回 stub 文本

## 已知陷阱

| 陷阱 | 表现 | 应对 |
|---|---|---|
| `events` collector 起晚了 | 录音正常，partial transcript 不显示 | collector 必须在 mic start **之前或同时**起，不能等 chunk 已经在飞之后才起 |
| 忘了 ping | 录音超过 15-20 秒就 disconnect | 起一个 12 秒间隔的 heartbeat coroutine，stop 时记得 cancel |
| token 是空字符串 | `startSession` 抛 `MissingToken` | `tokenProvider` lambda 先 trim 再 return；空字符串等价于 missing |
| 在主线程调 suspend 录音 API | ANR 风险 | 所有 `start/stop/sendAudioChunk/commitAndStop` 在 coroutine 里调；chunk 回调本身在 IO dispatcher |
| 没申请 RECORD_AUDIO runtime 授权 | `microphone.start()` 抛 `MicrophoneUnavailable` | 用 `hasPermission()` gate UI，授权走 Activity 的 `ActivityResultContracts.RequestPermission` |
| endpoint 没有 scheme | `startSession` 抛 `InvalidEndpoint` | URL string 前面带 `https://`；默认用 `VoiceFlowConfig.DEFAULT_ENDPOINT` |
| `RecoveryFailed` 之后还在 send audio | session 静默丢弃，UI 看上去"卡住" | 收到 `RecoveryFailed` 就 stop mic 并 `session.cancel()` |
| chunk 太大（>1s） | partial 间隔突然变长 | 库内部不要求精确 chunk 大小，但别在 onPCMChunk 里自己缓冲；`VoiceFlowMicrophone` 默认 chunk 已经合适 |
| 切后台没处理 | 回前台后 session 状态不定 | 保守做法在 `onStop()` 调 `session.cancel()`，回前台重新 `startSession()` |

## 边界

库不做的事：UI（mic button、状态指示、错误提示你自己画）、token 存储（你用加密存储传给 `tokenProvider`）、prompt/terms 输入控件（库只接受最终 `String` / `List<String>`）、backend 选择（默认 `https://space.ai-builders.com/backend`，换 backend 要确认对方实现同样 wire 协议：POST `/v1/audio/realtime/sessions` 拿 ticket，wss 走 PCM16 24kHz mono）。

库管的事：WebSocket 连接/重连/ticket flow、PCM16 24kHz mono 编码 + chunk 序列化、partial transcript 累积合并、commit 后 finalize 等待（含 2 次 retry）、bulk WAV 转写、caption 双层状态（`StreamCaption` / `StreamCaptionStore`，想要"reconnecting…"提示时用）、audioLevel 电平计算（`VoiceFlowAudioMetering.normalizedLevel`）。

## 参考实现

想看一个把同样 pipeline 装在 chat composer 上的完整实现，参考公开仓库 grapeot/opencode_android_client（一个 chat app，按 mic 录音 → 转写完进 input field）。它就是这个库内部 pipeline 的来源、确认能跑通的 Android 实现。相关文件在 `app/src/main/java/com/yage/opencode_client/data/audio/`：`RealtimeSpeechStreamer.kt`（live session + recovery 主流程）、`AudioRecorderManager.kt`（AudioRecord 24kHz PCM16 mono 捕获）、`AIBuildersAudioClient.kt`（session-create POST + WS）。你的场景如果是 chat input，照它抄最快。

iOS 端对照参考 iOS VoiceFlow 仓库 grapeot/voiceflow（https://github.com/grapeot/voiceflow）的 `skills/adding_voice_input_with_voiceflowkit.md`——Kotlin API 和 Swift 一一对应，actor → 带 Mutex 的 suspend class，`AsyncStream` → `Flow`，enum with associated values → sealed class。

## 接下来

- 自定义 prompt 让模型输出更有结构（全大写、bullet 形式）：见 `docs/working.md` 的 "prompt-following" 段。`gpt-realtime` 对**短指令型** prompt 反应弱，对**指令 + Example** 形态响应强。
- 想画实时波形：collect `microphone.audioLevel: Flow<Float>`（0..1，已 EMA 平滑），直接驱动一个 bar/wave view。
