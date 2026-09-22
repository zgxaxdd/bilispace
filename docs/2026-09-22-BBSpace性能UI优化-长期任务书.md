# BBSpace 性能 / 流畅度 / UI 优化 · 长期任务书

> **给接手的 Agent**：这是一份自包含的任务书。请先完整读一遍再动手。
> 你**没有设备**，所以本文的每一条都设计成「编译 + 单测 + 静态检查」可验证。
> 任何需要真机才能确认的结论，都必须写成「待设备验证」并给出**预期信号**，
> 不允许当成已完成。

---

## 0. 一句话目标

在**不引入回归**的前提下，把 BBSpace 的首页滚动 jank 从 1.1~1.7% 压到 **≤0.80%**、
把 UI 从"能看"提升到"成体系"，并补齐可移植的上游优化手法。

---

## 1. 环境与工程事实（已核实，可直接用）

| 项 | 值 |
|---|---|
| 仓库 | `D:\111j\c\_ref\bbspace` |
| 分支 | `build/agp9-migration`（HEAD `d7bbf677`） |
| 未提交变更 | **74 条 / 60 文件 / +3408 −1189**（全部是本任务的前置工作，见 §3） |
| 回滚点 | `stash@{0}` = `pre-upgrade-snapshot-20260921-105618` |
| 模块数 | 52 |
| Kotlin 源码 | 415 文件 / ~68,890 行 |
| 构建 | `GRADLE_USER_HOME=D:\env\gradle-home`、`JAVA_HOME=D:\env\jdk17` |
| 工具链 | AGP 9.4.0 / Gradle 9.6.0 / Kotlin 2.3.20 / R8 9.4.14 / compileSdk 37.2 / minSdk 33 |
| 上游参照 | `D:\111j\c\_ref\BiliPai`（BiliPai，已 checkout 到最新 main） |
| OPPO 文档 | `D:\oppo-doc-13771\13772_View无缝动画接入指引.md` |

**构建命令**（务必带这两个环境变量，否则会解析失败）：
```powershell
cd D:\111j\c\_ref\bbspace
$env:GRADLE_USER_HOME="D:\env\gradle-home"; $env:JAVA_HOME="D:\env\jdk17"
.\gradlew.bat :app:assembleDebug --console=plain      # 编译
.\gradlew.bat testDebugUnitTest --console=plain       # 全部单测（当前 71 个）
```

**铁律**：
1. **绝不用 PowerShell 改文件内容**。曾经用 `Set-Content -Encoding UTF8` 把
   `VideoPlayerPane.kt` 的中文写成乱码（仍是合法 UTF-8、能编译，极难发现）。
   一律用 `edit` / `write` 工具。若必须用 PowerShell，用
   `[System.IO.File]::ReadAllText($f,[Text.Encoding]::UTF8)` +
   `WriteAllText($f,$c,(New-Object System.Text.UTF8Encoding($false)))`，**并验证中文存活**。
2. **pwsh 的 `cd` 不跨调用保持**；`[System.IO.File]::*` 的路径是进程 CWD 相对，
   一律用**绝对路径**。
3. 每完成一项就跑 `testDebugUnitTest`，**必须全绿**才算完成。

---

## 2. 性能基线（release 实测，供你对照）

首页信息流滚动，4 轮，60fps 预算 16.67ms：

| 轮次 | frames | jank | p50 | p90 | p95 | p99 | slowDraw | slowUi |
|---|---|---|---|---|---|---|---|---|
| r1（预热） | 941 | 1.70% | 13ms | 15ms | 16ms | 26ms | 16 | 11 |
| r2 | 780 | 1.15% | 12ms | 15ms | 16ms | 20ms | 8 | 7 |
| r3 | 968 | **0.10%** | 9ms | 11ms | 12ms | 12ms | 1 | 0 |
| r4 | 974 | **0.00%** | 9ms | 11ms | 11ms | 12ms | 0 | 0 |

