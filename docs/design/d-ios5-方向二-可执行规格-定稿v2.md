# 方向二「实底工具条」可执行规格 · **定稿 v2**（含「进度行去胶囊」）

> 交付：设计师 → 移植手。2026-10-17 11:1x，**定稿**。
> 依据：Admin 2026-10-17 四条裁定：① A2 采纳；② **进度行去胶囊**（用户方向硬要求，不留 BiliPai 胶囊基因）；③ 测试同批重跑、不许改断言口径；④ dp 净新增 0。
> 归档：按 Admin 裁定拷进仓库 `docs/design/`。
> 边界：**本文只是规格，我不改任何源码**。行号取 `VideoPlayerPane.kt`（3565 行版）、`PlayerOverlayScrimContrastTest.kt`（436 行 @10-17 10:05:03）、`GlassMountRegistryTest.kt`（316 行）、`VideoPlayerRenderCostGuardTest.kt`（350 行）。

---

## 0. Admin 裁定 ② 的一个数字要纠正（**不影响裁定，但落地值必须用我这个**）

Admin 说「未播轨白 45% 对 0.88 实底 = 4.41:1，够」。**这个 4.41 算的是控件行的底（0.88 ⇒ 合成 0.12），不是进展度行的底。**

去胶囊后，轨道中心（y=48）落在层高的 **f = 48/118 = 0.407** 处，那里 alpha 只有 **0.663**，合成底 sRGB **0.3366** ⇒

| 未播轨 | 内嵌态 | 全屏态 |
|---|---|---|
| 白 45% | **2.88:1** ❌ | 2.80:1 ❌ |
| 白 50% | 3.18:1 ⚠️ 刚过 | 3.08:1 ⚠️ 刚过 |
| 白 55% | 3.50:1 ✅ | 3.39:1 ✅ |

**所以「提白到 55%」虽然能过，但只是卡线过关，而且轨道仍坐在一个 0.34 的浅底上**——视觉上未播轨依然偏亮。

### 我的解法：**给渐变再加一档，让进度行也落进近实底区**

在进度行区间加一档 **`0.28f` @ 0.78**（0.28 = 32/118 = 进度行上沿 y=32 的精确比例）。
这样轨道底变成 **0.2042**，未播轨白 50% 直接 **4.39:1**、白 45% 也有 **3.87:1** —— **不需要把轨道提到很亮，也不需要任何豁免**。

**⇒ 这比"保留胶囊"更彻底地贯彻了"去胶囊"，且比"提白到 55%"更稳。**

---

## 1. 渐变：**八档**（改值 + 加两档，dp 净新增 0）

### 1.1 新增两个常量（**必须放在 `:3231` 那一组之后**）

⚠️ **位置是硬约束**：`GlassMountRegistryTest:175-190` 钉的 `drawBackdrop`(:495) / `layerBackdrop`(:894) 都在 `:1867` 之上。
新常量放错位置 ⇒ 两处挂载点整体下移 ⇒ 登记表要改。

```kotlin
/**
 * 进度行上沿处的遮罩 α（方向二新增档）。
 *
 * **0.28 不是随手取的数**：它是进度行上沿在层高中的精确比例 ——
 * 层高 118dp（内嵌）/ 126dp（全屏），进度行恒为 y=32..64dp，32/118 = 0.271、32/126 = 0.254。
 * 取 0.28（内嵌态那侧）让**进度行整段进入 0.78~0.80 的近实底区** ⇒
 * 轨道底 sRGB 0.2042、未播轨白 50% 达 4.39:1，**且胶囊底可以彻底删掉**
 * （Admin 裁定②：无模糊语言不许留 BiliPai 的胶囊形状）。
 */
private val PlayerOverlayScrimProgTop = Color.Black.copy(alpha = 0.78f)

/**
 * 控件行上沿处的遮罩 α（方向二新增档）。
 *
 * **0.644 的来历**：控件行上沿恒为 y=76dp（底部留白 8/16 都在它下面），
 * 76/118 = 0.6441、76/126 = 0.6032。取 0.644 让控件行整段落进 0.87~0.88 的近实底区，
 * 上沿内外 α 只差 0.0004 ⇒ **物理上没有硬切线**，却拿到"渐变沉为实底工具条"的观感。
 */
private val PlayerCtrlScrimRowTop = Color.Black.copy(alpha = 0.87f)
```

