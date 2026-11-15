# 播放页白/黑 alpha 全量定性表 + `indication = null` 分类

> 出品：设计师。2026-10-17 13:5x。接 @bbspace开发项目 的裁定：
> **13 个白值逐个定性（进刻度 / 残留即删 / 归属错层）+ `clickable` 分类。**
> 基线：`VideoPlayerPane.kt` mtime 13:19:22，遮罩已落 `0.50f`(`:1885`)。
> 方法：`grep -n "Color\.\(White\|Black\)\.copy(alpha" ` **逐行**列出，**不按语义挑选**。

---

## 0. 先纠正两条计数（两处对账口径必须统一）

| 项 | 我先前的说法 | **实测** | 差因 |
|---|---|---|---|
| 播放页 alpha 总数 | "18 个浮层常量" | **23 处字面量**（`grep -n` 逐行） | 我只数了**具名 `private val`**，漏了函数体内的内联值 |
| `.clickable(indication = null)` | 引用了别人给的 "37 处" | **9 处** | 那 37 处是全仓 `.clickable(` 总数，不是 `indication = null` |

⚠️ **两处都是"拿子集当全集"** —— 台账员要求对账是对的。
**本表口径：凡 `Color.White|Black.copy(alpha` 命中行，一行不落，逐行定性。**

---

## 1. 23 处逐行定性表

| 行 | 值 | 所在 | **定性** | 处置 |
|---|---|---|---|---|
| 201 | 黑 0.66 | `PlayerOverlayScrimTopEnd` | **渐变档**（非叠加色） | 不进刻度 |
| 228 | 黑 0.88 | `PlayerOverlayScrimBottomEnd` | **渐变末档** | **排除刻度**（渐变≠叠加底） |
| 246 | 黑 0.44 | `PlayerOverlayScrimMid` | 渐变档 | 不进刻度 |
| 249 | 黑 0.58 | `PlayerOverlayScrimUpperMid` | 渐变档 | 不进刻度 |
| 1837 | 黑 0.58 | 顶栏 `Brush` 停靠 | 渐变档 | 不进刻度 |
| 251 | 黑 0.30 | `PlayerPillScrim` | **浮层底 L2** | → **黑 0.38** |
| 262 | 黑 0.72 | `PlayerToastScrim` | **浮层底 L3** | ✅ 保持 |
| 308 | 黑 0.72 | `PlayerSliderPreviewScrim` | **浮层底 L3** | ✅ 保持（与 262 已一致） |
| 1815 | 黑 0.16 | `SwipeRailBackingColor` | **浮层底 L1**（图标背盘） | ✅ 保持 |
| 1821 | 黑 0.32 | `SwipeRailAvatarFallbackColor` | **归属错层**（头像占位，非浮层底） | → **删**，改用 `SwipeRailBackingColor` 0.16 |
| 2037 | 黑 0.40 | `scrimColor`（`:2020` 弹层） | **归属错层**（弹层底，非浮层块） | → 归 `biliSheetShape` 体系，**本波不动** |
| 252 | 白 0.16 | `PlayerPillBorder` | 描边 → 归 `overlayPressed` 档 | → **白 0.18** |
| 253 | 白 0.68 | `PlayerPillTextDim` | 弱化文字 | → **白 0.60** |
| 254 | 白 0.90 | `PlayerSecondaryText` | 次要信息 | → **白 0.92** |
| 299 | 白 0.42 | `PlayerSliderInactiveTrack` | 未播轨 | ✅ 保持（已算达标） |
| 302 | 白 0.10 | `PlayerSliderDisabledTrack` | **禁用轨** | → **白 0.24**（= `disabled` 档） |
| 305 | 白 0.24 | `PlayerSliderDisabledThumb` | 停用滑块 | ✅ 保持（`disabled` 基准） |
| 365 | 白 0.60 | `PlayerCtrlOptionDim` | 可点选项常态 | ✅ 保持（刻度基准） |
| 3218 | 白 0.18 | `PlayerCtrlPressOverlay` | 按压叠色 | ✅ 保持（按压基准） |
| 1818 | 白 0.7 | `SwipeRailAvatarRingColor` | 头像环 | → **白 0.92**（次要信息档） |
| 523 | 白 0.05 | 液态玻璃染色 | **玻璃链内部**，非叠加色 | 不进刻度（玻璃整线作废中） |
| 1520 / 1553 | 白 0.38 | 点赞态"待确认" | ⚠️ **不是弱化文字，是"状态未定"** | → **白 0.24**（`disabled` 档） |
| **1970** | 白 **0.86** | 作者名 `@名` | ⚠️ **归属错层 —— 页面内文字** | 归 `BiliColorRoles`，**不进浮层刻度** |
| **1980** | 白 **0.82** | 联合创作 chip | ⚠️ **归属错层 —— 页面内文字** | 同上 |
| **2013 / 2023** | 白 **0.72** | `#话题` / `合集·` | ⚠️ **归属错层 —— 页面内文字** | 同上 |

