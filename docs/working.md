# Working Notes

## Changelog

### 2026-05-30（Start Record launcher shortcut）

**改动**：参考 app 新增 Android static shortcut，`shortcutId=start_record`。用户从 launcher 长按 VoiceFlow 可直接选择 Start Recording；shortcut 复用现有 `voiceflow://record` deep link 和 `MainActivity.handleDeepLinkIntent()` 路径，不新增第二套录音入口。

**验证**：资源编译随 `:app:assembleDebug` 覆盖；录音行为继续由现有 deep link 处理逻辑负责。

### 2026-05-30（OpenCode connection test 持久化）

**问题**：Android 参考 app 和 iOS 一样，只把 OpenCode connection test 的成功状态留在内存里的 `UiState.openCodeConnectionStatus`。App 重启后回到 `Untested`，导致已经配置好的 OpenCode 仍然要重新点 Test 才能发送。

**改动**：`SettingsStore` 新增 plain SharedPreferences 标记 `opencode_connection_verified`。`testOpenCodeConnection()` 成功后写 true；失败、保存/清除密码、修改 URL 或用户名时写 false。`refreshSettingsState()` 启动时在密码存在、URL/用户名非空且标记为 true 的情况下直接恢复 `ConnectionTestStatus.Success`。

**验证**：与 iOS 语义对齐；编译和 JVM 测试见本次 PR 验收记录。

### 2026-05-30（救援：保存 / 重发录音始终可用，不被卡住的转写锁死）

**问题**：用户 Stop 后转写偶尔卡死，状态停在 `Transcribing`。此时三点菜单里"保存录音"
和"重发录音"被灰掉，用户连把已经录好的音频抢救出来都做不到。

**根因**：`UiState.canSaveRecording = canNavigateTranscriptHistory && hasRecordingFile`，
而 `canNavigateTranscriptHistory` 只在 `Idle || Ready` 为 true（语义是"非录制中，可做
历史导航"）。卡在 `Transcribing` 时它为 false，把 save 灰掉；`canResendRecording` 又
依赖 `canSaveRecording`，被连累一起灰。

**搞清 `canNavigateTranscriptHistory` 隐含什么**：它 = `recordingStatus == Idle ||
Ready`，唯一对 save 有意义的"必要前提"是"不在录音中"（录音中 WAV 还没落盘，不该保存）。
但这个前提已经被 `hasRecordingFile` 覆盖——它只在 Stop 成功落盘 WAV 后置 true
（MainViewModel:618），新会话开始即清掉（:557），所以录音进行中恒为 false。结论：去掉
`canNavigateTranscriptHistory` 对 save 无害，纯靠 `hasRecordingFile` 门控既能在
Transcribing/卡死时放开，又不会在录音中误开。

**改动**：
- `UiState.canSaveRecording = hasRecordingFile`（去掉 `canNavigateTranscriptHistory`）。
- `UiState.canResendRecording = hasToken && (recordingStatus == Recording ||
  hasRecordingFile)`（对齐，不再经 `canSaveRecording` 间接依赖导航门）。
- `MainViewModel.resendLastRecording`：非 Recording 分支去掉
  `if (!snapshot.canNavigateTranscriptHistory) return` 守卫，只要 WAV 存在就强制重转
  （状态打回 Transcribing，关 WS、用已落盘 WAV 重走 `finishTranscriptionFromLastRecording`，
  替换卡住的 in-flight 尝试）。重转核心逻辑未动。
- `RecordScreen` 重发菜单项去掉 `&& state.recordingStatus != RecordingStatus.Transcribing`
  的额外禁用，直接绑 `canResendRecording`——卡在 Transcribing 正是要放开的场景。

**验证**：新增 `RescueGatingTest`（app/src/test），构造 `UiState` 覆盖：Transcribing +
hasRecordingFile 时 save/resend 为 true、无文件 save 为 false、resend 缺 token 为 false、
Recording 中 resend 为 true、正常 Ready 流程 save/resend 仍 true。`./gradlew
:app:assembleDebug :app:testDebugUnitTest` 通过。与 iOS 行为对齐。

### 2026-05-30（Stop→finalize 打字机效果：逐 delta 显示）

参考 app 的转写在 stop 后会"一把出现"整段文字，而不是逐字打出来。修掉它，让 finalize
阶段做到真打字机：来一个 delta 显示一个 delta。

**设计决策（必须保留的前提）**：录音时音频实时发到远端，但**故意不让远端输出**转写
（零散的实时语音会拉低识别质量，这是有意的取舍）。用户 Stop 之后才发指令让远端
output，然后逐个 delta 推进显示。因此 `handleStreamEvent` 里 `recordingStatus ==
Recording` 时丢弃 `PartialTranscript` 的门必须保留；VoiceFlowKit 库和控制指令一律不动。