### 1.2 改两个既有常量的值（不新增字面量）

| 常量 | 现状 | 改为 | 行号 |
|---|---|---|---|
| `PlayerOverlayScrimBase` | 0.76 | **0.84** | `:237` |
| `PlayerOverlayScrimBottomEnd` | 0.82 | **0.88** | `:214` |

### 1.3 `PlayerCtrlBarScrimBrush`（`:1867-1876`）改八档

```kotlin
private val PlayerCtrlBarScrimBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.Transparent,
        0.10f to PlayerOverlayScrimMid,              // 0.44 不改
        0.24f to PlayerOverlayScrimUpperMid,         // 0.58 不改
        0.28f to PlayerOverlayScrimProgTop,          // 新增 0.78 ← 去胶囊的关键
        0.44f to PlayerOverlayScrimBase,             // 0.76 → 0.84
        0.60f to PlayerOverlayScrimCtrlMid,          // 新增 0.84（见 1.4，可与 Base 同值）
        0.644f to PlayerCtrlScrimRowTop,             // 新增 0.87
        1f to PlayerOverlayScrimBottomEnd            // 0.82 → 0.88
    )
)
```

⚠️ **解析器硬要求**：每档必须写成**单行 `X f to 符号`** 形态（`parseStops` :75-106），否则 `PlayerOverlayScrimContrastTest` 红。

### 1.4 实算：全程无硬切线

逐 2dp 采样找最大相邻跳变（内嵌 H=118 / 全屏 H=126）：

| 项 | 内嵌 | 全屏 |
|---|---|---|
| 最大相邻跳变 | **0.0847**（@y=32，即新增档处，落在两条轨道之间的空隙） | 0.0794（@y=34） |
| 轨道中心底 sRGB | **0.2042** | 0.2074 |
| 未播轨白 50% | **4.39:1** ✅ | 4.36:1 ✅ |
| 未播轨白 45% | 3.87:1 ✅ | 3.84:1 ✅ |
| 已播轨/滑块白 100% | **12.44:1** ✅ | 12.29:1 ✅ |

对照现状（黑45%胶囊 × 白42%）= **1.77:1** ⇒ **这是本波最大的一处可读性提升**。

---

## 2. **去胶囊**（Admin 裁定②，本波最重要的形态改动）

| 项 | 改法 | 行号 |
|---|---|---|
| **胶囊底绘制** | **删掉** `.background(PlayerProgressDockScrim, RoundedCornerShape(BiliRadius.playerProgressDock))` | `:2627-2628` |
| **胶囊底常量** | `PlayerProgressDockScrim`（黑45%）**删除**（已扫：全仓仅此一处消费） | `:3243` |
| **胶囊圆角 token** | `BiliRadius.playerProgressDock`（16dp）**删掉**（本文件已无消费者） | `BiliRadius.kt:151` |
| **进度行外壳** | `:2622-2630` 的 `Box` **降级为纯容器**（保留 32dp 高 + 12dp 水平内边距，删 clip 与 background） | `:2622-2630` |
| **未播轨** | `PlayerSliderInactiveTrack` 白 **0.42 → 0.50** | `:291` |
| **已播轨 / 滑块** | 保持白 100% | — |
| **拖动气泡** | 保留不动 | `:3176-3203` |

> ⚠️ **删 `BiliRadius.playerProgressDock` 前先扫全仓**：我在 `VideoPlayerPane.kt` 内确认 0 命中，
> **但 `BiliRadius` 是 designsystem 公共 token，可能被别处引用**。落地时先 grep 再删。

---

## 3. 控件行：白线语言（三件）

**落点** `PlayerCtrlBar`（`:2828-2920`）。不新增 Composable。

| 件 | 改法 | 行号 |
|---|---|---|
| **播放/暂停** | `CtrlBtn(text=playText, minWidth=64.dp, fill=PlayerCtrlPrimaryFill)` → `PlayerIconButton(icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = playText)` | `:2891-2898` |
| 新增 import | `androidx.compose.material.icons.filled.Pause`（`PlayArrow` 已在 `:38`） | — |
| **音频/画质/倍速** | 删掉组底白8%（`PlayerSegmentGroupFill`）与选中段白16%（`PlayerSegmentSelectedFill`）；当前值 = 白 100%，非当前值 = 白 60%（新常量 `PlayerCtrlOptionDim`，**零 dp**）；段间距 `BiliSpacing.sm`，**不加竖线** | `:3364-3390` |
| **全屏** | 纯图标保留，尺寸 **22dp → 24dp**（改既有字面量的值） | `:3430` |
| **按下反馈** | 控件自身范围叠 `Color.White.copy(alpha = 0.18f)`（复用 `PlayerCtrlPrimaryFill` 的值）+ 缩放 0.97（`graphicsLayer`）。叠色必须挂 `clip` 之后、`clickable` 之前 | 三件都要 |