- **视频播放页**：jank 0.16%、p50 8ms、p95 9ms（已达标）
- **内存**：TOTAL PSS 235 MB
- **红线**：jank ≤0.80%、p95 ≤8ms（交接书标准）
- **设备**：OPPO PLC110 / ColorOS 16.1 / 1272×2800 / density 560 / **锁 60Hz**

> r1/r2 偏高是首轮预热（图片解码、字体加载），r3/r4 才是稳态。
> **注意方差**：同构建不同轮次能从 1.70% 掉到 0.00%，所以**不要用单轮数据下结论**，
> 至少 3 轮取中位。这也是你无法用设备验证的原因之一 —— 别在无设备时声称数字。

---

## 3. 前置工作已完成（不要重做）

以下都是本任务书之前已落地并验证过的，**你只需在此基础上继续**：

### 3.1 性能手法（移植自 BiliPai 上游）
- **帧预算棘轮** `core/designsystem/src/test/.../core/perf/FrameBudgetLintTest.kt`
  —— 7 条棘轮冻结反模式计数（`Modifier.composed{}`≤1、`Offscreen`≤0、
  `rememberInfiniteTransition`≤2、`runBlocking`≤3、`animate*AsState`≤10、`BlurEffect(`≤0），
  已实测验证"故意破坏会 FAIL"。**新增反模式必须连上限一起改并写明理由。**
- **onTrimMemory 分级**：`UI_HIDDEN` 从 `clear()` 改为 `trimToSize(50%)`，
  保留可视封面（原实现每次切后台清空 → 回前台全部重新解码）。
- **封面预加载**：`HomeVideoPage` 内 `snapshotFlow + debounce(180) + 滚动态门控 + IO 解码`。
- **Gradle 提速**：`parallel` / `caching` / `kotlin.incremental` / `nonTransitiveRClass`。
- **Coil**：磁盘缓存 256MB；`VideoCardCoverColorStore`（Palette 取封面主色）。

### 3.2 UI
- **视频详情介绍区重设计**：7 个 chip → 标题 + UP 行 + 一行统计 + 操作栏；
  AV/BV 号收进长按复制；相对时间；简介默认折叠。
- **骨架呼吸动画**：`SkeletonBlock` 整块 alpha 呼吸（1.1s，`RepeatMode.Reverse`），
  取代原「白色 14% 扫光」（浅色主题下几乎不可见）。
- **骨架圆角**：`SkeletonBarShape = RoundedCornerShape(percent = 50)`（半高胶囊）。
  **关键坑**：呼吸层用 `drawWithContent { drawRect }` 画矩形会**盖住 `background(shape=)`
  的圆角**，必须先 `clip(shape)`。
- **骨架对齐契约**（改一处必须同步另一处）：
  `VideoDetailInfoSkeleton` ↔ `VideoSummarySection` / `VideoDetailPage`：
  水平 16dp、顶部 16dp、行距 12dp、操作栏 5 项等分、头像 42dp 圆形。

### 3.3 无缝动画（OPPO View Seamless）
- 卡片锚点**铺满整卡**（封面+简介一起进 morph），用 **`PixelCopy` 点击瞬间截整卡真实像素**
  作为锚点内容（含标题文字）。
- **不要**用 `Modifier.recordCardLayer`（每帧录制 GraphicsLayer）—— 实测 jank 1.27%→4.06%。
- `tryOpen` 必须**同步**调用（官方时序：`setSeamlessView` 先于 `startActivity`），
  **绝不能放进协程**，否则无缝动画直接失效。
- `toImageBitmap()` 产出**硬件位图**，必须转 `ARGB_8888`，否则
  `Software rendering doesn't support hardware bitmaps` 导致注册失败。

---

## 4. 任务清单（按收益排序，每条都给了无设备验收标准）

### T1 ★★★ 拆掉「逐帧全屏重录链」—— 剩余 jank 的根因

