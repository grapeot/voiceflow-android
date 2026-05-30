# Working Notes

## Changelog

### 2026-05-29（录音中 Resend 作为 websocket 卡死逃生口）

- 对齐 iOS 参考 app：`Resend` 在录音中也可用。点击后先停止本地 `VoiceFlowMicrophone` 并拿到持久化 WAV，取消 live session / heartbeat / event collector，再走已有 bulk transcription 重放音频。
- 已有录音的保存和重发路径保持原语义；只有 `Recording` 状态新增强制重试分支，避免 websocket 卡死时用户只能等待 Stop finalize。

### 2026-05-29（参考 app 落地 + 集成 skill + 完整测试套件 + 发布配置）

这条记录在同日靠后的工作，supersede 下面那条里关于"app 尚未 scaffold / 测试覆盖缺口 /
下一步"的描述 —— 那些状态已经过期。当前真实状态如下。

**参考 app 已落地**（`app/` 模块，`applicationId = com.yage.voiceflow`，Jetpack Compose），
对齐 iOS 仓库 grapeot/voiceflow 的 `src/VoiceFlow/`。结构在 `com.yage.voiceflow` 下：
`MainActivity` + `VoiceFlowApp`（Compose 入口与导航）、`MainViewModel`（状态机）、
`ui/record/RecordScreen`（录音、实时 partial 字幕、`WaveformView` 波形、计时器、历史、
自动复制、保存 WAV、重发、可选 OpenCode push）、`ui/settings/SettingsScreen`（token 经
`EncryptedSharedPreferences` 存储、endpoint、OpenCode 配置、语言、prompt/terms）、
`data/SettingsStore`、`service/OpenCodeClient`、`i18n/`（System / English / 简体中文 的
应用内切换）、`model/`、`ui/components/`、`ui/theme/`。`AndroidManifest.xml` 声明
`voiceflow://record` deep link，资源含 `values/` 与 `values-zh-rCN/` 两套 strings、
`network_security_config`。app 依赖 `project(":voiceflowkit")`，`settings.gradle.kts`
同时 include `:voiceflowkit` 与 `:app`。

**iOS logo 接入为 launcher icon**：把 iOS app 的 logo 复制进
`app/src/main/res/drawable/voiceflow_logo.png`，并据此生成全分辨率
`mipmap-*/ic_launcher{,_round}.png`，让 Android app 与 iOS 视觉一致。

**集成 skill 已交付**：`skills/adding_voice_input_with_voiceflowkit_android.md` ——
给 host AI 看的完整集成指南，对齐 iOS `skills/adding_voice_input_with_voiceflowkit.md`。

**完整测试套件已交付**。`voiceflowkit/src/test/java/com/yage/voiceflowkit/` 下有 10 个
JVM 单测文件：`RealtimeApiUrlBuilderTest`、`RealtimeMessageParserTest`、
`TranscriptHelpersTest`、`Pcm16WavWriterTest`、`AudioChunkCacheTest`、
`BulkTranscriptionProgressTest`、`StreamCaptionStoreTest`、`VoiceFlowAudioMeteringTest`、
`VoiceFlowClientStubTest`，以及一个对接真实后端的 live 集成测试
`LiveBackendPromptFollowingTest`。后者配合 `scripts/test_live_integration.sh`，把签入的
`voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav` 喂进真实后端跑通整条链路，
验证 prompt-following 行为。下面旧条目里"只有 VoiceFlowAudioMeteringTest 实现、其余 8 个
尚未编写"的描述已作废；当时设想的 `RealtimeLiveSessionHandleTest` 和
`RealtimeTranscriptionClientWsTest` 这两个名字最终没有落地，实际多出的是
`BulkTranscriptionProgressTest`、`StreamCaptionStoreTest`、`LiveBackendPromptFollowingTest`。

**三处 bug 修复**：

- 转写永远出中文：移除了把 language 误注入成 prompt 的逻辑（那段把语言偏好当作 prompt
  片段拼进 session-create，导致后端被引导成固定中文输出）。去掉后语言由音频内容决定。
- 应用内语言切换真正生效：改用 `CompositionLocal` 携带选定 locale，配合
  `createConfigurationContext` 重建 resources，使 System / English / 简体中文 的切换
  在不重启 app 的情况下即时刷新整棵 Compose 树。
- 切 tab 时残留旧错误：把错误从常驻状态改成一次性事件（one-shot），消费后即清除，
  避免离开再回到某个 tab 时弹出早已过期的错误。

**发布配置（JitPack）**：library 通过 JitPack 从 GitHub tag 发布，这是公开仓库远程消费
VoiceFlowKit 的主路径。消费方在 `settings.gradle.kts`（或 root `build.gradle.kts`）的
repositories 里加 `maven { url = uri("https://jitpack.io") }`，依赖写
`implementation("com.github.grapeot:voiceflow-android:0.1.0")`（JitPack 把 group 重写为
`com.github.grapeot`，artifact 取仓库名）。本地开发可走 Gradle composite build 作为次要
路径：`includeBuild("../voiceflow-android") { dependencySubstitution { substitute(module("com.yage:voiceflowkit")).using(project(":voiceflowkit")) } }`。

