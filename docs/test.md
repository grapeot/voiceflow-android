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
- `ToolbarFeedbackTest` — 两秒到期、连续相同提示重新计时、活跃状态优先
- `ToolbarViewModelTest` — 真实 VM 的清空/历史恢复、空清空 no-op、空复制实际调用 Android 剪贴板、复制失败与忙碌门控；挂起的合成发送覆盖 Sending→clear→restore/前后导航、菜单与重复发送门控；显式复制成功/失败/空文本到期及离页后的最终状态选择，自动复制持久状态保持。全部依赖使用合成 mock，不外发
- `TranscriptCompositionTest` — Replace/Append 基底、Unicode/换行、累计 snapshot、尾段重发、失败回滚、用户编辑与未持久化音频的边界失效
- `TranscriptModeViewModelTest` — 模式会话默认值/两秒反馈、Start 权限期间锁定、三策略整篇历史与剪贴板、逐帧 finalize、bulk fallback、重发/后台/无信号/失败恢复、旧 session 事件及晚到响应不能改新内容。麦克风、服务和响应均为合成 mock

模式回归还使用 Deferred 控制后台 microphone.stop、audio drain、session.cancel 的返回时机，验证旧清理不能覆盖新的 Append 救援。Replace 覆盖 partial 后异常/空白和连续救援，验证原文、历史、剪贴板及后续切换 Append 的尾段边界。

麦克风 mock 遵循真实的一次性 stop 契约：开始录音后第一次 stop 返回 WAV，重复 stop 返回 null。专门测试第一次 stop 挂起时前台重复操作被门控、无旧 WAV 的首次录音正确登记、旧 WAV 不被误用、WAV 登记后网络仍挂起时恢复救援，以及正常/后台/救援三条停止路径的异常和空结果释放门控。

## Live backend 集成测试（opt-in）

`LiveBackendPromptFollowingTest`：把 checked-in 的 `voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav`（24kHz TTS 音频）经 `VoiceFlowClient.transcribe` 喂给真实 AI Builder backend，覆盖 GPT Realtime prompt 与 GPT Live 非空结果。**会消耗 API 额度**，靠 `VOICEFLOW_LIVE_WS=1` + 根目录 `.env` 里的 token 触发，**默认不跑**。

```bash
cp .env.example .env   # 填入 AI_BUILDER_TOKEN
./scripts/test_live_integration.sh
```

脚本设好 JBR、加载 `.env`、用 `--rerun-tasks` 单独跑该测试，对齐 iOS 的 `scripts/test_live_integration.sh`。

## OpenCode live e2e（opt-in，instrumented）

`OpenCodeLiveSendTranscriptTest`：通过 `OpenCodeClient.sendTranscript` 真实路径发送转写，并读回 `GET {base}/session/{id}/message` 验证 session 里确实落了 `[user]` 消息——只有 204 不算通过（那是 broken agent 的静默失败模式，公开 client 现在在 `sendTranscript` 内部做 read-back 验证）。

- 凭证来自根目录 `.env`（gitignored），仅在显式传入 `-PvoiceflowLiveTestCredentials=true` 时由 `app/build.gradle.kts` 注入 instrumentation runner args（`OPENCODE_BASE_URL` / `OPENCODE_USERNAME` / `OPENCODE_PASSWORD`）。默认不读取 `.env`，参数为空，live 测试跳过。带凭证的测试 APK 不可分发；普通 app APK 不注入这些测试参数。
- 模拟器访问 host localhost 走 `10.0.2.2`，测试会把 base URL 里的 `localhost` / `127.0.0.1` 重写过去。

```bash
./gradlew connectedDebugAndroidTest \
  -PvoiceflowLiveTestCredentials=true \
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
- 工具条模式/清空/复制在空页/非空/忙碌态常驻；中英、深浅色、窄屏和大字体下不挤掉图标
- 空复制覆盖旧剪贴板；空清空连续点击不改变历史；清空后左箭头恢复刚归档文本
- 连点复制/清空，状态区两秒反馈不移动按钮；录音、转写和发送优先
- 连点模式后确认 Replace 灰色 swap、Append accent 文字追加；历史按钮随模式切换为 Undo/Redo
- Append 两次录音形成整篇文档；重发只改最后录音，失败重发恢复原文；编辑/历史/清空后重发安全追加
- 录音/转写中不能改变模式，有非空文档基底时不能编辑，失败/后台结束后恢复可用；现有 Save/Resend 救援入口保持可用

工具条改动的离线验证：

```sh
unset VOICEFLOW_LIVE_WS
./gradlew --offline :voiceflowkit:testDebugUnitTest :app:testDebugUnitTest -PvoiceflowLiveTestCredentials=false
./gradlew --offline :voiceflowkit:assembleDebug :app:assembleDebug -PvoiceflowLiveTestCredentials=false
./gradlew --offline :app:lintDebug -PvoiceflowLiveTestCredentials=false
```

不要用全量 connected tests 代替离线验证。需要设备 UI 自动化时只定向执行合成用例，真实 API 与 OpenCode 外发测试需另行授权。

## 文档与仓库验收

公开文档齐全：`README.md`、`docs/prd.md`、`docs/rfc.md`、`docs/test.md`、`docs/design.md`、`docs/working.md`、`AGENTS.md`。`.env.example` 仅含 fake 示例。

隐私扫描：

```bash
rg -n '(o[p]://|/U[s]ers/[^ ]+|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|sk-[A-Za-z0-9]|AIza[0-9A-Za-z_-]+)' .
rg --files -g '*.m4a' -g '*.wav' -g '*.caf' -g '*.keystore' -g '*.jks'
```

预期：凭据模式零匹配；仓库内唯一的提交音频是 live 测试 fixture `voiceflowkit/src/test/resources/fixtures/tts_all_caps_24k.wav`（有意 checked-in）；无 keystore / `local.properties`。