**位置**
- `feature/home/.../HomeScreen.kt:200-206`
  ```kotlin
  .drawWithContent { homeGlassRefresher.frame; drawContent() }
  .then(if (homeGlassOn) Modifier.hazeSource(homeGlassState) else Modifier)
  .then(if (homeLiquidOn) Modifier.layerBackdrop(homeLiquidBackdrop) else Modifier)
  ```
- `app/.../navigation/AppNavHost.kt:681-691`：根 `Crossfade` 上同样的
  `drawWithContent { glassRefresher.frame }` + `backdropSource.modifier` + `hazeSource(glassState)`

**为什么是根因**：`drawWithContent` 读 `frame` 本身是**绘制期读状态**的正确写法，但挂载点是
**整棵内容的父节点**。Compose 的绘制失效会向下级联 → 每个滚动帧整屏重发绘制命令；
haze 的 `HazeSourceNode.draw()` 内部是 `layer.record { drawContent() }`（整屏重录进
GraphicsLayer）；LIQUID 档再叠一次 miuix `layerBackdrop` 的整屏重录 ——
**同一帧两次全屏离屏录制**（1272×2800 ≈ 3.5M px ×2）。

**要做**
1. 把悬浮顶栏移出 `Crossfade` 的 `hazeSource` 子树，让顶栏与 Dock **共用 AppNavHost 的
   `glassState`**，删掉 `HomeScreen` 私有的 `homeGlassState` + 逐帧 tick。
2. 至少在"顶栏完全滚出屏幕"时停止 tick（低风险版）。
3. **重跑 stride 实验**：`core/designsystem/.../glass/GlassRefresher.kt:36-41` 的注释断言
   "jank 不是逐帧绘制成本、抽帧无效"。但 `Number Slow issue draw commands` 统计的正是
   **绘制命令超时**的帧 —— 该结论是误诊。重新评估 stride 采样。

**无设备验收**
- `:app:assembleDebug` + `testDebugUnitTest` 全绿。
- **静态断言**：全仓 `drawWithContent` 读 glass tick 的位置**只剩 1 处**（根节点），
  写一个 `StructureTest` 固定这个事实（照抄 `FrameBudgetLintTest` 的 `countInSources` 写法，
  记得跳过注释行与 import 行）。
- 代码注释里更新 `GlassRefresher.kt` 那段误诊说明。

---

### T2 ★★★ 消除 Dock 合并动画期的整树重组

**位置** `app/.../navigation/AppNavHost.kt`
- `:841-848` `onGloballyPositioned { ... onOcclusionChanged(...) }` → `:394` 写 `bottomNavOcclusionRatio`
- `:279` 该 state 在 `:370` 的 `CompositionLocalProvider(... provides bottomNavOcclusionRatio)`
  里被**组合期读取**，读取点在 `AppNavHost` 自身 scope
- `LinkedDockPolicy.kt:60-74`：`top = (barHeight+verticalGap)*(1-merge)`、`height = top + barHeight`
  → **合并动画每帧布局高度都变** → `onGloballyPositioned` 每帧回调 → 每帧写 ratio
  → 280ms 内每帧重组整个 `AppNavHost`
- 同因：`:837` `onSizeChanged { hiddenDistancePx = ... }` 每帧写，`:806`
  `animateFloatAsState(targetValue = if (hidden) hiddenDistancePx else 0f)` 组合期读它

**要做**
- 遮挡比改测**展开态**的固定 chrome 高度（注释 `:840` 自己也说"隐藏动画只是 graphicsLayer
  平移，布局位置不变，因此常驻遮挡区恒定"），别用随 merge 变化的 `coords.size.height`。
- `bottomNavOcclusionRatio` 改经 CompositionLocal 下发 `() -> Float`，只在点击瞬间读。

**无设备验收**：`StructureTest` 断言 `onGloballyPositioned` 内**不再**出现随 merge 变化的量；
`bottomNavOcclusionRatio` 的 CompositionLocal 类型为 `() -> Float`。

---

