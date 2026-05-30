# VoiceFlow 设计 Spec — Pixelate（像素即纪律）

本文件是 VoiceFlow 录音/转写客户端的视觉设计语言规格。它和 iOS 仓库的同名 spec 是**同一套设计语言的两个平台实例**——Android 严格对齐 iOS，差异只在实现手段（Jetpack Compose / Material 3 vs SwiftUI），不在视觉决策。

## 一句话方向

像 Teenage Engineering OP-1、Playdate 掌机、《Return of the Obra Dinn》那种 **"像素即纪律"** 的高级路线——不是廉价 8-bit 怀旧。像素是一种几何纪律：元素对齐网格、颜色极少、坦荡承认低分辨率。"贵"来自克制、单色和恰到好处的留白，不靠高饱和原色或粗黑边框。

## 设计原则

1. **像素只能是几何纪律，不能是怀旧符号。** 一旦出现高饱和原色、粗黑描边、8-bit 卡通字，高级感立刻归零。这里的像素全部服务于"克制"。
2. **单一识别色：暖琥珀 `#F0A868`。** 整屏在任何时刻最多一处彩色，其余全是中性灰阶 + 近黑/纸白背景。琥珀承担"可交互 / 录音中"的语义。
3. **混合字体策略（硬约束）。** 像素字（Silkscreen，OFL）只用在**框架元素**：计时器、英文状态文案、英文按钮标签。**转写正文和任何中文一律用系统字体**——像素字不含 CJK，可读性也不适合长文；Compose / SwiftUI 对像素 FontFamily 遇到中文会自动 fallback 到系统字体，这是预期行为。
4. **关掉 Material You 动态取色。** 品牌色固定，不跟壁纸，和 iOS 一致。
5. **坦荡的低分辨率。** 波形宁可少而大（15 个方块）也不要密而细——密细是在偷偷模拟平滑波形，那是不自信；明确的大方块才是设计选择。

## 色板（双值，跟随系统深浅）

token 定义在 `ui/theme/DesignTokens.kt`（`Palette` 对象），是 iOS `DesignTokens.swift` 的直接移植，深浅 hex 一一对应。

| 用途 | 深色 | 浅色 |
|---|---|---|
| 主背景 | `#0A0A0B` | `#FAFAF7` |
| 次级背景 | `#141416` | `#F2F2EE` |
| 主文字 | `#F4F4F5` | `#1A1A1A` |
| 次级文字 | `#A1A1AA` | `#71717A` |
| 三级文字 | `#52525B` | `#A1A1AA` |
| 分隔线 | `#27272A` | `#E4E4E1` |
| **唯一识别色** | **`#F0A868`（琥珀，深浅同值）** | 同 |
| accent on（琥珀上的文字） | `#000000` | 同 |

## 字体

- 像素字：**Silkscreen**（`res/font/silkscreen.ttf` / `silkscreen_bold.ttf`，OFL 1.1，license 留在 `assets/licenses/`）。`DesignTokens.Pixel` 暴露 `timer`（~48sp）/`caption`（~14sp）/`button`（~14sp bold），fontFamily 指向 Silkscreen。
- 系统字：转写正文、所有中文走平台默认 sans-serif（保证可读 + CJK 覆盖）。

## 录音/转写主界面（`RecordScreen`）

从上到下：像素计时器 `00:00` → 像素状态行（英文 Silkscreen / 中文系统字）→ **像素方块波形** → 转写正文（系统字，`Speak.` placeholder）→ **录音按钮** → 三个 ghost 次级控件。

### 波形（`WaveformView`，核心）

- **15 个 bar**（从 36 减下来），双向对称（中线向上向下展开）。
- 每个 bar **不是一个实心矩形，而是一列小像素格堆叠**——cell ≈ 5.5dp、cell 间留 ≈ 1.75dp 缝；横向取 14dp bar 宽能容纳的整数列、居中。要能明显看出"这个大 bar 由小方块拼成"。
- **中线处也要留缝**：上下两半各从中线推半个 gap，避免中心两块并成一块（这是 Android 曾出过的 bug）。
- 保留 Idle / Active / Generating 三态动画逻辑不变；Idle 态也用同一像素画法（只有一两个小方块高）。

### 录音按钮（`CapsuleButton` Primary / Secondary）

- 形状用**像素阶梯圆角**（`PixelRoundedShape`）：四角用 3 级、每级 ~4dp 的直角台阶模拟"像素圆角"，边是直的。不是平滑胶囊。
- **只留文字标签**（`RECORD` / `STOP`，Silkscreen），**不放图标**——曾试过放像素 mic / stop 方块，但精细度不统一、显得突兀，去掉后更干净。
- 高度 56dp，Primary 琥珀实底 + 黑色像素字，Secondary 琥珀描边 + 琥珀字。

### Tab 图标（`PixelTabIcon`）

- mic / gear 都是手绘 **7×7 像素网格**（Canvas 自绘，方块 + 12% 缝），无 Material 矢量残留。两端图案完全一致。
- 选中琥珀、未选灰。

## App Icon / Logo

- app icon 与 in-app logo 是一个**像素语音气泡 + 内含波形**的标记（琥珀单色、近黑底），同时传达"语音"和"AI 理解"。取代了早期那个多色像素脑子。
- Android：`mipmap-*/ic_launcher.png` + `ic_launcher_round.png` 全 6 档密度。
- iOS：`Assets.xcassets/logo.imageset`（@1x/2x/3x）+ `AppIcon.appiconset`（1024 三态）。
