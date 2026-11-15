# 方向二「实底工具条」可执行规格 · **定稿 v3**

> 交付：设计师 → 移植手。2026-10-17，**v3 = 按已落地代码校正**。
>
> ⚠️ **v3 与 v2 的唯一实质差别**：v2 要求渐变改**八档**（含两个新常量
> `PlayerOverlayScrimProgTop` / `PlayerCtrlScrimRowTop`）。实际落地的是**五档**
> （`0 → .44@.10 → .58@.24 → .88@.36 → .88@1`，`VideoPlayerPane.kt:1872-1879`，
> mtime 2026-10-17 11:13:44）。
> **v3 按五档重写**，避免下一轮照着 v2 去找两个并不存在的常量；
> §0 保留 v2 的八档推导，供追溯。
>
> **五档在每一项判据上都优于八档**（轨道 4.01:1 vs 3.77:1、两态完全一致、陡坡减半），
> 所以**不必改回八档**。本文所有对比度数字均为代入 `ContrastMath.kt:84-90` 原公式实算。
>
> 边界：**本文只是规格，我不改任何源码。**行号取 `VideoPlayerPane.kt`（**3561 行**版，
> mtime 11:13:44）、`PlayerOverlayScrimContrastTest.kt`（436 行 @10-17 10:05:03）。
---

## 0. 【历史】v2 的八档推导（**已未采用，留档追溯**）

> 以下 §0.1~§0.3 是 v2 为"八档"写的推导。它**成立且数字没错**，但最终选了五档，
> 原因见 §1.2。**落地时不要照 §0 的八档值改。**

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

## 1. **渐变：实底并入渐变（已落地五档）**

`VideoPlayerPane.kt:1872-1879` `PlayerCtrlBarScrimBrush`：

```
0f     to Color.Transparent
0.10f  to PlayerOverlayScrimMid        // 0.44
0.24f  to PlayerOverlayScrimUpperMid   // 0.58
0.36f  to PlayerOverlayScrimBottomEnd  // 0.88  ← 平坦段起点
1f     to PlayerOverlayScrimBottomEnd  // 0.88
```

### 1.1 为什么平坦段起 **0.36**（而不是 v2 的 0.644）

胶囊（黑45%）删掉后，未播轨道直接坐在渐变上。轨道中心：

| 态 | 层高 | 轨道中心 y | f = 1 − y/H |
|---|---|---|---|
| 内嵌 | 118dp | 70dp | **0.4068** |
| 全屏 | 126dp | 78dp | **0.3810** |

若平坦段仍从 0.644 起，这两处 α 只有 ≈0.66 ⇒ 白42% 轨道只有 **2.85:1 / 2.77:1**，
低于非文本 3:1。⇒ 平坦段上移到 **0.36**，让轨道落进实底：白42% = **4.01:1**，
且**两态完全相同**（平坦段是常数，与层高无关）。

### 1.2 为什么最终没走 v2 的八档

| 项 | v2 八档 | **已落地五档** |
|---|---|---|
| 轨道处 α | 0.8275 / 0.8179（两态**不等**） | **0.88 / 0.88（两态相同）** |
| 未播轨 白42% | 3.77:1 / 3.71:1 | **4.01:1 / 4.01:1** |
| 未播轨 白50% | 4.69:1 / 4.60:1 | **5.11:1 / 5.11:1** |
| 最陡 `dα/df`（略过顶部淡出） | **5.00**（0.24→0.28） | **2.50**（0.24→0.36） |
| 墨迹带 minAlpha | 0.8713（全屏更紧） | **0.8800**（内嵌更紧） |

**五档各项皆优于八档**：轨道达标更宽、两态数值一致、渐变没有陡坡。
**结论：不需要改回八档，也不存在"为了过线才这么改"的动机。**

### 1.3 观感代价（必须留在 KDoc，别当退化改回去）

0.88 从 `f=0.36` 就铺满 ⇒ 118dp 层里 **75.5dp 是同一块黑**，只剩顶部 42.5dp 在淡出。
这是"实底工具条"的来源，也正是它与"画面上贴一排白字"（用户已否决形态）的分界。

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

> ✅ **已落地后的实际值**：上表第 6 行**没有执行**——未播轨最终保持白 **0.42**（`VideoPlayerPane.kt:293`）。
> 原因：平坦段起点上移到 0.36 后轨道处 α 已是 0.88，**白 42% 自己就有 4.01:1**（≥3:1），
> 不必再把轨道提亮。提亮反而让「已播 vs 未播」的差从 4.13:1 缩小、读起来更弱。
> **本行保留是历史记录，不是待做项。**

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