### T3 ★★ 列表项分配与重组（收益确定、风险低）

| 位置 | 问题 |
|---|---|
| `HomeVideoPage.kt` `item.title` 的 `TextStyle.copy(...)` | 每卡每次重组都新建 TextStyle（重对象）→ 提升为文件级 `val` 或 `remember` |
| `HomeVideoPage.kt` `actionKey()` = `"$goto\|$param\|$idx"` | 每次调用新建 String，且被 `AdaptiveMediaGrid` 的 `key` lambda 调用 → 做成 `FeedItem` 的 `val`（`by lazy`） |
| `HomeVideoPage.kt` `cardShape = RoundedCornerShape(BiliRadius.card)` | 每卡每次重组新建 → 提升为文件级 val |
| `FilledTabRow.kt:157-167` `.offset(x = thumbX)` | 组合期读 `animateDpAsState` → 切 Tab 每帧重组（且它在顶栏里）→ 改 `.offset { IntOffset(thumbX.roundToPx(), 0) }` |
| `CoverExpand.kt:83-92` | `onGloballyPositioned` 每卡每帧分配 2 个 `Rect`，且分配发生在 `if (map[key] == quantized) return` **去重之前** → 先比 4 个 float 再构造 |
| `Skeleton.kt` 的 `Brush`/`listOf(3 Color)` | 若仍在每帧重建 → 提升 colors 为文件级常量 |

**无设备验收**：`StructureTest` 断言 `TextStyle(` 不出现在 `HomeVideoPage` 的 item 体内、
`actionKey` 是 `val`；`FilledTabRow` 用 `offset {` 而非 `offset(x =`。

---

### T4 ★★ 把「组合期读动画值」清零

现状：`animate*AsState` 10 处，**部分在组合期读值** → 动画每帧重组宿主。
（`graphicsLayer { }` 内读 `.value` 是**正确**写法，不要动。）

**要做**：逐一审查 10 处，把仍在组合期读的改为
`Animatable` + 在 `graphicsLayer{}` / `drawBehind{}` 内读。
参照 BiliPai 的 `core/ui/animation/AppEntrance.kt`（`ModifierNodeElement` + `placeWithLayer`）
—— **不要**抄它的 `core/util/Animations.kt:animateEnter`（外壳是 `Modifier.composed{}`，每卡每次
重组都重跑 lambda）。

**无设备验收**：把 `maxAnimateAsState` 从 10 **调小**（棘轮只许减），并在注释里列出
每一处的去向。这是本轮唯一"用数字证明改善"的项。

---

### T5 ★★ UI 设计成体系化

**现状问题**：圆角/间距/字重虽已收口到 `BiliRadius` / `BiliSpacing` / `iOSTypography`，
但**仍有大量字面量散落**。

**要做**
1. 全仓扫描 `\d+\.dp` 与 `\d+\.sp` 字面量，列出**不在 theme 包内**的清单，
   按出现频次排序；高频值收敛成 token（圆角→`BiliRadius`、间距→`BiliSpacing`、
   字号→typography）。
2. 用 `BiliRadius.topRounded/bottomRounded`（已新增）统一"卡片上下两半分角"的写法。
3. 检查**卡片圆角一致性**：`BiliRadius.card == player == 16dp` 是刻意同值
   （无缝 morph 两端同值才不跳），别"优化"掉。
4. 检查**深浅色对比度**：所有 `onSurfaceVariant` 上的正文是否 ≥4.5:1。
   可以用纯 Kotlin 写一个对比度计算 + 单测（无需设备）。

**无设备验收**：新增 `DesignTokenLintTest`，断言 theme 包外的高频字面量 ≤ 某个上限
（棘轮式），并对对比度写单测。

---

### T6 ★★ 无障碍与触控目标

- 所有可点元素 ≥48dp 触控目标（Material 规范）；当前 `ActionChip` 内容高约 34dp
  + 上下 `BiliSpacing.sm` = 42dp，**不足**。
