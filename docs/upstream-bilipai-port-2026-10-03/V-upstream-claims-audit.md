# 上游 BiliPai 侧断言核验报告（V-upstream-claims-audit）

- 核验对象：上游 v0.2.4 worktree `D:\111j\c\_ref\BiliPai-024`，`origin/main` = `6e6bdce36`
- 核验方式：**逐条打开文件读到当前实际行号**，不依赖文件名、提交标题或提交统计摘要
- 仓库只读：本次核验**未修改任何文件**
- 核验时间：2026-10-03

---

## ① 总表

| 编号 | 断言摘要 | 判定 | 证据（文件:行号） | 若错，真相是什么 |
|---|---|---|---|---|
| A1 | `MiniPlayerManager.kt` 在 `c1ff890df` 新增约 59 行，含 `backgroundOptimizationPlayer` 字段、7 处重置、3 处 `player !== currentPlayer` 守卫 | ⚠️ 部分属实 | `app/.../feature/video/player/MiniPlayerManager.kt:787, 854, 885, 1020, 1339, 1350, 1820, 2098, 2196, 2260, 940, 1114, 1152` | 字段✅、3 处守卫✅；**"7 处重置"❌ 实为 8 处调用点**；"新增约 59 行" 实为 **+51/-8**（59 是 numstat 总变动=51+8） |
| A2 | `feature/video/playback/policy/` 下存在 `PlaybackHeartbeatPolicy.kt`，含 5 个 heartbeat 函数 | ⚠️ 部分属实 | `app/.../playback/policy/PlaybackAncillaryTaskPolicy.kt:24, 36, 48, 59, 78`；同名文件在 `core-data/.../data/repository/PlaybackHeartbeatPolicy.kt:3, 10` | 该目录下**不存在** `PlaybackHeartbeatPolicy.kt`（只有 6 个 policy 文件）。5 个函数全部位于 `PlaybackAncillaryTaskPolicy.kt`。`core-data` 下的同名文件内容完全不同 |
| A3 | `PlaybackAncillaryTaskPolicy.kt:21` 是 `return if (isInBackground) 90_000L else 30_000L`；若它是心搏间隔则"后台不发心搏"为错 | ⚠️ 部分属实（推论方向相反） | `PlaybackAncillaryTaskPolicy.kt:20-22`；唯一调用点 `VideoPlaybackViewModel.kt:5646` | 行号与字面✅。函数名是 **`resolveOnlineCountPollingDelayMs`**——**在线人数轮询**间隔，**不是心搏间隔**。心搏有独立的 30s 固定循环（`VideoPlaybackViewModel.kt:8940`）且后台被策略直接拦掉。故"后台不发心搏"**本身上游是成立的**，题面预设的反证不成立 |
| A4 | `VideoPlaybackViewModel.kt:1789-1793` 是那 5 个 heartbeat 字段 | ✅ 属实 | `VideoPlaybackViewModel.kt:1789-1793` | 5 个字段名与行号逐一对应。心搏循环 delay = `delay(30_000)` @ **8940**（固定值，无后台分支） |
| B1 | `PortraitFullscreenOverlay.kt` 在 `6cac80f45` +179 行，含 `coCreators`/`isCoCreation`/`"等 N 人联合创作"`/`PortraitStaffSheet` | ✅ 属实（1 处数字偏差） | `PortraitFullscreenOverlay.kt:669, 672, 728, 799, 815` | 全部存在。提交中该文件实为 **+178/-1**（179 是总变动） |
| B2 | `SystemBarCompat.kt` 新增 `Context.findHostActivity()`（约 +12 行 / 9 行函数体） | ✅ 属实 | `app/.../core/ui/SystemBarCompat.kt:93-101`；提交 `4a79d46bb` 该文件 `12 0` | 签名 `internal fun Context.findHostActivity(): Activity?` @94，函数体 95-100（6 行语句 / 含签名括号 8 行，"9 行"略高）。注意另有 2 份**重复实现**：`core/util/WindowSizeUtils.kt:314`、`feature/video/screen/AudioModeScreen.kt:146` |
| B3 | 存在 `resolveVideoListColumns` 且支持 3 列 | ✅ 属实 | `app/.../core/ui/components/VideoListLayoutControl.kt:119-121` | `internal fun resolveVideoListColumns(singleColumn: Boolean, availableWidthDp: Float): Int`；档位 **1/2/3**，3 列需 ≥960dp（`(w/320f).toInt().coerceIn(2,3)`） |
| B4 | 存在 `resolveBottomInputOverlayMaxWidthDp`，<600 同宽否则 640 | ✅ 属实 | `app/.../feature/video/ui/components/InputOverlayLayoutPolicy.kt:12-13` | `return if (windowWidthDp < 600) windowWidthDp else 640`；常量确为 **640** |
| C1 | `feature/home/HomeFeedMergePolicy.kt:23-42` 存在，web/app 交错合并 + 按视频去重 | ✅ 属实 | `app/.../feature/home/HomeFeedMergePolicy.kt:23-42, 48-54` | `fun mergeFeeds(web: List<VideoItem>, app: List<VideoItem>): List<VideoItem>`；**交错**（每轮先 `app[i]` 后 `web[i]`，非分段）；去重键 `keyOf`：dynamicId > bvid > aid > id > "mid:title:pubdate" |
| C2 | `fetchMergedWebFeed` 用 `fresh_type=4`/`feed_version=V8`/`brush=idx`；`fetchWebFeed` 用 `fresh_type=3` | ✅ 属实 | `VideoRepository.kt:667-668`（Web）；`:760, 764, 765`（Merged） | Web：`fresh_type=3`、`feed_version=System.currentTimeMillis()`；Merged：`feed_version="V8"`、`brush=idx`、`fresh_type="4"`。注释与代码一致 |
| C3 | `RecommendItem` 无 `idx` 字段、无三连点/负反馈上下文 | ✅ 属实（**高危限定**） | `core-data/.../response/ListModels.kt:312-333`（RecommendItem）；对照 `:626-636, 674-700` | `RecommendItem` 全字段：id/bvid/cid/goto/uri/pic/title/duration/pubdate/owner/stat/tid/tname/dimension/rights/isFollowed —— 确无 `idx`、无负反馈。**但同文件 `MobileFeedItem`（626）有 `idx:627` 与 `@SerialName("three_point_v2") threePointV2:636`，并在 674-700 构造 `RecommendationFeedbackMetadata`（FEEDBACK/DISLIKE @679-680）**。若据此断言"上游无 idx/无负反馈能力"即为错误 |
| C4 | `ApiClient.kt:1330` 是 `@POST("x/relation/modify")`；`BlockedUpRepository.kt:461` 是拉黑同步（act=5/6） | ✅ 属实 | `ApiClient.kt:1330, 1333`；`BlockedUpRepository.kt:17-18, 450, 459, 461` | 1330 为 `@retrofit2.http.POST("x/relation/modify")`✅；`BILIBILI_RELATION_ACT_BLOCK=5`(17)/`UNBLOCK=6`(18)，461 是 `act =` 实参行；**确实发起网络请求**（`api.modifyRelation(...)` @459，位于 `modifyBilibiliBlockedList` @450），非仅改本地 |
| D1 | `miuix-navigation` 约 4502 行 / 38 文件（`483099f98` 新建） | ⚠️ 部分属实 | `miuix-navigation/**`；提交 `483099f98` numstat | 模块确由该提交新建（全部 `+N/-0`），但提交主题是 floating video cards。**实际 34 文件 / 4483 行**（30 个 `.kt` / 4174 行）；提交时 33 文件（`UPGRADE_REVIEW.md` 为后续新增）。**"38 文件"❌**；"约 4502 行"偏差 19 行 |
| D2 | `CdnPlaybackDataSource` 约 371 行且**无消费者** | ❌ 不属实 | `feature/plugin/CdnPlaybackDataSource.kt:30`（371 行 ✅）；消费者 `core/player/PlaybackMediaCache.kt:15, 80, 123` | 行数✅，但**有真实消费者**：`PlaybackMediaCache.kt:15` import；`:80` 在 `buildCachedDataSourceFactory` 内实例化并 `addTransferListener`；`:123` 在 `prefetchRange` 内实例化（`background=true`）；另有 `CdnPlaybackDataSourceTest.kt`。它是播放缓存 DataSource 链的生产组件 |
| D3 | 弹幕引擎依赖是 `com.bytedance.danmaku.render.engine`（非 `master.flame.danmaku`） | ⚠️ 部分属实 | `danmaku-engine/build.gradle.kts:7`；`app/build.gradle.kts:369, 462-463`；`gradle/libs.versions.toml`（全 45 行） | 结论✅：namespace 为 `com.bytedance.danmaku.render.engine`，全仓 0 处 `master.flame`/`flame.danmaku`。但**不是 `libs.versions.toml` 依赖**——该表 45 行内 0 处 danmaku/flame 条目；引擎是**仓内模块** `:danmaku-engine`，由 `implementation(project(":danmaku-engine"))` 引入（"内部维护的 ByteDance DanmakuRenderEngine 分支；上游已归档"）。题面要求的"libs.versions.toml 行号与依赖坐标"**不存在** |
| D4 | 用户协议正文写死 GPL v3 与 Firebase 披露 | ✅ 属实 | `feature/agreement/UserAgreementText.kt:18, 21-23, 25, 38-41` | GPL v3 见 18/21/22/23/25；Firebase（Crashlytics + Analytics）披露见 38-41 |
| D5 | bbspace `FilledTabRow` 用 `weight(1f)` 等分、天然不溢出 | ✅ 属实（需 2 项限定） | `bbspace/core/designsystem/.../component/FilledTabRow.kt:105, 159, 194, 207` | Material 分支 `Card(modifier = Modifier.weight(1f))` @105 在 `tabs.forEachIndexed` 内等分✅；iOS 分支 @194 `.weight(1f)` **且** @159 `val segmentWidth = maxWidth / tabs.size`。"天然不溢出"横向成立，但机制不止 weight：iOS 文本 @207 `maxLines = 1`（裁切），Material 文本 @115-128 **无 maxLines**（靠换行消化，行高会增长） |