它是**求值式**的（`parseStops` 读源码真实停靠 + `minAlphaOverInkBand` 按布局栈常量取带），
所以改完自动按新几何重算。下表是代入 `ContrastMath.kt:84-90` 原公式、用两态真实层高
（118/126dp）与墨迹带（距底 19~31dp / 27~39dp）实算的：

| 断言 | 现状 | **已落地五档** | v2 八档 | 判 |
|---|---|---|---|---|
| `currentGradientKeepsInkBandReadable`（≥0.535） | 0.7736 | **0.8800** | 0.8713 | ✅ |
| `currentGradientMeetsWcagAaForPureWhiteText`（≥4.5:1） | 13.57:1 | **16.56:1** | 16.14:1 | ✅ |
| `normalStateTextAlphaStaysAboveComponentFloor`（≥4.3） | 9.95:1 | **14.15:1** | 13.81:1 | ✅ |
| 同条性质自检（差 >0.3） | 1.46 | **2.41** | 2.33 | ✅ |
| `evenlySpacedLegacyStopsWouldFailAtCurrentGeometry`（<0.535） | 0.2962 | **0.2962** | 0.2962 | ✅ 未失效 |
| `parserActuallyReadsRealValues` | — | 起 0 止 1、单调不减 | 同 | ✅ |

未播轨（白42%，非文本 3:1 判据）：现状 **1.77:1** → **4.01:1**（两态一致）；
若提到白50% 则 **5.11:1**。

**四条余量全部变大 ⇒ 不存在"改松凑绿"的动机。**

### 5.1 断言落点表（落地后逐条核对）

> Admin 要求：这些数**不能只写在文档里**——测试跑完若算出别的数，要能一眼分清
> 「实现错」还是「期望错」。下表就是那张对照卡。**测试源码一个字节都不用改**；
> 若某条算出的数与此表不符，**先怀疑实现**（停靠点写错、层高常量变了、轨道位置挪了），
> **不要改断言**。

| 断言（`PlayerOverlayScrimContrastTest.kt`） | 行 | 阈值 | **期望实算值** | 不符时先查 |
|---|---|---|---|---|
| `currentGradientKeepsInkBandReadable` | `:295` | ≥ 0.535 | **0.8800** | `PlayerCtrlBarScrimBrush` 是否仍是五档；`PlayerCtrlLayerHeight(_Full)` 是否仍 118/126 |
| `currentGradientMeetsWcagAaForPureWhiteText` | `:314` | ≥ 4.5 | **16.56:1** | 同上（它复用同一条 `minAlphaOverStates`） |
| `normalStateTextAlphaStaysAboveComponentFloor`（常态字 0.92） | `:390` | ≥ 4.3 | **14.15:1** | `textAlpha = 0.92` 是否仍传入 |
| 同条性质自检 | `:401` | 差 > 0.3 | **2.41** | 若算出 0 ⇒ `textAlpha` 没进公式，是**测试自检**红 |
| `evenlySpacedLegacyStopsWouldFailAtCurrentGeometry` | `:336` | < 0.535 | **0.2962** | 若 ≥ 0.535 ⇒ 判据失去分辨力（**假绿**） |
| `contrastFormulaKeepsTheSrgbComposeStep` | `:352` | 比值 ≥ 2.0 | **≈2.06** | `ContrastMath` 被改过 |
| `parserActuallyReadsRealValues` | `:413` | 层高 100..160、两态差 8、起 0 止 1、单调不减 | **118 / 126** | `Spacing.kt` 的 `xxxl/md/sm/lg` 被改 |

**已算出的补充数**（同样供核对）：未播轨白42% **4.01:1**（两态相同）、白50% **5.11:1**；
控制层平坦段 0.88 覆盖 **75.5dp/118dp**；唯一陡坡 `dα/df = 2.50`（0.24→0.36）。

---

## 6. 改动清单（照着做）