- 关键操作（点赞/投币/收藏/分享/下载）需有 `contentDescription`（已有 label，确认语义完整）。
- 检查 `Icon` 的 `contentDescription = null` 是否都用在"纯装饰"场景。

**无设备验收**：`StructureTest` 断言可点组件的 `defaultMinSize(minHeight = 48.dp)` 或等效；
逐个核对 `contentDescription`。

---

### T7 ★ 继续移植 BiliPai 上游手法（择其可离线验证者）

上游调查报告的完整清单在 §7。**只做能离线验证的**：
- `FrameBudgetLintTest` 增加新棘轮（如 `CompositingStrategy.Offscreen`、`drawWithCache` 缺失）
- 把 `HomeFeedScrollStatePersistenceStructureTest` 那类"结构断言"补到 BBSpace
  缺的页面（首页 feed、评论、搜索历史）
- `Snapshot.withoutReadObservation` 采样滚动态（上游
  `FeedVerticalStaggeredGrid.kt:52-67`），替代 `derivedStateOf { isScrollInProgress }`

**不要抄的**（上游自己已废弃/空转，详见 §7）：baseline profile 全链路（上游产物不存在）、
scroll-lite 降级、`HomePerformancePolicy.shouldPrioritizeSmoothness`、
`DynamicLayoutPolicy.resolveDynamicSidebarBeyondBoundsItemCount`（死代码）。

---

### T8 ★ 代码逻辑审查（无设备也能做）

逐文件审 `feature/video/`（1475+706+688+686+565+435 行）与 `core/playback/`：
- 状态机是否有**不可达分支** / 状态残留
- `LaunchedEffect` 的 key 是否完备（漏 key → 陈旧闭包）
- 协程作用域是否正确（`viewModelScope` vs `rememberCoroutineScope` vs 进程级）
- `CancellationException` 是否被 `runCatching` 吞掉（项目已有
  `core/common/.../coroutines/RunCatchingCancellable.kt`，用它）
- 是否有 `!!` 在可空路径上

**输出**：一份问题清单（文件:行号 + 症状 + 修法），逐条修掉或标注"待设备验证"。

---

## 5. 验收标准（全部无需设备）

一项任务算完成，必须同时满足：

1. `:app:assembleDebug` **BUILD SUCCESSFUL**，无新增 `e:` 与 `w: ...never used`
2. `testDebugUnitTest` **全绿**（当前 71 个；你新增的算进去）
3. 该项的**静态断言测试**已落地（见各条的"无设备验收"）
4. 若动了棘轮上限：**在注释里写明理由**（照抄现有格式）
5. 代码注释解释**为什么**这么写（项目风格：每个非显然决策都有注释说明踩过的坑）

**不允许**：
- 声称任何帧率/内存数字（你测不了）
- 为了让测试通过而放宽断言
- 用 `@Suppress` 掩盖问题
- 把"待设备验证"的项标记为完成

---

## 6. 已知陷阱（前人踩过，别重蹈）