**判定统计**：✅ 属实 12 条 · ⚠️ 部分属实 6 条 · ❌ 不属实 1 条（共 19 条）

---

## ② 逐条详述

### A1 — MiniPlayerManager 后台优化（⚠️ 部分属实）

准确文件路径：`app/src/main/java/com/android/purebilibili/feature/video/player/MiniPlayerManager.kt`

提交核对（`git show --numstat c1ff890df`）：

```
51	8	app/src/main/java/com/android/purebilibili/feature/video/player/MiniPlayerManager.kt
```

→ **新增 51 行、删除 8 行**；`--stat` 显示的 `59` 是二者之和（总变动行数），不是"新增 59 行"。

字段声明（✅ 属实）：

```kotlin
787:     private var backgroundOptimizationPlayer: Player? = null
```

"3 处 `player !== currentPlayer` 守卫"（✅ 属实，且 3 处均为本提交新增）：

```kotlin
 940:             if (player !== currentPlayer || backgroundOptimizationPlayer !== currentPlayer) return@launch
1114:         if (player !== currentPlayer || backgroundOptimizationPlayer !== currentPlayer) return
1152:         if (player !== currentPlayer || backgroundOptimizationPlayer !== currentPlayer) return
```

（另有 3 处形如 `backgroundOptimizationPlayer !== <x>` 但左侧不是 `player !== currentPlayer` 的守卫：877、975、1019。）

**"7 处重置" ❌ 不属实。** `resetBackgroundOptimizationState()` 的**调用点共 8 处**，全部在本提交新增：