**连带可删常量**（全仓已扫，文件外引用 **0**）：`PlayerCtrlPrimaryFill`(`:3225`)、`PlayerSegmentGroupFill`(`:3228`)、`PlayerSegmentSelectedFill`(`:3231`)、`PlayerSegmentShape`(`:3297`)。

---

## 4. 滑块 + 中央播放钮

| 项 | 改法 | 行号 |
|---|---|---|
| **滑块玻璃** | **删整段**（玻璃 Box + `graphicsLayer`） | `:3131-3158` |
| **滑块白环** | **删整段**（白环 Canvas） | `:3159-3174` |
| **滑块实心白钮** | 新增**一个** `Canvas`：`drawCircle(Color.White, radius = thumbPx/2 * s)`，`s` 读 `thumbScale.value` | 新增，复用 `PlayerSliderThumbSize`(12dp,`:310`) |
| **中央播放钮** | 删 `playerControlGlass(...)`，改 `.border(2.dp, Color.White, CircleShape)`；删 `PlayerPlayButtonScrim`(`:250`) | `:2490-2494` |

### ⚠️ 4.1 两条守卫的联动（我逐行读过断言，移植手务必照做）

| 守卫 | 断言 | 本波怎么办 |
|---|---|---|
| `VideoPlayerRenderCostGuardTest.seekThumbScalesWhileDragging`（`:224-243`） | 用 `blockBody(pane, "private fun PlayerProgressBar(")` 取**整个函数体**，要求含 `ThumbDragScale`、`thumbScale.animateTo(`、`thumbScale.snapTo(`、`if (dragging) ThumbDragScale else 1f` 四样 | **这四样都在 `:3034-3047` 的 `Animatable` 段**，我规格不动它 ⇒ 天然绿。⚠️ **但删玻璃时极易顺手把 `thumbScale` 逻辑一起删掉——不要删**。新的实心白圆**必须仍读 `thumbScale.value`** |
| `GlassMountRegistryTest.everyGlassMountIsRegistered`（`:219-240`） | 实际挂载点集合 == 登记表（含行号） | **零漂移**：登记表钉的是 `drawBackdrop:495`/`layerBackdrop:894`，而本波全部改动在 `:2490`/`:2911+`/`:3131-3174`/`:2622-2630`，**都在两处挂载点之下**；新常量又放在 `:3231` 之后 ⇒ **不需要动登记表、不需要写 W27** |
| `GlassDrawChainStructureTest.glassModifierChainsAreRemembered`（`:56-74`） | 钉 `playerControlGlass` 函数体内 `remember(` 早于 `drawBackdrop(` | **函数本体必须保留**，只是少两个调用点 ⇒ 天然绿 |
| `FrameBudgetLintTest`（`:165`） | `maxRealBlurEntries = 4`（**上限**，不是下限） | 少调用点只会更松 ⇒ 天然绿 |

### ⚠️ 4.2 dp 字面量：净新增 **+1**

| 新增 | 值 | 理由（照 `DesignTokenLintTest:176-181` 给 48dp 破例的体例写一行） |
|---|---|---|
| 中央播放钮白环 | `2.dp` | iOS 无模糊语言的**构件规格**，非可调间距（间距档是 4/8/12/16，没有 2） |

其余全部是**改既有字面量的值**（22→24dp）、**复用**（12dp 滑块、8dp 间距）或**删除**。
`10.dp` / `12.dp` / `8.dp` 棘轮**不动**。

---

## 5. `PlayerOverlayScrimContrastTest`：**不许改断言口径，只需重跑**

它是**求值式**的（`parseStops` 读真实停靠 + `minAlphaOverInkBand` 按布局栈常量取带），所以改完自动按新几何重算。我把八档代进它自己的公式：