| 陷阱 | 后果 | 正确做法 |
|---|---|---|
| PowerShell 改文件 | 中文变乱码，能编译、极难发现 | 用 `edit`/`write` |
| `tryOpen` 放进协程 | **OPPO 无缝动画直接失效** | 必须同步调用 |
| `toImageBitmap()` 直接当锚点内容 | `Software rendering doesn't support hardware bitmaps`，注册失败 | 转 `ARGB_8888` |
| `Modifier.recordCardLayer` 挂列表项 | 每帧离屏渲染，jank 1.27%→4.06% | 用 `PixelCopy` 点击时截一次 |
| `drawRect` 不加 `clip(shape)` | 盖掉圆角，骨架变直角 | 先 `clip(shape)` |
| 遮罩里再画一份骨架 | 两份骨架叠加 → 淡出时跳动 | 删掉遮罩，只留一份 |
| 只改默认值不迁移 | 老用户 DataStore 里已有旧值 → 改动无效 | 加一次性迁移（见 `AppSettings.migrateTransitionStyleDefaultIfNeeded`） |
| `detailLoading` 默认 false | 首帧落在"无数据无错误"分支 → 白屏 | 默认 `true` |
| 详情 Activity 用 `ThemeConfig()` 初值 | 首帧 MATERIAL 浅色 → 白闪 | 同步预读（`preloadThemeConfig`） |
| 固定 dp 圆角用在高度差异大的块上 | 同一"16dp"在不同高度观感不同 | `RoundedCornerShape(percent = 50)` |
| `[string]$Output` 接 dumpsys 多行输出 | 被拼成一行，`^` 锚点失效，指标全空 | 不声明类型 |
| 棘轮扫描器不跳过 import 行 | `import runBlocking` 被计数，误报 | 跳过注释行**和** import 行 |
| uiautomator 的 `bounds` 解析 | `[x,y][x,y]` 先 `-replace '[\[\]]',''` 再 split | 见 §8 脚本 |

---

## 7. BiliPai 上游手法清单（调查报告摘要）

### 值得移植（可离线验证）
1. **帧预算棘轮** `FrameBudgetLintTest` —— 已移植，继续扩充
2. **Compose 稳定性配置 + 兑现它的源码守卫**：上游
   `app/build.gradle.kts:331-351` 的 `stabilityConfigurationFiles` +
   `compose_stability.conf`。**BBSpace 模型层已有 173/195 类标 `@Immutable`，所以收益不大**
   —— 先核查那 22 个未标注的类是否在组合路径上，是才做。
   注意上游 `ModelImmutabilityGuardTest.kt:80-84` 记录的坑：
   **`stabilityConfigurationFiles` 指向不存在的文件时编译照样成功、无任何警告**，
   声明静默失效 → 必须配守卫。
3. **把状态读取推到 draw/layout 阶段**：`Animatable` + `graphicsLayer{}` 内读
4. **`rememberUpdatedState` 稳定 provider 身份**：BBSpace 已有 54 处，继续核查
5. **列表项 `remember` 归并**：见 T3
6. **`Snapshot.withoutReadObservation` 采样滚动态**：见 T7
7. **骨架脉冲时钟上提**（整组一个 `infiniteTransition`）—— **BBSpace 已做对**
   （`ProvideSkeletonShimmer` 幂等）
8. **`onTrimMemory` 分级编排抽成纯函数**（便于 JVM 单测）—— 可做
9. **`BlurEffect` 半径守卫**：`graphicsLayer` 里直接 `BlurEffect(...)` 而不比较半径
   = 每帧新建 RenderEffect 并重编着色器。BBSpace 当前 0 处，**保持 0**
10. **debug-only StrictMode 用 `penaltyLog` 而非 `penaltyDeath`** —— BBSpace 已做对

### 明确不要抄（上游自己已废弃/空转）
- **baseline profile 全链路**：上游基建齐全但 **`baseline-prof.txt` 不存在**，
  `ProfileInstaller.writeProfile` 空转。**BBSpace 反而有真的 71 行手工 profile**，
  这条不是缺口。
- **scroll-lite 滚动降级**：上游所有调用点硬编码 `scrollLiteModeEnabled = false`
- **`HomePerformancePolicy.shouldPrioritizeSmoothness`**：上游标了 `// Feature retired`
- **`DynamicLayoutPolicy.resolveDynamicSidebarBeyondBoundsItemCount`**：死代码，从未接线
- **`androidx.startup`**：上游声明了但 0 处使用

### 上游也有的坏味道（别学）
- `onTrimMemory(UI_HIDDEN)` 就 `PlayUrlCache.clear()` + `clearSubtitleCueCache()`
  —— 切桌面 2-3 秒就清，切回来要重新请求带签名的 playurl。**BBSpace 用 trim 更对。**
- `VideoCardCoverColorStore` 每张卡拷一份 Bitmap 给 Palette
- `PlayUrlCache` 全 `@Synchronized` + 锁内字符串拼接日志