```kotlin
 854:     private fun resetBackgroundOptimizationState() {      // ← 定义，非调用点
 885:         resetBackgroundOptimizationState()                // onEnterBackground
1020:             resetBackgroundOptimizationState()            // onEnterForeground
1339:             resetBackgroundOptimizationState()            // clearExternalPlayerIfMatches
1350:         resetBackgroundOptimizationState()                // resetExternalPlayer
1820:             resetBackgroundOptimizationState()            // 释放/结束播放
2098:         resetBackgroundOptimizationState()                // dismiss
2196:         if (player !== externalPlayer) resetBackgroundOptimizationState()
2260:         if (player !== externalPlayer) resetBackgroundOptimizationState()
```

计数口径对照（**无任何一种口径得出 7**）：

| 口径 | 数量 |
|---|---|
| 提交新增的调用点 | **8** |
| 当前文件调用点总数 | **8** |
| 含定义在内的文本出现处 | 9 |
| 函数体内被重置的状态量 | 10 |
| 提交新增的相关代码行总数（含定义/字段/守卫） | 12 |

### A2 — PlaybackHeartbeatPolicy 位置（⚠️ 部分属实）

`feature/video/playback/policy/` 下实际只有 6 个文件：`AdaptiveDashTrackPolicy.kt`、`PlaybackAncillaryTaskPolicy.kt`、`PlaybackPostLoadPlan.kt`、`PlaybackPowerPolicy.kt`、`PlaybackTransitionPolicy.kt`、`PluginPollingPolicy.kt`。**不存在 `PlaybackHeartbeatPolicy.kt`。**

5 个 heartbeat 函数全部位于 `app/src/main/java/com/android/purebilibili/feature/video/playback/policy/PlaybackAncillaryTaskPolicy.kt`：

```kotlin
24: internal fun shouldSendPlaybackHeartbeat(
36: internal fun shouldSendInitialPlaybackHeartbeat(
48: internal fun resolvePlaybackHeartbeatSessionStartTsSec(
59: internal fun resolvePlaybackHeartbeatSnapshot(
78: internal fun shouldFlushPlaybackHeartbeatSnapshot(
 3: internal data class PlaybackHeartbeatSnapshot(
```

另有一个**同名但不同内容**的文件存在于 core-data：

`core-data/src/main/java/com/android/purebilibili/data/repository/PlaybackHeartbeatPolicy.kt`（共 53 行，package 为 `com.android.purebilibili.data.repository`）：

```kotlin
 3: fun resolvePlaybackHeartbeatPlayType(
10: fun buildPlaybackHeartbeatFields(
```

→ 该文件只管**上报报文字段拼装**（`played_time`/`real_played_time`/`start_ts`/`play_type` 等），**不含**上述 5 个判定函数。因此断言若指"policy 目录下有此文件"为假；若指"存在同名文件"为真但会误导（模块、package、职责全不同）。

### A3 — PlaybackAncillaryTaskPolicy.kt:21 的真实语义（⚠️ 部分属实，且推论方向相反）

行号与字面**完全属实**：

```kotlin
20: internal fun resolveOnlineCountPollingDelayMs(isInBackground: Boolean): Long {
21:     return if (isInBackground) 90_000L else 30_000L
22: }
```

**函数名：`resolveOnlineCountPollingDelayMs`。它不是心搏间隔，而是在线人数轮询间隔。**

唯一调用点（生产路径）：

```kotlin
5631:                         val response = com.android.purebilibili.core.network.NetworkModule.api.getOnlineCount(bvid, cid)
...
5646:                 delay(resolveOnlineCountPollingDelayMs(isInBackground = BackgroundManager.isInBackground))
```

（测试断言同为在线人数语义：`PlaybackAncillaryTaskPolicyTest.kt:40-41`。）

心搏间隔另有其值，且**不是** 90s：

```kotlin
8939:             while (true) {
8940:                 delay(30_000)
8941:                 if (reportPlaybackHeartbeatSnapshot(forceFlush = false, reason = "interval")) {
```

**结论**：题面"如果是心搏间隔，则'后台不发心搏'的说法就错了（后台是 90s 发一次）"的推论**前提不成立**。真相反而是：上游**后台确实不发心搏**，由两个策略函数硬性把关——

```kotlin
30:     return isPlaying &&
31:         !isInBackground &&          // ← shouldSendPlaybackHeartbeat
...
42:     return isActivelyPlaying &&
43:         !isInBackground &&          // ← shouldSendInitialPlaybackHeartbeat
```

后台每 90s 发的是**在线人数轮询**，与心搏（30s，且后台被拦）是两条独立链路。

### A4 — VideoPlaybackViewModel 心搏字段（✅ 属实）

```kotlin
1789:     private var heartbeatJob: Job? = null
1790:     private var heartbeatSessionStartTsSec: Long = 0L
1791:     private var heartbeatAccumulatedPlayMs: Long = 0L
1792:     private var heartbeatActivePlayStartElapsedMs: Long? = null
1793:     private var lastReportedHeartbeatSnapshot: PlaybackHeartbeatSnapshot? = null
```

5 个字段名、类型、行号与断言完全一致。**心搏循环体 delay 的实际值 = `30_000`（30 秒），位于 `VideoPlaybackViewModel.kt:8940`**，固定常量，无任何 background 分支。

### B1 — PortraitFullscreenOverlay 联合创作（✅ 属实，1 处数字偏差）

文件：`app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PortraitFullscreenOverlay.kt`

```kotlin
668:         // 联合创作：UP 之外的成员；官方样式为多头像叠放 + 「等 N 人联合创作 ˅」
669:         val coCreators = remember(staff, ownerMid) {
672:         val isCoCreation = staff.size > 1 && coCreators.isNotEmpty()
728:                     text = "等 ${staff.size} 人联合创作 ˅",
799:             PortraitStaffSheet(
815: private fun PortraitStaffSheet(
```