**Release / 发布 APK**：`:app:assembleRelease` 产出的 release APK 用 Android debug key
签名（`buildTypes.release.signingConfig = signingConfigs.getByName("debug")`），因此可
直接安装——这是参考 app，不走 Play Store，故不维护私有上传密钥。`scripts/release.sh
<version>` 是发布的 contract：编译签名 APK、打 tag 并 push（JitPack 据此构建 library）、
用 `gh release create` 发布 GitHub release 并附上 `voiceflow-<version>.apk`。AGENTS.md
的"发布（Release）"节记录了这个流程。

### 2026-05-29（VoiceFlowKit Swift → Kotlin Android 移植 + 仓库结构对齐 iOS）

把 iOS 仓库 grapeot/voiceflow 里的 Swift Package VoiceFlowKit 移植成一个
独立的 Kotlin Android library，并把 Android 仓库的顶层结构对齐 iOS 仓库。

**移植目标**：公开 facade、wire protocol、断线恢复语义以 iOS 仓库的 Swift 源为权威；
语言/平台 idiom 按 Kotlin/Android 习惯翻译。底层机制复用经确认可用的 Android 参考实现
（24 kHz PCM16 mono `AudioRecord` 采集、OkHttp WebSocket、session-create POST、磁盘
cache 重放、recover、commit/stop finalize），不参考更旧、可能有 bug 的 Android 客户端。

**交付的 library**（`voiceflowkit/` 模块，package `com.yage.voiceflowkit`）：

- 公开 facade：`VoiceFlowKit`（VERSION 常量）、`VoiceFlowConfig` + `TokenProvider`
  typealias、`VoiceFlowClient`（suspend funcs + 内部 `Mutex`，`makeStub` 工厂）、
  `VoiceFlowSession`（`events: Flow<VoiceFlowEvent>` + `SessionEventBridge`）、
  `VoiceFlowEvent` / `VoiceFlowConnectionPhase`、`VoiceFlowError`（sealed class :
  `Exception`）、`TranscriptionResult`、`VoiceFlowMicrophone`（`audioLevel:
  Flow<Float>`）、`VoiceFlowAudioMetering`、`StreamCaption` / `StreamCaptionStore`。
- 内部 pipeline（`com.yage.voiceflowkit.internal`，全部标 Kotlin `internal`，不进
  ABI）：`RealtimeTranscriptionConfig`、`RealtimeTranscriptEvent` 类型层、
  `RealtimeMessageParser`、`RealtimeApiUrlBuilder`、`Pcm16WavWriter`、
  `AudioChunkCache`、`AudioChunkEncoder`、`TranscriptHelpers`、
  `RealtimeWebSocketSession`、`RealtimeLiveSessionHandle`、`RealtimeTranscriptionClient`、
  `BulkTranscriptionProgress`、`AndroidAudioRecorder`、`AIBuilderConnectionClient`、
  `MockRealtimeTranscriptionClient`。

**Swift → Kotlin idiom 映射**：`actor` → class + 内部 `Mutex` + `suspend`；
`AsyncStream<VoiceFlowEvent>` → `Flow`（`SharedFlow` 背书，extraBufferCapacity 16）；
`@Sendable () async throws -> String` → `typealias TokenProvider = suspend () ->
String`；enum with associated values → sealed class；`OSLog` → `android.util.Log`
（`loggerSubsystem` 字段移除）；`Codable` → `org.json`；`AVAudioEngine` tap →
`AudioRecord`（无重采样）；`URLSession` WebSocket → OkHttp。

**常量逐字对齐 iOS**：sampleRate 24000、chunkDurationSeconds 0.5、chunkByteSize 24000、
replayChunkSize 240000、minCommitAudioBytes 4800、heartbeatIntervalSeconds 12、
maxRecoverAttempts 5、recoverBackoffBaseMs 300、silenceDurationMs 1200、
finalizeTimeoutMs 30000、默认 endpoint `https://space.ai-builders.com/backend`、默认
model `gpt-realtime`。

**一处实现分歧（已记录）**：`testConnection` —— iOS 版用 GET `/v1/usage/summary` 期望
2xx；经确认可用的 Android 参考用 POST `/v1/embeddings {"input":"ok"}` 期望 <400。本
移植按"复用经确认可用的 Android 机制"原则采用后者，见 `docs/rfc.md` 连接测试章节。