**根因：StateFlow conflation**。`RealtimeLiveSessionHandle` 在 finalize 阶段每个 delta
确实逐个回调，但 app 层 `updateTranscriptDuringFinalize`（MainViewModel）直接
`_state.update { transcript = partial }`，把每个快照推进一个 conflating 的
`MutableStateFlow`。加上 finalize callback 来自多个 `Dispatchers.IO` 协程的突发写入，
Compose 跟不上 —— 中间快照被合并吞掉，最终只渲染最后一帧，表现为"一把出现"。

**修复：non-conflating channel-drain 打字机管线**（全在 `MainViewModel`，不碰库）：
- 新增 `applyStreamedTranscript(current, incoming)` 纯函数做快照 reconciliation：
  内容相同 → 返回 `current` 同一实例（让消费者 skip recomposition）；current 为空或
  incoming 是前缀增长 → 返回 incoming（append）；backend 重新分段、非前缀 → 返回
  incoming（replace）。top-level、无框架依赖，可被单测直接调用。
- 新增 `finalizeTranscriptChannel: Channel<String>`。`updateTranscriptDuringFinalize`
  改为 `trySend(partial)`；没有活跃管线时 defensive 兜底直接写 state，clipboard
  throttling 保留。
- `startFinalizeTypewriter()` 开一个 **fresh `Channel.UNLIMITED`**（绝不能用
  CONFLATED，否则又丢中间值），在 `viewModelScope.launch(Dispatchers.Main)` 里单消费者
  循环：每取一个 snapshot 应用 `applyStreamedTranscript` 再 `delay(12)`（12ms 一帧）。
- 生命周期：`finishLiveTranscriptionSession` 顶部调 `startFinalizeTypewriter()`（先
  teardown 旧管线，每次 stop 干净开始）。写最终文本前调 suspend
  `drainFinalizeTypewriter()` —— `close()` channel 让消费者把排队快照全部 drain 完，
  再 `join()` 那个 job（**不是 cancel**，避免动画被截断后被整段覆盖）。
  `completeStopTranscriptionSuccess/Failure` 调 `stopFinalizeTypewriter()` 防御性
  teardown；`onCleared` 也 teardown 防泄漏。

**测试**：`app/src/test/java/com/yage/voiceflow/FinalizeTypewriterTest.kt` 覆盖
`applyStreamedTranscript` 的 append/同实例/重分段/空起步四种语义，以及用 ViewModel 同款
原语（UNLIMITED channel + 单消费者 + `MutableStateFlow`）证明每个 delta 都按序成为独立
state 值、重复快照不产生多余 churn。`app/build.gradle.kts` 加上
`testImplementation(libs.junit)` 与 `testImplementation(libs.kotlinx.coroutines.test)`。

**验证**：`./gradlew :app:assembleDebug` 编译通过；`./gradlew :app:testDebugUnitTest`
全绿（`FinalizeTypewriterTest` 6 个用例全过）。均用 Android Studio JBR 作 JAVA_HOME。

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

---

## Pixelate 视觉设计语言落地（参考 app）

把 "Pixelate（像素即纪律）" 设计语言落到参考 app（`app/` 模块，`com.yage.voiceflow`），与 iOS 严格对齐（详见新建的 `docs/design.md`）。这是一次纯视觉改造，不动协议/恢复/库逻辑。

**字体**
- 引入 Silkscreen 像素字体（`res/font/silkscreen.ttf` / `silkscreen_bold.ttf`，OFL 1.1，license 在 `assets/licenses/silkscreen_OFL.txt`）。`DesignTokens.Pixel` 新增 timer/caption/button 像素 TextStyle。
- 混合字体策略：像素字只用于计时器 / 英文状态 / 英文按钮标签；转写正文 + 所有中文走系统字体（CJK 自动 fallback）。

**组件**
- `WaveformView`：bar 36→15、去圆角；每个 bar 改成小像素格堆叠（cell 5.5dp + gap 1.75dp），双向对称，中线留缝。保留 Idle/Active/Generating 三态动画。
- `CapsuleButton`：形状从 CircleShape 换成 `PixelRoundedShape`（3 级方块阶梯角）；录音按钮只留文字标签、不放图标。
- `PixelTabIcon`（新）：mic/gear 7×7 像素网格 Canvas 自绘，替换 Material 矢量；`VoiceFlowApp` 两个 tab 用它。
- `RecordScreen` / `StatusText`：计时器/状态/按钮标签接上像素字。

**App icon / logo**：换成像素语音气泡+波形标记（琥珀单色近黑底），全 6 档 mipmap launcher（含 round）。

**Bug 修复**：波形像素格中线处曾两块并在一起没缝——上下两半各从中线推半个 gap 修复。

**验证**：`./gradlew :app:assembleDebug` 通过（JBR JAVA_HOME）；`:app:testDebugUnitTest` NO-SOURCE 真空通过。模拟器 emulator-5554 截图逐项确认：像素计时/状态、像素方块波形（中线有缝）、像素阶梯角录音按钮（纯文字）、像素 Tab mic/gear、新 app icon。用户在模拟器上确认界面与图标均正确。