- `coCreators`：**定义 @669**（另在 699 使用）
- `isCoCreation`：**定义 @672**（另在 694/725/798 使用）
- 字符串：**@728**，确为**模板字符串** `"等 ${staff.size} 人联合创作 ˅"`
- `PortraitStaffSheet`：**确实存在**，定义为 `private fun PortraitStaffSheet(` @815，调用 @799

提交偏差：`git show --numstat 6cac80f45` 该文件为 `178 1` → **+178/-1**，断言"+179 行"是总变动（178+1）。

### B2 — SystemBarCompat.findHostActivity（✅ 属实）

文件：`app/src/main/java/com/android/purebilibili/core/ui/SystemBarCompat.kt`

```kotlin
93: /** Unwraps [ContextThemeWrapper]-style chains to the hosting [Activity]; null when detached. */
94: internal fun Context.findHostActivity(): Activity? {
95:     var context = this
96:     while (context is ContextWrapper) {
97:         if (context is Activity) return context
98:         context = context.baseContext
99:     }
100:     return null
101: }
```

**签名：`internal fun Context.findHostActivity(): Activity?`，位于 `SystemBarCompat.kt:94`**（函数整体 93-101；函数体语句 95-100 共 6 行）。

`git blame` 显示由 `4a79d46bb` 引入；该提交对 `SystemBarCompat.kt` 为 `12 0` → **+12/-0**，与"约 +12 行"一致；"9 行函数体"略高于实测（6 行语句 / 8 行含括号）。

补充事实：同名 `private tailrec fun Context.findHostActivity()` 还有**两份重复实现**——`core/util/WindowSizeUtils.kt:314`、`feature/video/screen/AudioModeScreen.kt:146`。`SystemBarCompat.kt` 这份是被跨包复用的公共版本（`Theme.kt:41`、`FullscreenPlayerOverlay.kt:87` 均 import `com.android.purebilibili.core.ui.findHostActivity`）。

### B3 — resolveVideoListColumns（✅ 属实）

文件：`app/src/main/java/com/android/purebilibili/core/ui/components/VideoListLayoutControl.kt`

```kotlin
115: /**
116:  * 视频列表列数策略：用户单列偏好优先；网格模式下每列保证 ≥320dp 的最小可读卡宽，
117:  * ≥960dp 的大窗口推导出第 3 列并封顶，避免大平板内容利用不足。
118:  */
119: internal fun resolveVideoListColumns(singleColumn: Boolean, availableWidthDp: Float): Int {
120:     if (singleColumn || availableWidthDp < 320f) return 1
121:     return (availableWidthDp / 320f).toInt().coerceIn(2, 3)
122: }
```

**支持档位：1 / 2 / 3**（`coerceIn(2, 3)` 上封顶 3）。3 列阈值 = 960dp。测试印证 `VideoListLayoutPolicyTest.kt:23-25`（960/1200/1920 → 3）。

### B4 — resolveBottomInputOverlayMaxWidthDp（✅ 属实）

文件：`app/src/main/java/com/android/purebilibili/feature/video/ui/components/InputOverlayLayoutPolicy.kt`

```kotlin
12: internal fun resolveBottomInputOverlayMaxWidthDp(windowWidthDp: Int): Int {
13:     return if (windowWidthDp < 600) windowWidthDp else 640
14: }
```

语义与断言一致：**宽度 <600 时同宽（返回自身），否则 640**。**实际常量值 = 640**。测试印证 `InputOverlayLayoutPolicyTest.kt:12-13, 18-21`（599→599，600→640）。

### C1 — HomeFeedMergePolicy（✅ 属实）

文件：`app/src/main/java/com/android/purebilibili/feature/home/HomeFeedMergePolicy.kt`

**实际函数签名**：

```kotlin
23:     fun mergeFeeds(web: List<VideoItem>, app: List<VideoItem>): List<VideoItem> {
```

断言的 `23-42` 正是该函数体范围（✅）。**合并算法 = 交错（interleave），不是分段拼接**：

```kotlin
27:         val seen = hashSetOf<String>()
30:         val maxLen = maxOf(web.size, app.size)
31:         for (i in 0 until maxLen) {
32:             if (i < app.size) {
33:                 val item = app[i]
34:                 if (seen.add(keyOf(item))) merged.add(item)
35:             }
36:             if (i < web.size) {
37:                 val item = web[i]
38:                 if (seen.add(keyOf(item))) merged.add(item)
39:             }
40:         }
```

每轮先取 `app[i]` 再取 `web[i]`（注释 @18 亦为"app 在前"）；一方耗尽后另一方剩余项顺次追加；任一输入为空时安全返回另一侧（24-25）。

**去重键**（@48-54，按优先级）：

```kotlin
49:         if (item.dynamicId.isNotBlank()) return "dyn:${item.dynamicId}"
50:         if (item.bvid.isNotBlank()) return "bvid:${item.bvid}"
51:         if (item.aid > 0) return "aid:${item.aid}"
52:         if (item.id > 0) return "id:${item.id}"
53:         return "${item.owner.mid}:${item.title}:${item.pubdate}"
```

生产调用点：`VideoRepository.kt:641`。

### C2 — VideoRepository 的两套推荐参数（✅ 属实）

`fetchWebFeed`（`VideoRepository.kt:656`）：

```kotlin
666:            val params = mapOf(
667:                "ps" to refreshCount.toString(), "fresh_type" to "3", "fresh_idx" to idx.toString(),
668:                "feed_version" to System.currentTimeMillis().toString(), "y_num" to idx.toString()
669:            )
```

→ Web 单路：**`fresh_type=3`**，`feed_version` 为**当前时间戳**（非 V8）。

`fetchMergedWebFeed`（`VideoRepository.kt:748`）：

```kotlin
758:            val params = mapOf(
759:                "version" to "1",
760:                "feed_version" to "V8",
761:                "homepage_ver" to "1",
762:                "ps" to refreshCount.toString(),
763:                "fresh_idx" to idx.toString(),
764:                "brush" to idx.toString(),
765:                "fresh_type" to "4"
766:            )
```

