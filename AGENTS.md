# VoiceFlow (Android) Agent Notes

本项目工作语言是中文。这是 iOS 仓库 `voiceflow` 的 Android 移植，交付物是一个独立
的 Kotlin Android library —— VoiceFlowKit。仓库内容按可发布到 GitHub 的标准处理，
所有文档、示例、测试和代码都只能包含可公开的信息。

## 项目定位

VoiceFlowKit (Android) 是一个面向 AI 集成方的"生成内核"。让任何 Android app（minSdk
26）的 host AI agent 通过读一份集成指南就能给宿主 app 加上"按一下录音 → 出文字"的
能力。它对齐 iOS 版的公开 facade、wire protocol 和断线恢复语义。

公开仓库：<https://github.com/grapeot/voiceflow-android>（默认分支 `main`）。

## 目录结构

```text
settings.gradle.kts / build.gradle.kts / gradle.properties   # 独立 Gradle 工程
gradle/wrapper/                                              # Gradle 9.3.1 wrapper
gradle/libs.versions.toml                                   # version catalog（无 Compose/Hilt/Retrofit）
voiceflowkit/                                               # library 模块（artifactId = voiceflowkit）
  build.gradle.kts                                          # com.android.library
  src/main/AndroidManifest.xml                              # 声明 RECORD_AUDIO + INTERNET
  src/main/java/com/yage/voiceflowkit/                      # 公开 facade
  src/main/java/com/yage/voiceflowkit/internal/             # internal pipeline（不进 ABI）
  src/test/java/com/yage/voiceflowkit/                      # JVM 单元测试
docs/prd.md / docs/rfc.md / docs/working.md                 # 产品 / 技术方案 / 变更记录
app/                                                        # 参考 app（Compose，applicationId com.yage.voiceflow，见 working.md）
```

不要在仓库根目录新建 `tests/`；测试放在 `voiceflowkit/src/test/`（JVM 单测）或将来的
`voiceflowkit/src/androidTest/`（仪器测试）。

## 硬性规则

1. 不提交真实 token、`.env`、录音文件、日志、构建产物、keystore 或 `local.properties`。
2. 对外文档只描述 VoiceFlowKit 的最终产品状态，不写内部来源、私有项目、私有账号或
   不可公开的历史上下文（包括用作移植参考的私有 Android 客户端名称）。
3. 公开表面只有 `com.yage.voiceflowkit` 包下的 facade。`com.yage.voiceflowkit.internal`
   下的所有类/函数必须标 Kotlin `internal`，不得进入 published ABI。
4. 不引入 Hilt、Compose、Retrofit、kotlinx-serialization。JSON 用平台自带的
   `org.json`（对齐经确认可用的 Android 参考实现）。库保持 DI-agnostic，只暴露普通
   构造函数 / 工厂函数。
5. Wire protocol、常量（sampleRate 24000、replayChunkSize 240000、minCommitAudioBytes
   4800、maxRecoverAttempts 5 等）和恢复语义必须与 iOS 仓库 grapeot/voiceflow
   (<https://github.com/grapeot/voiceflow>) 的 Swift 源保持一致；行为以 Swift facade 为准。
6. 每次非平凡变更后更新 `docs/working.md`，记录完成内容、验证结果和影响后续实现的决策。
7. Git 操作（`git add` / `commit` / `push` / `gh pr create`）必须串行执行，不并行。

## 构建与验证

系统 `java` 不在 PATH，先 export Android Studio 自带 JBR：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :voiceflowkit:assembleDebug
./gradlew :voiceflowkit:testDebugUnitTest
```

## 推荐实现方向

复用经过确认的 Android 实现模式：24 kHz PCM16 mono `AudioRecord` 采集、OkHttp
WebSocket、session-create POST、磁盘 cache 重放、recover、commit/stop finalize。
Swift `actor` 映射为 Kotlin class + 内部 `Mutex`；`AsyncStream` 映射为 `Flow`；
`@Sendable` 闭包映射为 suspend lambda。具体文件复制必须先经过隐私检查。