| # | 文件 | 状态 | 改动 |
|---|---|---|---|
| 1 | `VideoPlayerPane.kt:221` | ✅ 已落地 | `PlayerOverlayScrimBottomEnd = 0.88f` |
| 2 | `VideoPlayerPane.kt` | ✅ 已落地 | `PlayerOverlayScrimMid=0.44` / `UpperMid=0.58`；`Base`/`LowerMid` 已删 |
| 3 | `VideoPlayerPane.kt:1872-1879` | ✅ 已落地 | 渐变**五档**（单行符号形态，`parseStops` 可解析） |
| 4 | `VideoPlayerPane.kt` | ✅ 已落地 | `PlayerProgressDockScrim` 已删（KDoc `:1860` 记「本批已删掉黑 45% 胶囊底」） |
| 5 | `VideoPlayerPane.kt:294` | ✅ 已落地 | `PlayerSliderInactiveTrack` 保持白 **0.42**——靠 0.88 底达标 4.01:1，**不必**提到 0.50 |
| 6 | `VideoPlayerPane.kt:3136-3178` | ⬜ **待做** | 删玻璃 Box + 白环 Canvas，合并为**一个实心白圆 Canvas**（**保留 `:3034-3047` 的 `thumbScale` 四件套**） |
| 7 | `VideoPlayerPane.kt:2494-2497` | ⬜ **待做** | 删 `playerControlGlass`，改 2dp 白环；删 `PlayerPlayButtonScrim`（`:250` 仍有定义） |
| 8 | `VideoPlayerPane.kt:3295-3343` + `PlayerSegmentGroup` | ⬜ **待做** | 控件行三件改白线语言 + 按下反馈；新增 `PlayerCtrlOptionDim`(白0.60) |
| 9 | `BiliRadius.kt:151` | ⬜ **待做** | 删 `playerProgressDock`（**先 grep 全仓**） |
| 10 | `DesignTokenLintTest` | ⬜ **待做** | 记 +1 行（中央播放钮白环 2dp），按 `:176-181` 体例 |
| 11 | — | ✅ 不用动 | `GlassMountRegistryTest`（本波零漂移） |

**当前进度：1-5 已落地**（`VideoPlayerPane.kt` mtime **11:13:44**），**6-10 全部待做**。
**剩余顺序**：6（滑块）→ 7（中央钮）→ 8（控件行）→ 9（token 清理）→ 10（棘轮理由行）
→ 编译 → 跑 `:core:designsystem` + `:feature:video` → 出 10809。**一次只改一批**，改完先编译。

⚠️ **第 6 项的两个坑**（我自己差点漏，已写死做法）：
① `VideoPlayerRenderCostGuardTest.seekThumbScalesWhileDragging`（`:224-243`）用
`blockBody(pane, "private fun PlayerProgressBar(")` 取**整个函数体**，要求含
`ThumbDragScale`、`thumbScale.animateTo(`、`thumbScale.snapTo(`、
`if (dragging) ThumbDragScale else 1f` 四样。**删玻璃时极易顺手把 `thumbScale` 一起删掉
——不要删**，新的实心白圆**必须仍读 `thumbScale.value`**。
② 白环半径现在**写死**并依赖 `:3164-3167` 那条「玻璃与白环必须同心同倍率」的**约定**——
两个独立绘制节点靠约定对齐，正是「拖动态 39×11 扁形」这类破相的结构性来源。

---

## 7. 验收（10809 真机）

| # | 判据 | 三态 |
|---|---|---|
| 1 | 进展度行**无胶囊** | ✅ 静态可验（已删），待真机复看 |
| 2 | 控件行**无胶囊**、无竖线 | ⬜ **失败**（`:3295-3343` 仍用 `fill` + `PlayerControlShape`） |
| 3 | 控制层**无硬切线**（唯一陡坡 0.30α 落在 24%~36% 淡出区，**不在任何轨道或文字行上**） | 待真机 + 像素行采样 |
| 4 | 已播/未播轨一眼可辨（**4.01:1**，现状 1.77:1） | 待真机 |
| 5 | 滑块**实心白圆**、拖动放大 1.18× 不变形 | ⬜ **失败**（`:3136-3178` 两节点仍在） |
| 6 | 中央播放钮是**白环**不是半透明黑盘 | ⬜ **失败**（`:2494` 仍用 `playerControlGlass`） |
| 7 | dp 棘轮：`10/12/8.dp` **未升**，总数 957→958 且有理由行 | 待跑 |
| 8 | 浮层零彩色 | 可静态验 |

---

## 8. 诚实标注

- **未跑 Gradle、零源码改动** —— 全部落点为只读核对所得；所有对比度数字均代入
  `ContrastMath.kt:84-90` 的原公式实算，不是估算。
- **规格 v2 的八档已被五档取代**（§1.2 给了逐项对比）。我原来倾向八档是为了
  「给进度行单独加一档」，实测五档用同一个平坦段同时解决了轨道和文字两件事，
  数值还更好——**这条我认自己的方案不如落地的好**。
- **观感全部未能验证**：0.88 会不会太重、轨道白 50% 在真机上够不够醒目，只有 10809 截图能判。
- 真机手抓到的「拖动态 39×11 扁形」**我没有定论**；若确为玻璃/白环失配，本规格第 8 项从结构上消灭它。