→ Merged 路：**`feed_version="V8"`、`brush=idx`、`fresh_type="4"`**，另有 `version=1`、`homepage_ver=1`。注释 @618、@746 与代码一致（"注释里写的值可能与代码不一致"的风险在本例中**未发生**）。调用点：`VideoRepository.kt:622`（`async { fetchMergedWebFeed(...) }`），分支条件见 613/645。

### C3 — RecommendItem 字段（✅ 属实，但为高危半真）

`core-data/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt`

`RecommendItem` 全部字段（@312-333），无省略：

```kotlin
312: data class RecommendItem(
313:     val id: Long = 0,
314:     val bvid: String? = null,
315:     val cid: Long? = null,
316:     val goto: String? = null,
317:     val uri: String? = null,
318:     val pic: String? = null,
319:     val title: String? = null,
320:     val duration: Int? = null,
321:     val pubdate: Long? = null,
322:     val owner: RecommendOwner? = null,
323:     val stat: RecommendStat? = null,
324:     val tid: Int = 0,
325:     val tname: String = "",
326:     //  [新增] 视频尺寸信息 (用于判断竖屏视频)
327:     val dimension: Dimension? = null,
328:     val rights: VideoRights? = null,
329:     //  [新增] 是否已关注该 UP 主(推荐流过滤已关注豁免用; 接口返回 0/1 整数)
330:     @SerialName("is_followed")
331:     @Serializable(with = FlexibleBooleanSerializer::class)
332:     val isFollowed: Boolean = false
333: ) {
```

**明确回答**：`RecommendItem` **是否有 `idx`？→ 没有。是否有负反馈上下文？→ 没有**（无 `three_point`/`three_point_v2`/`dislike`/`feedback` 相关字段）。断言在此范围内**属实**。

**但必须附上高危限定**——同一文件的**另一个**推荐流模型具备全部这些能力：

```kotlin
626: data class MobileFeedItem(
627:     val idx: Long = 0,
...
636:     @SerialName("three_point_v2") val threePointV2: List<MobileFeedThreePoint> = emptyList(),
...
674:             recommendationFeedback = RecommendationFeedbackMetadata(
675:                 goto = goto,
676:                 param = param,
679:                         "feedback" -> RecommendationFeedbackType.FEEDBACK
680:                         "dislike" -> RecommendationFeedbackType.DISLIKE
699:                 supportsServerSync = goto.isNotBlank() && param.isNotBlank()
```

补充：`ListModels.kt:132` 的 `dislike` 属于 `VideoRelationData`（视频点赞/点踩状态），与推荐流负反馈**无关**，不可误引。

→ 若用 C3 论证"上游没有 idx / 没有负反馈能力"，即为**错误**：上游能力在 `MobileFeedItem`（移动端推荐流）上，且处于生产路径——`HomeFeedMergePolicy` 交错合并的"app 半边"正是 `MobileFeedItem` 转出的 `VideoItem`（`VideoRepository.kt:641` 的 `mobileList`）。

### C4 — ApiClient 与 BlockedUpRepository（✅ 属实）

```kotlin
1328:     //  [新增] 关注/取关 UP 主
1329:     @retrofit2.http.FormUrlEncoded
1330:     @retrofit2.http.POST("x/relation/modify")
1331:     suspend fun modifyRelation(
1332:         @retrofit2.http.Field("fid") fid: Long,      // UP 主 mid
1333:         @retrofit2.http.Field("act") act: Int,        // 1=关注, 2=取关, 5=拉黑, 6=解除拉黑
```

`ApiClient.kt:1330` 确为 `@POST("x/relation/modify")` ✅。

`BlockedUpRepository.kt`：

```kotlin
17: private const val BILIBILI_RELATION_ACT_BLOCK = 5
18: private const val BILIBILI_RELATION_ACT_UNBLOCK = 6
...
450:     private suspend fun modifyBilibiliBlockedList(
...
458:         return runCatching {
459:             api.modifyRelation(
460:                 fid = mid,
461:                 act = if (blocked) BILIBILI_RELATION_ACT_BLOCK else BILIBILI_RELATION_ACT_UNBLOCK,
462:                 csrf = csrf,
463:                 reSrc = resolveBlockedUpRelationReSrc(relationSource)
464:             )
```

**`act=5` = 拉黑（BLOCK），`act=6` = 解除拉黑（UNBLOCK）** ✅。461 行是 `act =` 实参行（函数整体 450-479）。**该文件确实发起网络请求**——通过 Retrofit `api.modifyRelation(...)`（@459），并由 `response.code == 0` 判定远端状态（467-473），返回 `BilibiliBlockedListRemoteStatus`；**不是**只改本地。

### D1 — miuix-navigation 规模（⚠️ 部分属实）

模块确实由 `483099f98` **新建**（该提交内 `miuix-navigation/**` 全部为新增、0 删除），但提交标题是 `Make floating video cards land on release; bump versionCode to 425`，与"新建模块"这一描述在语义上无关（只因该模块在同一提交里被一并提交）。

实测（当前 `6e6bdce36` worktree）：

| 口径 | 实测 | 断言 |
|---|---|---|
| 文件总数（含 `LICENSE`/`README.md`/`UPGRADE_REVIEW.md`/`build.gradle.kts`） | **34** | 38 ❌ |
| 全模块总行数 | **4483** | 约 4502（偏差 19） |
| `.kt` 文件数 / 行数 | **30 / 4174** | — |
| 该提交新增行合计 | 4425 | — |
| 提交时文件数 | 33（`UPGRADE_REVIEW.md` 为后续新增） | — |

→ **"38 文件" 不属实**；"约 4502 行"接近但不精确（4483）。

### D2 — CdnPlaybackDataSource（❌ 不属实）

