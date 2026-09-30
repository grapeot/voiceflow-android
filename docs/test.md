# 测试与验收策略

测试代码在 Gradle 模块内：`voiceflowkit/src/test/`（库 JVM 单元测试）、`app/src/test/`（参考 app JVM 测试）、`app/src/androidTest/`（instrumented，默认自动 skip）。仓库根目录没有 `tests/` 文件夹。

## Agent / 日常验证

**默认只跑 JVM 单测。** 不依赖网络，不需要设备/模拟器：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :voiceflowkit:testDebugUnitTest :app:testDebugUnitTest
```

系统 `java` 不在 PATH，必须先 export Android Studio 自带 JBR（与 `AGENTS.md`「构建与验证」一致）。

发版前或用户明确要求完整验收时：

```bash
./gradlew :voiceflowkit:testDebugUnitTest :app:testDebugUnitTest \
  :voiceflowkit:assembleDebug :app:assembleDebug
```

## 单元测试（voiceflowkit/src/test）

纯 JVM + mock，不依赖网络。覆盖：

- `RealtimeApiUrlBuilderTest`（10）— base URL 归一化、API URL 拼装、https→wss 映射、base path 不重复前缀、ticket query 保留
- `RealtimeMessageParserTest`（13）— 各 wire 消息 type → event 映射、start 控制消息
- `Pcm16WavWriterTest`（5）— WAV header roundtrip
- `AudioChunkCacheTest`（5）— 磁盘 cache append / readChunk 窗口 / 越界 / byteCount / remove
- `VoiceFlowAudioMeteringTest`（5）— RMS → 0..1 level
- `TranscriptHelpersTest`（11）— delta reducer、finalize resolve（completed 权威 / partial 兜底 / trim）、recoverable error 判定（buffer too small 大小写不敏感）
- `BulkTranscriptionProgressTest`（6）— bulk accumulator 与 finished-vs-error 顺序修正
- `StreamCaptionStoreTest`（4）— 双层 caption 状态机（persistent + transient 闪现、重闪重置计时）
- `VoiceFlowClientStubTest`（11）— `makeStub` 行为
- `GptLiveTranscribeTest` — strategy 解析与 capability、model pinning、session body 契约、originating strategy / 自定义 model 的 preserved retry、timeout 随 PCM 时长缩放（GPT Realtime 固定）
- `RealtimeWebSocketSessionTest` — queue backpressure 与 audio/commit/turn/stop 发送顺序
- `GrokBatchTranscriptionTest` — Grok 不建 realtime session、multipart 保留 mount filename 与 terms、connection test 走 usage summary endpoint
- `SignalQualityTest` — 静音/语音 RMS 阈值与 speech tier 判定
- `LiveBackendPromptFollowingTest`（2）— opt-in live 集成测试，见下节

## 参考 app 单元测试（app/src/test）

- `FinalizeTypewriterTest` — finalize 打字机：append-only reconciliation（后端重新分段时非前缀走 replace、相同内容跳过 recomposition）、non-conflating channel 保证每个 snapshot 按序到达不被 StateFlow conflate 吞掉
- `RescueGatingTest` — 保存 / 重发录音的救援门控
- `StrategySettingsTest` — strategy 选择与持久化；ordered audio sender 非阻塞（capture 回调不等网络 backpressure）、overflow 拒绝、1s drain timeout 取消卡死的网络发送

## Live backend 集成测试（opt-in）

`LiveBackendPromptFollowingTest`：把 checked-in 的 `voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav`（24kHz TTS 音频）经 `VoiceFlowClient.transcribe` 喂给真实 AI Builder backend，覆盖 GPT Realtime prompt 与 GPT Live 非空结果。**会消耗 API 额度**，靠 `VOICEFLOW_LIVE_WS=1` + 根目录 `.env` 里的 token 触发，**默认不跑**。

```bash
cp .env.example .env   # 填入 AI_BUILDER_TOKEN
./scripts/test_live_integration.sh
```

脚本设好 JBR、加载 `.env`、用 `--rerun-tasks` 单独跑该测试，对齐 iOS 的 `scripts/test_live_integration.sh`。

## OpenCode live e2e（opt-in，instrumented）

`OpenCodeLiveSendTranscriptTest`：通过 `OpenCodeClient.sendTranscript` 真实路径发送转写，并读回 `GET {base}/session/{id}/message` 验证 session 里确实落了 `[user]` 消息——只有 204 不算通过（那是 broken agent 的静默失败模式，公开 client 现在在 `sendTranscript` 内部做 read-back 验证）。

- 凭证来自根目录 `.env`（gitignored），由 `app/build.gradle.kts` 注入 instrumentation runner args（`OPENCODE_BASE_URL` / `OPENCODE_USERNAME` / `OPENCODE_PASSWORD`）；缺 `OPENCODE_BASE_URL` 时 `assumeTrue` 自动 skip，默认跑法永远绿且不出网。
- 模拟器访问 host localhost 走 `10.0.2.2`，测试会把 base URL 里的 `localhost` / `127.0.0.1` 重写过去。

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yage.voiceflow.service.OpenCodeLiveSendTranscriptTest
```

需要已连接的真机/模拟器 + 可达的本地 OpenCode server。

## 手工验证清单

- 首次启动 → Settings 保存 token → Test Connection
- Record 屏录音 → Stop → 转写 → 自动复制
- 分别选 GPT Realtime / GPT Live Transcribe / Grok Batch 录音：GPT Live 录音中出字（既有设计），GPT Realtime 录音中不出字，Grok 录音期无网络活动、Stop 后才上传
- 历史 chevron、⋯ 菜单的保存/重发
- 中英文双语切换
- 模拟器截图逐项确认视觉元素（Pixelate：状态字、像素方块波形、Tab 图标、app icon），与 iOS 一致

## 文档与仓库验收

公开文档齐全：`README.md`、`docs/prd.md`、`docs/rfc.md`、`docs/test.md`、`docs/design.md`、`docs/working.md`、`AGENTS.md`。`.env.example` 仅含 fake 示例。

隐私扫描：

```bash
rg -n '(o[p]://|/U[s]ers/[^ ]+|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|sk-[A-Za-z0-9]|AIza[0-9A-Za-z_-]+)' .
rg --files -g '*.m4a' -g '*.wav' -g '*.caf' -g '*.keystore' -g '*.jks'
```

预期：凭据模式零匹配；仓库内唯一的提交音频是 live 测试 fixture `voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav`（有意 checked-in）；无 keystore / `local.properties`。