---

## 8. 可用工具（本仓库已有）

| 脚本 | 用途 |
|---|---|
| `scripts/perf-baseline.ps1` | 设备侧 gfxinfo 基准（无设备用不上，但**读数逻辑**可参考） |
| `scripts/analyze_transition.py` | 录屏逐帧分析动画（需 ffmpeg + PIL，无设备用不上） |
| `core/designsystem/src/test/.../FrameBudgetLintTest.kt` | **棘轮的范本**，写新结构断言照抄它 |

写新 `StructureTest` 时**务必照抄** `FrameBudgetLintTest` 的两个设计：
1. `countInSources` 跳过注释行 + import 行
2. `scannerActuallyReadsSources` 断言真扫到了 >100 个文件
   （否则路径一变就"全部通过"—— 最危险的假绿）

`repoRoot` 推导：单测 working dir 是模块目录，向上找含 `settings.gradle.kts` 的目录。

---

## 9. 建议的执行顺序

```
T3（低风险、收益确定、立刻能做）
  ↓
T5 + T6（UI 体系化 + 无障碍，纯静态可验证）
  ↓
T4（组合期读动画值清零，能用棘轮数字证明）
  ↓
T1 + T2（架构级，收益最大但风险最高 —— 必须最后做，且做完立刻跑全量单测）
  ↓
T7 + T8（查漏补缺）
```

**每完成一项就 commit**（当前 74 条未提交变更已经太大，混在一起无法回滚）。
建议提交信息格式：`perf(home): 拆掉逐帧全屏重录链`、`refactor(ui): 圆角 token 收敛`。

---

## 10. 最终交付物

1. 改动后的代码（全部通过 §5 验收标准）
2. **一份报告**，逐条列出：
   - 做了什么、为什么这么做（含踩到的坑）
   - 每项的静态验证证据（哪个测试、断言了什么）
   - **明确列出"待设备验证"项**，每项写清"预期信号"（如"预期 jank 从 1.15% 降到 ≤0.8%"）
   - 未完成项及原因
3. 若动了棘轮上限，列出所有变更及理由

---

## 附：给设备回归者的交接清单（留给有设备时用）

```powershell
$adb="D:\env\android-sdk\platform-tools\adb.exe"; $pkg="com.naaammme.bbspace"
# 性能基准
cd D:\111j\c\_ref\bbspace; .\scripts\perf-baseline.ps1 -Pkg $pkg -Rounds 4
# 全流程（每步验证焦点）
#   启动 → 切 4 Tab → 滚动 6 次 → 动态定位卡片点击 → 控件交互 → 返回 → 点小横条 → 再返回
# 日志
$pid_ = (& $adb shell pidof $pkg | Out-String).Trim()
& $adb logcat -d --pid=$pid_ | Select-String "FATAL EXCEPTION|ANR in|AndroidRuntime:"
# 无缝动画（release 下这些日志被 R8 裁掉，需 debug 包）
& $adb logcat -d -s OplusViewSeamless:* 
```

**卡片动态定位**（滚动后坐标会变，别用硬编码）：
```powershell
& $adb shell uiautomator dump /sdcard/c.xml; & $adb pull /sdcard/c.xml D:\_c.xml
[xml]$x = Get-Content D:\_c.xml -Encoding UTF8
foreach ($n in $x.SelectNodes("//node")) {
  if ($n.clickable -ne "true") { continue }
  if ($n.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { continue }
  $x0=[int]$Matches[1]; $y0=[int]$Matches[2]; $x1=[int]$Matches[3]; $y1=[int]$Matches[4]
  if (($x1-$x0) -ge 500 -and ($x1-$x0) -le 700 -and ($y1-$y0) -ge 550 -and ($y1-$y0) -le 800) {
    & $adb shell input tap ([int](($x0+$x1)/2)) ([int](($y0+$y1)/2)); break
  }
}
```