行数 ✅：文件 `app/src/main/java/com/android/purebilibili/feature/plugin/CdnPlaybackDataSource.kt` 共 **371 行**，类声明：

```kotlin
30: internal class CdnPlaybackDataSource(
31:     private val upstream: DataSource,
32:     private val background: Boolean = false,
33:     private val client: OkHttpClient? = null,
34:     private val isWifi: () -> Boolean = { NetworkModule.appContext?.let { NetworkUtils.isWifi(it) } == true }
35: ) : BaseDataSource(true) {
```

**"无消费者" ❌ 不属实。它有 3 处生产消费 + 1 处测试：**

```kotlin
// core/player/PlaybackMediaCache.kt
 15: import com.android.purebilibili.feature.plugin.CdnPlaybackDataSource
 80:             CdnPlaybackDataSource(upstreamFactory.createDataSource()).apply {
 81:                 addTransferListener(upstreamTransferListener)
 82:             }
 123:             CdnPlaybackDataSource(upstreamFactory.createDataSource(), background = true).also { activeUpstream.set(it) }
```

- `:80` 位于 `buildCachedDataSourceFactory()`（@75）内，被 `buildCdnOptimizedDataSourceFactory()`（@97）、`buildCachedDataSourceFactory` 的调用方与 `prefetchRange()`（@111）串入 ExoPlayer 的 `CacheDataSource.Factory` 上游链——即**真实播放链路**；
- `:123` 位于 `prefetchRange()` 的**可取消**预取链路；
- 另有 `app/src/test/java/com/android/purebilibili/feature/plugin/CdnPlaybackDataSourceTest.kt`（@31、@80、@108、@159）。

→ 若据此断言把该文件归为"零命中、可不移植"，会导致**丢弃正在使用的播放缓存组件**。

### D3 — 弹幕引擎依赖（⚠️ 部分属实）

**结论正确**：包命名空间确为 ByteDance 分支，全仓无 `master.flame.danmaku`。

```kotlin
// danmaku-engine/build.gradle.kts
  7:     namespace = "com.bytedance.danmaku.render.engine"
```

```kotlin
// app/build.gradle.kts
369:     implementation(project(":danmaku-engine"))
...
462:     // --- 5. Danmaku (弹幕引擎) ---
463:     // 内部维护的 ByteDance DanmakuRenderEngine 分支；上游已归档。
464:     // 对 app 只暴露项目自有的引擎中立接口，便于后续替换渲染后端。
```

本地模块 `danmaku-engine` 内含 `src/main/java/com/bytedance/danmaku/render/engine/**` 的完整实现（`DanmakuView.kt`、`control/`、`render/`、`data/`、`touch/`、`utils/`），以及项目自有适配层 `com/android/purebilibili/danmaku/engine/ByteDanceDanmakuEngine.kt`（import `com.bytedance.danmaku.render.engine.DanmakuView` @4）。

**但"libs.versions.toml 行号与依赖坐标"不存在**：`gradle/libs.versions.toml` 全文 45 行，**0 处** `danmaku` 或 `flame`（`[versions]`/`[libraries]`/`[plugins]` 三段均无）。该引擎是**仓内 Gradle 模块**，不是版本目录里的外部依赖坐标。唯一残留的坐标文本出现在 `app/lint-baseline.xml:6939`（`implementation("com.github.bytedance:DanmakuRenderEngine:v0.1.0")`），这是**历史 lint 基线快照**，当前 `app/build.gradle.kts` 已无该行（该处现为 @462-464 的注释）。

### D4 — 用户协议（✅ 属实）

文件：`app/src/main/java/com/android/purebilibili/feature/agreement/UserAgreementText.kt`

```kotlin
18:                 "BiliPai 是一款基于 GNU GPL v3 协议开源的第三方哔哩哔哩客户端，源代码可在项目仓库" +
19:                     "（https://github.com/jay3-yy/BiliPai）公开获取。" +
...
21:                     "权利归其各自权利人所有。本应用按“现状”提供，适用 GPL v3 的全部条款：在遵守 GPL v3 的前提下，" +
22:                     "您可自由使用、修改和再分发本应用；除 GPL v3 明文规定外，本应用不提供任何明示或默示的担保。" +
23:                     "您继续使用即表示同时接受本协议与 GPL v3 开源协议。",
...
25:                     "任何个人或组织均有权依据 GPL v3 从项目仓库免费获取完整源代码；如您是通过付费途径获得本应用的，" +
38:                 "4. 本应用集成了 Google Firebase 服务用于质量维护：崩溃报告（Firebase Crashlytics，崩溃堆栈、" +
39:                     "设备型号与系统版本等）与匿名化的用量统计（Firebase Analytics，如每日活跃、页面浏览与基础功能" +
40:                     "使用事件、应用版本与语言区域），不含您的账号 ID、手机号等个人身份信息。上述数据会发送至 Google " +
41:                     "的 Firebase 服务器；您可以在应用设置的“崩溃跟踪”与“使用情况统计”开关中分别关闭对应收集。",
```

GPL v3 正文出现于 @18/21/22/23/25；Firebase（Crashlytics + Analytics + 可关闭开关）披露见 @38-41。断言属实。

### D5 — bbspace FilledTabRow（✅ 属实，需 2 项限定）

**注意这是下游 bbspace 的断言**，文件：`D:\111j\c\_ref\bbspace\core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\component\FilledTabRow.kt`（共 214 行；本次**只读**，未触碰 104 项未提交改动）。

Material 分支——`weight(1f)` 等分 ✅：

```kotlin
101:                 tabs.forEachIndexed { index, tab ->
102:                     val selected = index == selectedIndex
103:                     Card(
104:                         onClick = { onSelect(index) },
105:                         modifier = Modifier.weight(1f),
106:                         shape = RoundedCornerShape(18.dp),
```

外层 Row 在无 trailing 时 `fillMaxWidth()`、有 trailing 时 `weight(1f)`（@93-99）。