**工程脚手架**：独立 Gradle 工程（自带 wrapper，Gradle 9.3.1）。toolchain 对齐
opencode 参考：AGP 9.1.0、Kotlin 2.2.10、OkHttp 4.12.0、kotlinx-coroutines 1.7.3、
JSON 用平台 `org.json`，测试用 JUnit4 + mockk + okhttp-mockwebserver +
kotlinx-coroutines-test + turbine。无 Hilt、无 Compose、无 Retrofit、无
kotlinx-serialization。`voiceflowkit/src/main/AndroidManifest.xml` 声明 `RECORD_AUDIO`
+ `INTERNET`，host 通过 manifest merge 继承。

**构建期修复**（让库编译并跑通单测）：

- root `build.gradle.kts`：去掉 `alias(libs.plugins.kotlin.android) apply false`。
- `voiceflowkit/build.gradle.kts`：去掉 `org.jetbrains.kotlin.android` plugin（AGP
  9.1.0 内建 Kotlin 支持并注册 `kotlin` extension，再 apply 该 plugin 会冲突 ——
  "Cannot add extension with name 'kotlin'"；opencode 参考同样不声明 `kotlin-android`）；
  去掉失效的 `kotlinOptions { jvmTarget = "17" }`，改用顶层 `kotlin { compilerOptions
  { jvmTarget.set(JvmTarget.JVM_17) } }`。
- `VoiceFlowError.kt`：补上缺失的 `companion object { internal fun
  from(error: RealtimeTranscriptionError): VoiceFlowError }` 边界翻译器（三个调用点
  ——`VoiceFlowClient.startSession` / `transcribe` / `VoiceFlowSession.commitAndStop`
  —— 引用了它但类型层 agent 留给了 facade 层、没人接手）。公开 API 未改，只补了内部
  翻译器。

**构建结果**：`assembleDebug` BUILD SUCCESSFUL，产出 `voiceflowkit-debug.aar`；
`testDebugUnitTest` 绿（`VoiceFlowAudioMeteringTest` 5/5）。系统 `java` 不在 PATH，
构建需先 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`。

**仓库结构对齐 iOS（本条 changelog 所在的工作）**：把 Android 仓库顶层文档结构补齐到
与 iOS 仓库 grapeot/voiceflow 对应：

- `README.md`：重写为 iOS README 的 Android 类比 —— "library + 生成内核"定位、
  Generative Kernel framing、facade 表面表、Swift→Kotlin idiom 映射表、`includeBuild`
  composite build 依赖方式、参考 app 位置说明。
- `AGENTS.md`：Android 工作语言 + 硬性规则的类比（公开表面只含 facade 包、internal
  pipeline 必须标 `internal`、不引 Hilt/Compose、常量对齐 iOS、git 串行、JBR 构建）。
- `docs/prd.md`：库作为生成内核的目标（核心套件 / 引导知识 / 杠杆工具集三分法）、
  与 iOS 版的对应关系表、工作模式、转写上下文、鉴权、失败恢复、关键常量、成功标准。
- `docs/rfc.md`：从 iOS `docs/rfc.md` port，按实际写下的 Kotlin 模块结构调整 —— 模块
  划分树、公开 API、内部架构（transcribing 抽象 + 核心恢复机制）、wire protocol、连接
  测试分歧、常量、测试覆盖现状、验证命令。
- `docs/working.md`：本文件。

未触碰 library 代码、集成 skill 和测试 —— 那些由其他 agent 单独负责。

**测试覆盖缺口（非 blocker）**：设计规划了 9 个单元测试文件，目前只有
`VoiceFlowAudioMeteringTest.kt` 实际编写并通过。其余 8 个（`RealtimeApiUrlBuilderTest`、
`RealtimeMessageParserTest`、`Pcm16WavWriterTest`、`AudioChunkCacheTest`、
`TranscriptHelpersTest`、`RealtimeLiveSessionHandleTest`、`VoiceFlowClientStubTest`、
`RealtimeTranscriptionClientWsTest`）尚未实现。不影响编译或绿色构建，待实现层补齐。

**参考 app 状态**：iOS 仓库有第一方 app（`src/VoiceFlow/`）。Android 这边参考 app
（`app/` 模块）尚未 scaffold。本次刻意不创建空 `app/` 模块 —— 一个未配置的 Android
application 模块会拖累 library-only 的构建/测试预期，且没有源码时价值为零。决定先在
README / PRD / RFC 中记录它应落在 `app/`（对齐 iOS `src/VoiceFlow/`），作为后续步骤。
library 模块独立于任何 app 模块构建与发布。

**下一步**：
1. 补齐 8 个规划单元测试 + MockWebServer 集成测试。
2. 写 Android 版集成 skill（host AI 看的 `adding_voice_input` 手册，对齐 iOS
   `skills/adding_voice_input_with_voiceflowkit.md`）。
3. 需要演示/验收真机行为时再 scaffold `app/` 参考模块。
4. 准备发布到 Maven repository（目前走 `includeBuild` composite build）。