### 1.1 结论：**"13 个白值"里有 5 个根本不是浮层**

`0.86`(`:1970` 作者名) / `0.82`(`:1980` chip) / `0.72`(`:2013` 话题、`:2023` 合集)
**全部是压在视频上的**页面内文字**（作者行 / 标签行），它们应该走
`BiliColorRoles.secondaryLabel` 一类的页面角色色，不该自己写 alpha 字面量。

⇒ **这就是"不统一"的第二层根因**：不只是浮层刻度缺一层，
**页面内浮层上的文字也没走 `BiliColorRoles`**，所以又长出 5 个私有 alpha。
⚠️ 这 5 处**本波不动**（`BiliColorRoles` 在 designsystem、这些在 feature，
跨层改动要单独评估），但**必须登记**，否则"统一"做完还剩五个孤儿。

---

## 2. `.clickable(indication = null)` 分类（实测 **9 处**，非 37）

| 文件 | 处 | **归属** | 处置 |
|---|---|---|---|
| `IOSAlertDialog.kt` | 2 | **页面内**（弹窗按钮） | 归 `BiliColorRoles`，**不套浮层刻度** |
| `IOSActionSheet.kt` | 1 | 页面内 | 同上 |
| `IOSControls.kt` | 1 | 页面内（`IOSSwitch`） | 同上 |
| `FilledTabRow.kt` | 1 | 页面内 | 同上 |
| `AppNavHost.kt` | 1 | 页面内 | 同上 |
| **`VideoPlayerPane.kt`** | **1** | **浮层**（`playerCtrlPress` 自己管按压态 `:3286`） | ✅ 正确，**保持** |
| `LivePlayerPane.kt` | 1 | **浮层**（另一个播放器） | ⚠️ **与主播放器不同步** |
| `DownloadPlayerPane.kt` | 1 | **浮层**（下载页播放器） | ⚠️ **同上** |

**关键结论**：**9 处里只有 3 处是浮层**，且**主播放器只有 1 处**。
⇒ **`indication = null` 不是问题**，我先前引用"37 处"是**把 `.clickable(` 总数当成了它**，撤回。

⚠️ **但发现一处真问题**：`LivePlayerPane.kt` 与 `DownloadPlayerPane.kt`
**各有一套自己的播放页控件**。本轮所有控制行改动只落在 `VideoPlayerPane.kt`，
⇒ **三个播放器的控制层语言会分叉**。这是"统一"里**比 alpha 更硬的一处断层**，
建议登记为**第二批的第一个对象**（直播/下载播放页按主播放器的规格对齐）。

---

## 3. 本波落地清单（零新增常量、零新增 dp）

| # | 改动 | 行 | 动作 |
|---|---|---|---|
| 1 | `PlayerPillScrim` 0.30 → **0.38** | `:251` | 改值 |
| 2 | `PlayerPillTextDim` 0.68 → **0.60** | `:253` | 改值 |
| 3 | `PlayerPillBorder` 0.16 → **0.18** | `:252` | 改值 |
| 4 | `PlayerSecondaryText` 0.90 → **0.92** | `:254` | 改值 |
| 5 | `PlayerSliderDisabledTrack` 0.10 → **0.24** | `:302` | 改值 |
| 6 | `SwipeRailAvatarRingColor` 0.70 → **0.92** | `:1818` | 改值 |
| 7 | 点赞"待确认" 0.38 → **0.24** ×2 | `:1520`/`:1553` | 改值 |
| 8 | 删 `SwipeRailAvatarFallbackColor`(黑0.32)，改用 `SwipeRailBackingColor` | `:1821` | **删常量** |
| 9 | `PlayerPillShape` 删除，`PlayerPillChip` 改用 R2(17dp) | `:268`/`:3420` | 删重复定义 |

**净新增常量 −1、dp 0。** 第 8 项落地前**先 grep** `SwipeRailAvatarFallbackColor` 有无其他消费者。

---

## 4. 明确**不做**的三件（防又落一版又推翻）

| 不做 | 理由 |
|---|---|
| 5 处页面内浮层文字（0.86/0.82/0.72×2）归 `BiliColorRoles` | 跨模块（feature → designsystem），要单独评估 |
| 3 个播放器对齐 | 本波只动主播放器；直播/下载是第二批 |
| grep 守卫 | 会一次性点红三十多个现有字面量，**必须与第二批同批落** |

---

## 5. 诚实标注

- **本表 23 行逐行来自 `grep -n` 实测**，行号可复核；**没有按语义挑选**。
- **两处计数自我纠正**：23 处（不是我先前的 18）、`indication=null` 9 处（不是引用的 37）。
- 第 8 项（删常量）**未验证是否有其他消费者**，落地时须先 grep。
- **三个播放器的控制层语言分叉 = 已证实的断层**，但本波不修。