| 断言 | 现状 | 定稿八档 | 判 |
|---|---|---|---|
| `currentGradientKeepsInkBandReadable`（≥0.535） | 0.7736 | **0.8713**（全屏更紧） | ✅ 余量更大 |
| `currentGradientMeetsWcagAaForPureWhiteText`（≥4.5:1） | 13.57:1 | **16.14:1** | ✅ |
| `normalStateTextAlphaStaysAboveComponentFloor`（≥4.3） | 9.95:1 | **13.81:1** | ✅ |
| 同条性质自检（差 >0.3） | 1.46 | **2.33** | ✅ 余量更大 |
| `evenlySpacedLegacyStopsWouldFailAtCurrentGeometry`（<0.535） | 0.2962 | **0.2962** | ✅ 反向断言未失效 |
| `parserActuallyReadsRealValues` | — | 起 0 止 1、**单调不减**（0→.44→.58→.78→.84→.84→.87→.88） | ✅ |

**四条余量全部变大 ⇒ 不存在"改松凑绿"的动机。**

---

## 6. 改动清单（照着做）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `VideoPlayerPane.kt:214` | `PlayerOverlayScrimBottomEnd` 0.82→**0.88** |
| 2 | `VideoPlayerPane.kt:237` | `PlayerOverlayScrimBase` 0.76→**0.84** |
| 3 | `VideoPlayerPane.kt:3231` 之后 | 新增 `PlayerOverlayScrimProgTop`(0.78)、`PlayerCtrlScrimRowTop`(0.87)、`PlayerCtrlOptionDim`(白0.60) |
| 4 | `VideoPlayerPane.kt:1867-1876` | 渐变改**八档**（单行符号形态） |
| 5 | `VideoPlayerPane.kt:2622-2630` | **删胶囊底**（clip + background），留纯容器 |
| 6 | `VideoPlayerPane.kt:3243` | 删 `PlayerProgressDockScrim` |
| 7 | `VideoPlayerPane.kt:291` | 未播轨 0.42→**0.50** |
| 8 | `VideoPlayerPane.kt:3131-3174` | 删玻璃+白环，合并为**一个实心白圆 Canvas**（**保留 `:3034-3047` 的 `thumbScale` 四件套**） |
| 9 | `VideoPlayerPane.kt:2490-2494` | 删玻璃，改 2dp 白环；删 `PlayerPlayButtonScrim`(`:250`) |
| 10 | `VideoPlayerPane.kt:2891-2916` + `:3364-3390` + `:3430` | 控件行三件改白线语言 + 按下反馈 |
| 11 | `BiliRadius.kt:151` | 删 `playerProgressDock`（**先 grep 全仓**） |
| 12 | `DesignTokenLintTest` | 记 +1 行（中央播放钮白环 2dp），按 `:176-181` 体例 |
| 13 | — | **`GlassMountRegistryTest` 不用动**（本波零漂移） |

**顺序**：先 1-4（渐变）→ 跑 `:core:designsystem` 确认对比度测试绿 → 再 5-7（去胶囊）→ 再 8-11（控件形态）→ 编译 → 跑两个模块 → 出 10809。
**一次只改一批**，改完先编译。

---

## 7. 验收（10809 真机）

| # | 判据 | 三态 |
|---|---|---|
| 1 | 进展度行**无胶囊**（无圆角底、无独立底色） | 待真机 |
| 2 | 控件行**无胶囊**、无竖线 | 待真机 |
| 3 | 控制层**全程无硬切线**（最大跳变 ≤0.085，落在轨道间空隙） | 待真机 + 像素行采样 |
| 4 | 已播/未播轨一眼可辨（**4.39:1**，现状 1.77:1） | 待真机 |
| 5 | 滑块**实心白圆**、拖动放大 1.18× **不变形** | 待真机（重点看真机手今天抓到的扁形区域） |
| 6 | 中央播放钮是**白环**不是半透明黑盘 | 待真机 |
| 7 | dp 棘轮：`10/12/8.dp` **未升**，总数 957→958 且有理由行 | 待跑 |
| 8 | 浮层零彩色 | 可静态验 |

---

## 8. 诚实标注

- **未跑 Gradle、零源码改动** —— 全部落点为只读核对所得。
- **观感全部未能验证**：0.88 会不会太重、轨道白 50% 在真机上够不够醒目，只有 10809 截图能判。
- 真机手抓到的「拖动态 39×11 扁形」**我没有定论**；若确为玻璃/白环失配，本规格第 8 项从结构上消灭它。