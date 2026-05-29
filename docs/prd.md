# VoiceFlowKit (Android) PRD

## 目标

这个 repo 交付一个产品：**VoiceFlowKit (Android)** —— 一个面向 AI 集成方的语音输入
"生成内核"，是 iOS 仓库 `voiceflow` 里 Swift Package 的 Android 移植。它让任何
Android app（minSdk 26）的 host AI agent 通过读一份集成指南，就能给宿主 app 加上
"按一下录音 → 出文字"的能力。

iOS 仓库 grapeot/voiceflow 同时承载一个第一方 app（VoiceFlow）。Android 这边对齐
这一结构：除 library 外，还交付一个 Jetpack Compose 参考 app（`app/` 模块），是 iOS
`src/VoiceFlow/` 的 Android 对应。本节先讲 library 作为生成内核的设计哲学，参考 app
的功能范围单列在"参考 app（`app/` 模块）"一节。

### VoiceFlowKit 作为生成内核的目标

VoiceFlowKit 按 [generative kernel](https://yage.ai/ai-software-engineering.html)
的思路设计：交付物不是一个隐藏底层细节的"开箱即用 SDK"，而是一套让宿主 AI 在用户 app
里高质量"自己组装"语音输入能力的内核。

按那篇文章里的三分法对应：

- **核心套件**：`com.yage.voiceflowkit` 包下的 facade（`VoiceFlowClient` /
  `VoiceFlowSession` / `VoiceFlowMicrophone` / `VoiceFlowConfig` / `VoiceFlowError`
  等）。这是宿主 AI 没办法在 prompt 里"现编"出来的底层 —— WebSocket ticket flow、
  PCM16 编码、partial transcript 合并、recover 重连、finalize 重试。
- **引导知识**：面向 host AI 的集成指南，已交付在
  `skills/adding_voice_input_with_voiceflowkit_android.md`，对齐 iOS 仓库
  grapeot/voiceflow 的 `skills/adding_voice_input_with_voiceflowkit.md`。内容覆盖
  集成流程、验收标准、已知陷阱、reference 在哪读。指南是一等公民，跟代码一起 ship、
  一起 review、一起更新。
- **杠杆工具集**：`VoiceFlowClient.makeStub(...)`（offline stub client，让 host 的
  仪器测试不依赖网络也能跑通）、`VoiceFlowAudioMetering.normalizedLevel(pcm16le)`
  （host 想自己接 `AudioRecord` 而不用我们的 mic，也能算出一致的 0..1 level）、
  `StreamCaption` / `StreamCaptionStore`（双层 caption 状态机给 host 做
  "reconnecting…" / "stream restored." 这种 UX）。这些都是"AI 在概念上能想到，但
  自己实现繁琐易错"的部分。

为了让 host AI 能可靠组装，设计上有几条硬约束：

- **错误透传，不包装**：`VoiceFlowError` 把 HTTP status code、WebSocket detail、
  底层 reason string 都直接 surface 出来。Host AI 看到 `HttpError(statusCode = 401)`
  立刻知道是 token 问题，看到 `ConnectionLost("...")` 立刻知道连接阶段就挂了 —— 不是
  抛一个泛化的 `apiFailure`。`VoiceFlowError` 是 `Exception` 的 sealed 子类，可以直接
  `catch` + `when` 模式匹配。
- **暴露细粒度控制**：`VoiceFlowSession` 把 `sendAudioChunk` / `ping` /
  `commitAndStop` / `cancel` / `connectionPhase` / `events` 全部公开。Host 如果想用
  自己的 `AudioRecord` 不用 `VoiceFlowMicrophone`、想做自定义心跳 cadence、想消费
  partial transcript 同时还想自己监听 phase change，都不需要 hack。
- **DI-agnostic**：库不依赖 Hilt / Compose，只暴露普通构造函数和工厂函数。内部
  pipeline 全部 `internal`，不进 published ABI，库内部可以自由演进。

成功标准：一个完全没看过这个 repo 的 host AI agent，读完集成指南之后，能在 host 的
Android app 里加完整可用的语音输入 button，单次 prompt 内完成（不需要回头查 kit 源码、
不需要 trial-and-error）。如果做不到，指南还不够好。

## 与 iOS 版的对应关系

Android 版的产品边界刻意与 iOS 版 library 部分对齐 —— 同一个 backend、同一套 wire
protocol、同一套常量、同一套断线恢复语义。差异只在语言/平台 idiom：

| 维度 | iOS (Swift) | Android (Kotlin) |
|---|---|---|
| 并发原语 | `actor` | class + 内部 `Mutex` + `suspend` |
| 事件流 | `AsyncStream<VoiceFlowEvent>` | `Flow<VoiceFlowEvent>`（`SharedFlow` 背书） |
| token 提供 | `@Sendable () async throws -> String` | `typealias TokenProvider = suspend () -> String` |
| 错误模型 | enum with associated values | sealed class : `Exception` |
| 录音 | `AVAudioEngine` tap | `AudioRecord`（24 kHz PCM16 mono，无重采样） |
| WebSocket | `URLSession` | OkHttp `WebSocket` |
| 日志 | `OSLog` subsystem | `android.util.Log`（`loggerSubsystem` 字段移除） |
| JSON | `Codable` | `org.json`（对齐经确认可用的 Android 参考实现） |

平台支持：minSdk 26，compileSdk 35，Java/Kotlin target 17。`AudioRecord` 直接交付
24 kHz PCM16 mono，live mic 路径不需要重采样（重采样只在文件导入时才需要，V0 不做）。

## 工作模式

库支持两种工作模式，对齐 iOS 版：

1. **Live streaming**（推荐，默认）：`client.startSession()` → 边录音边收 partial →
   `session.commitAndStop()` 拿 final。Latency 低，体验好。
2. **Bulk**：`client.transcribe(wavFile)` 一次性转写已有 WAV 文件，走同一条 WebSocket
   协议（无实时 sleep），返回 `TranscriptionResult(text, requestId)`。

## 转写上下文（prompt + terms）

`VoiceFlowConfig` 接受可选的 `prompt: String?` 和 `terms: List<String>`，随每个
session-create 请求的 POST body 一起发到 backend：

```http
POST {endpoint}/v1/audio/realtime/sessions
Authorization: Bearer {token}
Content-Type: application/json

{
  "model": "gpt-realtime",
  "vad": false,
  "silence_duration_ms": 1200,
  "prompt": "...optional...",
  "terms": ["...", "..."]
}
```

空字符串和空列表 trim 后视为未设置，wire 上不出现这个 key。库内部用
`RealtimeSessionContext(prompt, terms)` 在 `beginLiveSession` 和 `transcribeBulkPcm`
两条路径间传递这两个值。

**模型 prompt-following 行为**：`gpt-realtime` 对纯指令型 prompt 反应弱，对"指令 +
Example"形态响应强。这是模型行为不是 library 问题，host 在 UI 里给用户的 placeholder
应体现这一点。

## 鉴权模型

AI Builder Space 使用 Bearer token。token 由 host 通过 `VoiceFlowConfig.tokenProvider`
（一个 `suspend () -> String`）提供；库本身不持久化、不存 token，每次需要鉴权时调用
provider 拿最新值，trim 后为空则抛 `MissingToken`。请求带 `Authorization: Bearer
<token>`。token 不进日志或错误文案。

## Endpoint 策略

默认固定 AI Builder base：

```text
https://space.ai-builders.com/backend
```

Host 可通过 `VoiceFlowConfig.endpoint` 换成兼容 backend。`RealtimeApiUrlBuilder`
负责 normalize base、拼 API path、把 http→ws / https→wss 并保留 ticket query。

## 失败恢复（产品要求）

对齐 iOS 版与经确认的 Android 参考实现：

- **send / ping 失败**：磁盘 cache 静默重连并重放全部已录 PCM（不对齐时间轴 sleep）；
  录音中不更新转写区；通过 `RecoveryStarted` / `PhaseChanged(Recovering)` 事件提示。
- **录音中连接未就绪**：先开 mic + 磁盘 cache，WebSocket deferred attach（不阻塞录音）；
  cache 累积后按 `REPLAY_CHUNK_SIZE` bulk 重放。
- **server error / 持久重连失败**：录音中 recoverable 的 "buffer too small" error 被
  库内部过滤掉；重试耗尽则 `phase=Disconnected` + emit `RecoveryFailed`。
- **finalize**：等待 recover 完成后再 commit；30s 超时；带 2 次重试
  （`preserveForRetry` / `restoreAfterRetry`）；resolved 为空映射为 `EmptyTranscript`。
- **正常结束**（connected/generating → idle）：返回最终 transcript。
- **bulk resend**：走 `transcribeBulkPcm`，不按录音时长做实时重放。

## 关键常量

| 常量 | 值 |
|---|---|
| sampleRate | 24000 |
| chunkDurationSeconds | 0.5 |
| chunkByteSize | 24000（= 24000 * 0.5 * 2） |
| replayChunkSize | 240000 |
| minCommitAudioBytes | 4800（= 24000 * 0.1 * 2） |
| heartbeatIntervalSeconds | 12 |
| maxRecoverAttempts | 5 |
| recoverBackoffBaseMs | 300 |
| silenceDurationMs | 1200 |
| finalizeTimeoutMs | 30000 |

这些值与 iOS 仓库 `RealtimeTranscriptionConfig` 一致，不得擅自改动。

## 参考 app（`app/` 模块）

参考 app 是 library 的消费方示范，包名 `com.yage.voiceflow`，是 iOS 仓库
grapeot/voiceflow 里 VoiceFlow app 的 Android 对应。它只通过公开 facade 用
VoiceFlowKit，不碰 internal 包，目的是给 host AI 一个"长什么样算用对了"的可运行参照，
同时本身就是一个能用的语音输入工具。

应用只有两个 tab，对齐 iOS。Record tab 提供开始/停止录音、录音状态与波形
（`WaveformView`）、实时 partial transcript 显示、转写完成后自动复制到剪贴板、左右
chevron 浏览最近历史、手动复制、三点菜单里的保存录音（导出 WAV 到 app 外部目录）与重发
录音。历史只存本机，默认保留最近若干条。Settings tab 提供 AI Builder Space API token
输入与连接测试、默认 endpoint 说明（endpoint 固定，不可编辑）、可选 OpenCode 配置
（server URL、username、password），以及语言偏好（System / English / 简体中文）和转写
上下文（prompt / terms）输入。

OpenCode 是可选增强：只有在用户填好配置并通过连接测试后，Record tab 才显示"发送到
OpenCode"入口（gating 对齐 iOS）。`voiceflow://record` deep link 已注册，打开后切到
Record tab 并复用现有开始录音流程，不接受 token、文本或其他外部 payload。

模块内部分层与 iOS 同构：`MainActivity` 承载 Compose 入口与 deep link 处理；
`MainViewModel` 持有 `VoiceFlowClient` facade、驱动 live session 与事件消费、维护跨页面
状态；`VoiceFlowApp` 是根 Composable。状态类型在 `model/`（`RecordingStatus`、
`TranscriptHistory`、`OpenCodeSendStatus`、`ConnectionTestStatus`、`AppLanguage`、
`DeepLink` 等），持久化在 `data/SettingsStore`（token 与 OpenCode password 存
`EncryptedSharedPreferences`，其余非敏感项存普通 `SharedPreferences`，对齐 iOS 用
Keychain + UserDefaults 的分层）。`service/OpenCodeClient` 是 app 专属的"发送到
OpenCode"HTTP relay，不进 library。`i18n/` 实现真正的应用内语言切换（CompositionLocal
+ `createConfigurationContext`，无需重启）。UI 在 `ui/record`、`ui/settings`、
`ui/components`（含 `WaveformView`）、`ui/theme`，字符串本地化在 `res/values` 与
`res/values-zh-rCN`。

## 安全与隐私

token 由 host 提供，库不持久化。仓库只包含 fake 配置示例。库声明 `RECORD_AUDIO` 和
`INTERNET` 权限，host 通过 manifest merge 继承；`RECORD_AUDIO` 仍需 host 在运行时
请求（Android 不允许 library 直接授予）。诊断日志只记安全摘要，不记 token、转写文本或
音频内容。

## 发布要求

代码、文档、测试按可公开发布标准维护。公开仓库：`grapeot/voiceflow-android`，默认分支
`main`。

## 成功标准

- library 模块在 Android Studio JBR 下 `assembleDebug` 与 `testDebugUnitTest` 全绿。
- 公开 facade 与 iOS 版语义一致，wire protocol 与常量逐字对齐。
- 内部 pipeline 全部 `internal`，published ABI 只含 facade。
- host AI 读完集成指南能单 prompt 内集成；参考 app 即一个可运行的集成结果参照。

## 明确不做（V0）

library 本身不含 app 层行为（编辑器、历史、剪贴板、OpenCode 发送等都在参考 app 里，不进
library）。不做离线转写、文件导入重采样、转写模型选择 UI。