iOS 分支——`weight(1f)` **+** 手工等分：

```kotlin
153:     BoxWithConstraints(
...
159:         val segmentWidth = maxWidth / tabs.size
...
192:                 Box(
193:                     modifier = Modifier
194:                         .weight(1f)
195:                         .fillMaxHeight()
```

**"天然不溢出"的核实结果：横向不溢出成立，但机制不止 `weight(1f)`**：

- iOS 分支文本 **@207 `maxLines = 1`**（超长由裁切/省略处理，不撑破容器）；容器高度固定 `height(32.dp)` @155；滑块宽 `maxWidth / tabs.size`、偏移 `segmentWidth * selectedIndex`（@159、@165），最右段恰好贴边，不越界。
- Material 分支文本 **@115-128 无 `maxLines`**——超长文本会**换行**，Card 高度自适应增高。因此该分支是"横向不溢出、纵向可能变高"，而非"完全不溢出"。

→ "用 `weight(1f)` 等分"✅ 成立（Material @105；iOS @194 且辅以 @159 手工等分）；"天然不溢出"作为**横向**结论成立，但归因于 `weight(1f)` 单独一个机制并不完整（iOS 靠 `maxLines=1` 裁切，Material 靠换行）。

---

## ③ 错误清单（❌ 与 ⚠️ 汇总——本次核验的核心产出）

### ❌ 明确不属实（1 条）

| # | 断言 | 真相 | 影响 |
|---|---|---|---|
| E1 | **D2**：`CdnPlaybackDataSource` 约 371 行且**无消费者** | 371 行正确，但**有 3 处生产消费**：`PlaybackMediaCache.kt:80`（播放缓存上游工厂）、`:123`（可取消预取）、`:15` import，外加测试文件 | **最严重**。会导致把一个正在播放在链路上的组件误判为"零命中、不值得移植"而丢弃 |

### ⚠️ 部分属实 / 有偏差（6 条）

| # | 断言 | 错在哪 | 真相 | 影响 |
|---|---|---|---|---|
| E2 | **A1**："7 处重置" | 计数错误 | 实际 **8 处调用点**（885/1020/1339/1350/1820/2098/2196/2260）；任何口径都不产生 7 | 中。用于论证改动面时低估了 1 处 |
| E3 | **A3**：`:21` 是心搏间隔，故"后台不发心搏"为错 | 语义误判 + 推论方向相反 | `:21` 属于 **`resolveOnlineCountPollingDelayMs`（在线人数轮询）**，非心搏。心搏是 30s 固定循环（VM:8940）且**后台确实被策略拦掉不发** | **最严重**。若据此修改结论，会得出与上游事实相反的设计判断（误以为后台需按 90s 发心搏） |
| E4 | **A2**：`feature/video/playback/policy/` 下有 `PlaybackHeartbeatPolicy.kt` | 文件路径错 | 该目录**无此文件**；5 个函数都在 `PlaybackAncillaryTaskPolicy.kt`（24/36/48/59/78）。同名文件在 `core-data/.../data/repository/`，内容完全不同 | 高。按错路径去读会读到一个职责不符的文件，进而误判移植边界 |
| E5 | **D1**：`miuix-navigation` 38 文件 | 文件数错 | 实际 **34 文件**（30 个 `.kt`）/ **4483 行**；"约 4502 行"偏差 19 | 中。规模评估偏大 |
| E6 | **D3**：`libs.versions.toml` 里有该依赖坐标与行号 | 出处错 | `libs.versions.toml`（45 行）**0 处** danmaku/flame 条目；引擎是**仓内模块** `:danmaku-engine`（`app/build.gradle.kts:369`），namespace 见 `danmaku-engine/build.gradle.kts:7` | 中。结论对（非 flame），但"版本目录坐标"这一证据不存在，照抄会引不到东西 |
| E7 | **A1**"新增约 59 行" / **B1**"+179 行" / **B2**"9 行函数体" | 把 numstat 总变动/含注释范围当成新增行 | A1 实为 **+51/-8**（59=51+8）；B1 实为 **+178/-1**（179=178+1）；B2 函数体 6 行语句 / 8 行含括号 | 低。属统计口径问题，不影响结论 |

### 需重点提示的"高危半真"（形式上属实，误用即错，1 条）

| # | 断言 | 风险 |
|---|---|---|
| E8 | **C3**：`RecommendItem` 没有 `idx`、没有负反馈上下文 | 逐字**属实**（`RecommendItem` @312-333 确无）。**但同一文件 `MobileFeedItem` @626-636 有 `idx` 与 `three_point_v2`，并在 @674-700 构造完整 `RecommendationFeedbackMetadata`（FEEDBACK/DISLIKE）**，且是首页合并流的生产半边（`VideoRepository.kt:641`）。若以 C3 论证"上游不具备 idx/负反馈能力"，结论即错 |

### 最严重的 3 处

1. **E1（D2 "无消费者"）** — 事实性错误，且直接导致"可丢弃"的处置结论，会误删生产链路组件。
2. **E3（A3 语义误判）** — 把"在线人数轮询间隔"当成"心搏间隔"，并据此推翻一个**本身上游成立**的结论（后台不发心搏），会把设计判断引向反面。
3. **E4（A2 文件路径）** — 指定的文件在该路径不存在，按错路径读到的同名文件职责完全不同，会污染移植边界判断。

---

## ④ 为查找（可能不存在的）符号所做的检索记录

所有检索均在 `D:\111j\c\_ref\BiliPai-024` 内执行；"命中 0"即证明该符号不存在。

| # | 检索目标 | 检索方式 | 结果 |
|---|---|---|---|
| 1 | `PlaybackHeartbeatPolicy.kt` 是否在 `feature/video/playback/policy/` | glob `**/feature/video/playback/policy/*.kt` | **无此文件**；仅返回 6 个 policy 文件 + 5 个测试文件 |
| 2 | 全部 heartbeat 策略函数 | grep `PlaybackHeartbeatPolicy\|shouldSendInitialPlaybackHeartbeat\|shouldSendPlaybackHeartbeat\|shouldFlushPlaybackHeartbeatSnapshot\|resolvePlaybackHeartbeatSnapshot\|resolvePlaybackHeartbeatSessionStartTsSec` | 31 命中：定义全在 `PlaybackAncillaryTaskPolicy.kt`；`PlaybackHeartbeatPolicy` 字面仅出现在 `app/src/test/java/.../data/repository/PlaybackHeartbeatPolicyTest.kt:7`（测试类名） |
| 3 | 任何名字含 Heartbeat 的文件 | `Get-ChildItem -Recurse -Filter "*Heartbeat*"` | 3 个：`core-data/.../data/repository/PlaybackHeartbeatPolicy.kt`、`app/src/test/java/.../data/repository/PlaybackHeartbeatPolicyTest.kt`、`app/src/test/java/.../data/repository/LiveHeartbeatPolicyTest.kt` |
| 4 | `resolveOnlineCountPollingDelayMs` 是否有第二个（心搏）调用点 | grep `resolveOnlineCountPollingDelayMs` | 5 命中：定义 `PlaybackAncillaryTaskPolicy.kt:20`、测试 2 处、import `VideoPlaybackViewModel.kt:111`、**唯一生产调用 `:5646`（在线人数轮询）**。无任何心搏调用点 |
| 5 | 心搏循环的 delay 实际值 | grep `delay\(.*\)\|HEARTBEAT`（VM） | 14 命中；心跳循环 @**8940 `delay(30_000)`** |
| 6 | `PortraitStaffSheet` | grep `PortraitStaffSheet` | 命中：定义 `PortraitFullscreenOverlay.kt:815`、调用 `:799`（**存在**） |
| 7 | `findHostActivity` | grep `findHostActivity` | 命中 10 处：`SystemBarCompat.kt:94`（被 Theme.kt / FullscreenPlayerOverlay.kt 复用）、另有**2 份重复私有实现** `core/util/WindowSizeUtils.kt:314`、`feature/video/screen/AudioModeScreen.kt:146` |
| 8 | `resolveVideoListColumns` / `resolveBottomInputOverlayMaxWidthDp` | grep 二者 | 均命中：`core/ui/components/VideoListLayoutControl.kt:119`、`feature/video/ui/components/InputOverlayLayoutPolicy.kt:12` |
| 9 | `CdnPlaybackDataSource` 是否有消费者 | grep `CdnPlaybackDataSource` | 9 命中：**3 处生产使用**（`PlaybackMediaCache.kt:15/80/123`）+ 5 处测试 → "无消费者"被否证 |
| 10 | 弹幕引擎依赖是否在版本目录 | grep `danmaku\|flame`，include `libs.versions.toml` | **No matches found（0 命中）** → `libs.versions.toml` 中确实没有该依赖条目 |
| 11 | 是否残留 flame danmaku | grep `danmaku\.render\|bytedance\|master\.flame\|flame\.danmaku` | 244 命中，全部为 `com.bytedance.danmaku.render.engine`（本地 `danmaku-engine` 模块内）；**`master.flame` / `flame.danmaku` 0 命中** |
| 12 | 弹幕依赖的 gradle 声明位置 | `Select-String` on `app/build.gradle.kts` + `danmaku-engine/build.gradle.kts` | `app/build.gradle.kts:369 implementation(project(":danmaku-engine"))`、`danmaku-engine/build.gradle.kts:7 namespace = "com.bytedance.danmaku.render.engine"` |
| 13 | 用户协议 GPL / Firebase | grep `GPL\|GNU General Public\|Firebase`，include `*.kt` | 50 命中；正文集中在 `UserAgreementText.kt:18-25, 38-41`（其余为 licenses 页、CrashReporter/AnalyticsHelper 代码） |
| 14 | `RecommendItem` 内是否有 idx/负反馈 | grep `class RecommendItem\|idx\|three_point\|dislike\|feedback`，include `ListModels.kt` | 7 命中：`idx` 仅 @627/656（**属 `MobileFeedItem`**）；`three_point_v2` 仅 @636（同上）；`dislike` @132 属 `VideoRelationData`、@680 属 `MobileFeedItem` 的反馈映射。→ `RecommendItem` 本体无这些字段（断言属实，但存在 E8 误用风险） |
| 15 | `act=5/6` 常量 | grep `BILIBILI_RELATION_ACT_BLOCK\|BILIBILI_RELATION_ACT_UNBLOCK` | 3 命中：`BlockedUpRepository.kt:17 =5`、`:18 =6`、`:461` 使用处 |
| 16 | miuix-navigation 规模 | `Get-ChildItem -Recurse -File` + 逐文件行数求和 + `git show --numstat` | 34 文件 / 4483 行（30 `.kt` / 4174 行）；提交 `483099f98` 新增 4425 行 |
| 17 | bbspace `FilledTabRow` | 定位 `core/designsystem/.../component/FilledTabRow.kt` 并通读 214 行 | 存在；`weight(1f)` @105/@194；`segmentWidth = maxWidth / tabs.size` @159；`maxLines = 1` @207 |

---

## 附：核验方法与边界

- **未修改任何文件**：全部操作为 `read`/`grep`/`glob`/`git show`/`git blame`/`Get-Content`。`bbspace`（含 104 项未提交改动）仅被读取，未触碰。
- **不靠文件名/提交标题判断**：A2、A3、D2、D3 四条均因此才发现断言的路径/语义/出处与事实不符。
- **行号均为当前 worktree（`6e6bdce36`）实读行号**，非历史提交中的行号；涉及提交内改动的，已同时给出 `git show --numstat` 依据。
- **提交统计口径提醒**：`git show --stat`/`--numstat` 里那一列是"新增+删除"的总变动行数，本次核验发现 A1、B1 两处断言把它误当成了"新增行数"。
