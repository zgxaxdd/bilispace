# C10 · BiliPai vs bbspace「竖屏刷视频」播放与预取实现对比

- 性质：**只读对比**，不改任何源码。本文件是唯一产出。
- 仓库根：
  - BiliPai（参照实现）：`D:\111j\c\_ref\BiliPai`，下文路径相对该根，如 `app/src/main/java/com/android/purebilibili/...`，简写为 `feature/video/ui/pager/PortraitVideoPager.kt`。
  - bbspace（待优化）：`D:\111j\c\_ref\bbspace`，下文路径相对该根。
- 方法：两侧逐维度对齐 `路径:行号` + 代码片段；结论分「差距 / 起播体验影响 / 实现成本 / 建议」。
- 前置批次关系：C4（swipe 会话）、C7（aid 优先 / 起播阻塞）、C8（等首帧 + 粒子）、C9（等首帧遮罩）已落地；本 C10 聚焦**播放链路 + 预取 + 缓存**的残余差距，且明确推翻"bbspace 无任何字节预取"的粗描——bbspace 有一段**内存级、单文件、仅索引**的 91KB 预热（见 §2.2）。

---

## 一、总览表

| 维度 | BiliPai（file:line） | bbspace（file:line） | 差距 | 起播/体验影响 | 实现成本 | 建议 |
|---|---|---|---|---|---|---|
| 1 切页→起播链路 | 并行详情+playurl 单次 bootstrap `VideoRepository.kt:467-500`；generation 防串台 `PortraitVideoPager.kt:1022-1034`；迟到丢弃 `shouldApplyLoadResult` `PortraitPagerSwitchPolicy.kt:44-53` | 并行 prepare+fetch+detail+resume `StreamPlaybackSessionImpl.kt:384-405`；`openId` token+target 双防串台 `:350,:412-469` | **并行度持平**；防串台机制等价（generation vs token+target） | 起播主体往返两边都已并行，差距不在这 | 低 | 已达标，**不重复投入** |
| 1b 起播可省往返 | 下一支已预取：playurl 命中 `PlayUrlCache`(`PlayUrlCache.kt:66`)+head 命中 SimpleCache(`PlaybackMediaCache.kt:104`) | 无预取，每次 swipe 全量 gRPC+91KB 索引+首段网络 | **相邻页 0 预热** | 每次上滑多 1 个 gRPC 往返 + 首段下载，冷启首帧明显更慢 | 中 | **重点补**（见 Top1/Top2） |
| 2 预取三层 | 拖动 0.25 偏移 `PortraitVideoLoadPolicy.kt:331-352`；停稳 1~3 条 `:368-400`；head 1.5MB/256KB `:411-437` | 仅 `SingleFileDashMediaSourceFactory.kt:362` 91KB 索引内存预热（单文件、即用即弃） | **跨视频 playurl/字节预取完全缺失** | 预取命中时 BiliPai 近秒开；bbspace 每页重来 | 中~高 | **核心差距**（Top1/2/3） |
| 3 首帧/播放器状态 | generation 比对 `renderedFirstFrameGeneration==activeLoadGeneration` `PortraitVideoPager.kt:1670`；`setKeepContentOnPlayerReset`；runtime 宽高比重算 `:1887-1895`；letterbox 环境光 `:2301-2324` | 单调计数 `firstFrameSeq>0` `Media3PlayerEngine.kt:75,117` + UI 粘滞键兜竞态 `PortraitSwipePager.kt:168-193`；`setKeepContentOnPlayerReset(true)` `VideoPlayerPane.kt:474`；静态 resizeMode `:457-464` | 首帧竞态两边都处理（generation vs 粘滞键）；bbspace 无 runtime 宽高比重算/无 letterbox 环境光 | keepContent 一致；黑屏/暂停键闪烁已由 C8 粘滞层缓解 | 低 | 首帧已够用；resizeMode 自适应/letterbox 为增强项 |
| 4 播完行为 | STOP/顺序/单循/列表循/续播五态 `PlaybackCompletionPolicy.kt:8-47`；repeat 仅 REPEAT_ONE→ONE `:71-81`；autoAdvance 幂等键 `lastAutoAdvancedBvid` `PortraitVideoPager.kt:902-921` | STATE_ENDED→`animateScrollToPage(+1)` `PortraitSwipePager.kt:149-161`；repeat=One→`REPEAT_MODE_ONE`→STATE_ENDED 永不触发→自动翻页失效（设计互斥）`Media3PlayerEngine.kt:268` | bbspace 只有"自动翻下一页"，无 STOP/loop 列表；repeat=One 与自动翻页互斥 | 默认(Off)下自动翻页正常；单循用户翻页失效（可接受） | 低 | 只补幂等键；五态矩阵**不建议全移** |
| 5 多P/合集/同bvid异cid | mediaId=`bvid#cid` `PortraitPagerSwitchPolicy.kt:75-78`，切 P 判重载 `:55-73`；合集 follow-up `PortraitVideoPager.kt:879-885` | 无 mediaId（`Media3PlayerEngine.kt:521-526` 不设）；同 aid 复用详情 `StreamPlaybackSessionImpl.kt:343-348`；aid 优先去重 `VideoViewModel.kt:537-543` | bbspace 用 aid 级身份（较粗），mediaId 语义缺位 | 刷视频流里条目互异，影响低；详情页多P 切 cid 有风险 | 中 | 详情页多P 可考虑补 cid 语义；刷视频流不必 |
| 6 缓存与内存 | media `SimpleCache` 512MB LRU `PlaybackMediaCache.kt:29,173-193`；CDN 镜像同键 `:90-101`；Coil 磁盘+内存 `PureApplication.kt:102-135`+封面预热 | **无 media SimpleCache**（DataSource 仅 OkHttp+CdnFailover `Media3PlayerEngine.kt:498-519`）；Coil 磁盘+内存 `BiliApplication.kt:205`+封面预热 `HomeVideoPage.kt:148-239` | **无持久化媒体字节缓存**（封面缓存持平） | 重看/回刷重复下载；无 CDN 命中共享 | 中~高 | **核心差距**（Top3） |
| 7 异常与降级 | 无流→停 `PortraitVideoPager.kt:1052-1064`；CDN 备份url `PortraitVideoLoadPolicy.kt:300-329`；缓存初始化失败降级直连 `PlaybackMediaCache.kt:186` | 无流→`NoPlayableStreamException`+整态重置 `StreamPlaybackSessionImpl.kt:425,472-489`；系统错误重建+防抖 `Media3PlayerEngine.kt:97-113,363-399`；`DefaultLoadErrorHandlingPolicy(3)` `:518` | 都有 CDN 换线+重试；bbspace 系统错误重建更完整，BiliPai 有 stall→换线恢复 | 健壮性大体持平 | 低 | 已达标；仅补 stall→换线观测 |

---

## 二、逐维度详解

### 2.1 切页到起播的完整链路

**触发点**

- BiliPai：三条路径都会起播。
  1) 停稳提交页：`requestPortraitPlaybackForPage(committedPage)` `PortraitVideoPager.kt:1421`。
  2) **拖动跨 snap 中点即起播**（提前几百 ms）：`resolvePortraitEarlyPlaybackPage` `PortraitVideoPager.kt:1291`（策略 `PortraitVideoLoadPolicy.kt:354-366`，注释明确"Pager currentPage 在跨过 snap 中点即变"）。
  3) 拖动偏移预取：`resolvePortraitSwipePrefetchTargetPage` `:1241`。
- bbspace：`snapshotFlow { pagerState.currentPage }.distinctUntilChanged()` `PortraitSwipePager.kt:106-118` → `onSwipeTo` → `openSwipeTarget`（aid 优先去重）`VideoViewModel.kt:537-551` → `openVideo` `StreamPlaybackSessionImpl.kt:179-184`。`currentPage` 同样在中点变化，**起播时机与 BiliPai 的 early-playback 相当**——这一点常被误解为 bbspace 只在停稳后起播，实测代码并非如此。

**请求并行度**

- BiliPai：`getPortraitPlaybackDetails` `VideoRepository.kt:467` 在 `shouldUsePortraitParallelPlaybackBootstrap`（bvid 以 BV 开头且 cid>0，`PortraitVideoLoadPolicy.kt:140-143`）时并行 `infoDeferred`+`playUrlDeferred` `VideoRepository.kt:475-500`；否则回退串行 `getVideoDetails`。
- bbspace：`openVideoInternal` 一次并行 6 个 job `StreamPlaybackSessionImpl.kt:384-405`：

```kotlin
// StreamPlaybackSessionImpl.kt:384-405
val prepareJob = async { prepare() }
val sourceJob = async { videoRepository.fetchPlaybackSource(request) }
val detailJob  = if (reuseDetail == null) async(Dispatchers.IO) { ...fetchVideoDetail... } else null
val localResumeJob = async(Dispatchers.IO) { readLocalResumeIfResolvable(request) }
val qualityJob = async { request.preferredQuality ?: appSettings.defaultVideoQuality.first() }
val audioJob   = async { appSettings.defaultAudioQuality.first() }
```

两边都把"详情/取流/设置"并行化了，**并行度打平**。

**token/generation 防串台 + 迟到丢弃**

- BiliPai：`activeLoadGeneration += 1; val requestGeneration` `PortraitVideoPager.kt:1022-1023`；迟到结果用 `shouldApplyLoadResult(requestGeneration, activeLoadGeneration, expectedBvid, currentPlayingBvid)` 判据（generation 不匹配 **或** bvid 不匹配即丢）`PortraitPagerSwitchPolicy.kt:44-53`，用于无流分支 `:1054` 与应用前 `:1105`（丢弃时打日志 `:1112-1114`）。
- bbspace：`val token = openId.incrementAndGet()` `StreamPlaybackSessionImpl.kt:350`；`openId.get() != token` 守卫 `:412,:427,:442,:467,:484`，再叠加 `currentTarget != target` 目标级双检 `:458,:469`。语义与 BiliPai 等价（代数/令牌 + 身份）。

**起播耗时构成 & 可省往返**

| 阶段 | BiliPai | bbspace | 可省？ |
|---|---|---|---|
| 详情 | 与 playurl 并行（cached 先上屏）`VideoRepository.kt:475` | 与取流并行，`peekCached` 乐观上屏 `StreamPlaybackSessionImpl.kt:355-359` | 都已优化 |
| 取流(Grpc) | 与详情并行；**下一支命中 PlayUrlCache 则 0 往返** `PlayUrlCache.kt:66` | 与详情并行；**无缓存，每次全量** `VideoPlayerRepositoryImpl.kt:42-55` | bbspace 可省 1 往返（预取） |
| 解码前 | DASH manifest 直接给 | **单文件 DASH 读 91KB 索引** `SingleFileDashMediaSourceFactory.kt:193-217` | 该 91KB 无法省（解析必需），但可并行/预热 |
| 首段下载 | head 命中 SimpleCache 则近 0 `PlaybackMediaCache.kt:104` | 每次现下载 | bbspace 可省首段（字节预热） |
| 首帧 | generation 兜底自动起播 `PortraitVideoPager.kt:770-777` | `playWhenReady=true` 直起 `Media3PlayerEngine.kt:227` | bbspace 无 STATE_READY 兜底（见 §2.3） |

结论：**主体往返两边都已并行；真正可省的是"相邻页预热"缺失**（下一支的 Grpc + 首段），这是 bbspace 起播更慢的主因，而非当前页的编排。

---

### 2.2 预取 / 预加载

**BiliPai 三层（全部有开关/去重）**

1) 拖动 0.25 偏移预取相邻页——阈值 `PORTRAIT_SWIPE_PREFETCH_OFFSET_THRESHOLD = 0.25f` `PortraitVideoLoadPolicy.kt:34`：

```kotlin
// PortraitVideoLoadPolicy.kt:331-352（纯函数，可单测）
internal fun resolvePortraitSwipePrefetchTargetPage(
    isScrollInProgress: Boolean, currentPage: Int,
    currentPageOffsetFraction: Float, lastPageIndex: Int,
    prefetchThreshold: Float = PORTRAIT_SWIPE_PREFETCH_OFFSET_THRESHOLD
): Int? {
    if (!isScrollInProgress) return null
    return when {
        currentPageOffsetFraction <= -prefetchThreshold -> (currentPage + 1).takeIf { it <= lastPageIndex } // 上滑→下一支
        currentPageOffsetFraction >=  prefetchThreshold -> (currentPage - 1).takeIf { it >= 0 }        // 下滑→上一支
        else -> null
    }
}
```
触发在 `PortraitVideoPager.kt:1228-1248`，去重 `lastSwipePrefetchPage` + `portraitPrefetchedPlayUrlBvids`（`ConcurrentHashMap.newKeySet`，`:464`），命中即 `preloadPortraitPlayUrl` + `prefetchPortraitPlaybackHead` `:1258-1268`。

2) 停稳后预取 1~3 条 playurl：

```kotlin
// PortraitVideoLoadPolicy.kt:368-400（纯函数，可单测）
internal fun resolvePortraitPlayUrlPreloadCount(prefetchVideoEnabled: Boolean, isWifi: Boolean, availableTargets: Int): Int {
    if (availableTargets <= 0) return 0
    val maxCount = when {                                   // WiFi+开关=3 / WiFi=2 / 仅开关=1 / 否则 0
        isWifi && prefetchVideoEnabled -> 3
        isWifi -> 2
        prefetchVideoEnabled -> 1
        else -> 0
    }
    return min(availableTargets, maxCount)
}
```
触发在 `PortraitVideoPager.kt:1426-1460`（committedPage 之后 N 页），开关 `prefetchVideoEnabled`（SettingsManager，`:412`）+ `NetworkUtils.isWifi`。

3) head 字节预热写 SimpleCache——视频头 `PORTRAIT_VIDEO_HEAD_PREFETCH_BYTES = 1536KB`、音频 `PORTRAIT_AUDIO_HEAD_PREFETCH_BYTES = 256KB` `PortraitVideoLoadPolicy.kt:35-36`：

```kotlin
// PortraitVideoLoadPolicy.kt:411-437：视频/音频两段 async 并行 CacheWriter 写盘
buildList {
    add(streamUrls.videoUrl to PORTRAIT_VIDEO_HEAD_PREFETCH_BYTES)
    streamUrls.audioUrl?.takeIf { it.isNotBlank() }?.let { add(it to PORTRAIT_AUDIO_HEAD_PREFETCH_BYTES) }
}.map { (url, length) -> async(Dispatchers.IO) { PlaybackMediaCache.prefetchRange(...) } }.awaitAll()
```

**bbspace 现状（纠正背景描叙）**

- 无跨视频 playurl/字节预取（`preloadPortraitPlayUrl|prefetch.*playurl` 命中 0，符合背景）。
- 但 `SingleFileDashMediaSourceFactory` 有一段**内存级、单文件、仅索引**预热：`readHttpIndex` 用 `Range: bytes=0-(91536-1)` 拉文件头 `SingleFileDashMediaSourceFactory.kt:197`，`DASH_PREFETCH_MAX_BYTES = 91_536` `:362`，解析出单文件 DASH 索引后把这段 `ByteArray` 交给 `PrefetchedDataSource` 复用 `:210-213,264-359`。
- **这段与 BiliPai 的 head 预热本质不同**：① 只为解析当前文件的索引，用完即随 `create` 丢弃，不跨视频、不落盘；② 91KB 远小于 BiliPai 的 1.5MB，仅供索引+极少量首帧数据；③ 它是**每次 open 的固定成本**（还额外增加 91KB 下载延迟），不是预热。

**bbspace 落地预取需动的文件（接口形态建议）**

- playurl 预取 + 进程内 LRU：
  - `core/playback/.../repository/VideoPlayerRepository`（接口）加 `suspend fun preloadPlayUrl(bvid: String, cid: Long, aid: Long, quality: Int): PlaybackSource?`；`VideoPlayerRepositoryImpl` 实现里先查进程内 LRU（仿 `PlayUrlCache`，key=`bvid:cid:q`，TTL 10min，容量 ~80）再打 Grpc。
  - `StreamPlaybackSession`（接口）+ `StreamPlaybackSessionImpl` 持有该 LRU，`openVideoInternal` 的 `fetchPlaybackSource` 前先查 LRU 命中即短路。
- 触发点：`PortraitSwipePager.kt` 的 `snapshotFlow` 增加"拖动偏移 0.25 → 预取相邻页"与"停稳 → 预取后 1~3 页"两个 `LaunchedEffect`，仿 BiliPai `PortraitVideoPager.kt:1228,1426`。
- 字节预热 + 磁盘缓存（播放内核级改造，独立立项）：
  - 新增 `infra/player/.../PlaybackMediaCache`（SimpleCache + `LeastRecentlyUsedCacheEvictor(512MB)`，仿 `PlaybackMediaCache.kt:173-193`）。
  - `Media3PlayerEngine.buildProgressiveMediaSourceFactory` `:498` 与 `SingleFileDashMediaSourceFactory.buildDataSourceFactory` `:150` 的 upstream 外包一层 `CacheDataSource.Factory`（`FLAG_IGNORE_CACHE_ON_ERROR`），并加 `prefetchRange`（`CacheWriter`）供 head 1.5MB/256KB 预热。

---

### 2.3 播放器状态与首帧

**首帧重置与竞态**

- BiliPai：`renderedFirstFrameGeneration` 每次起播置 `-1` `PortraitVideoPager.kt:1034`，`onRenderedFirstFrame` 置为 `activeLoadGeneration` `:786-789`，读取用 **generation 相等**判定 `hasRenderedFirstFrame = (renderedFirstFrameGeneration == activeLoadGeneration)` `:1670`。天然免疫上一支视频的迟到帧。
- bbspace：`firstFrameSeq` 单调计数，`onRenderedFirstFrame` 自增 `Media3PlayerEngine.kt:117`，`setSource` 归零 `:221`、`resetRuntimeState` 归零 `:588`；对外 `hasRenderedFirstFrame = state.firstFrameSeq > 0L` `StreamPlaybackSessionImpl.kt:852,872`。
  - **竞态**：`PortraitSwipePager.kt:173-192` 明确记录——`finishVideoPlayback` 先发布新 ids、后才 `stopForReuse()` 归零，中间几十毫秒 `firstFrameSeq>0` 仍是上一支的 `true`。bbspace 用**粘滞键** `waitingKey`（`currentKey` 变化即进入等待，"这一支出帧或出错"才退出）兜住 `:185-193`，C8/C9 已落地。
  - 对比：BiliPai 用 generation 从源头避免；bbspace 用 UI 粘滞层在末端缓解。**两者都正确**，bbspace 方案把复杂度放在 UI 层。

**setKeepContentOnPlayerReset / surface / resizeMode / letterbox**

- `setKeepContentOnPlayerReset(true)`：两边一致（BiliPai `VideoPlayerSection.kt:3684,3717`；bbspace `VideoPlayerPane.kt:474`）。这是"翻页瞬间画面残留上一支最后一帧"的根因，两边都用"等首帧遮罩"盖住它。
- surface 绑定/重绑：
  - BiliPai `shouldRebindSharedPlayerSurfaceOnAttach` + `rebindPlayerSurfaceIfNeeded` `PortraitVideoPager.kt:1929-1941`；前台恢复 `shouldRecoverPortraitPagerSurfaceOnResume` + 延迟重试 + `shouldKickPlaybackAfterSurfaceRecovery` `:1955-1983`（策略 `PortraitPagerSwitchPolicy.kt:511-517`）。
  - bbspace `PlayerViewTargetBinder.bind/unbind`，生命周期 ON_START bind / ON_STOP unbind `VideoPlayerPane.kt:494,498-506`，引擎重建时 `onPlayerReleased` 摘除 `Media3PlayerEngine.kt:193,325,387`。**缺 BiliPai 的"surface 恢复后补踢播放"重试**。
- resizeMode：BiliPai 用 `VideoAspectRatio`(FIT/ZOOM/FILL/STRETCH) → `playerResizeMode`（`VideoAspectRatio.kt:113-118`、pivot `:129`），并用 `onVideoSizeChanged`→`resolvePortraitRuntimeVideoAspectRatio` 按真实视频宽高比运行时重算 `PortraitVideoPager.kt:1887-1895`；bbspace 是静态用户设置 `PlayerVideoResizeMode`(Fit/Zoom/Fill)→RESIZE_MODE `VideoPlayerPane.kt:457-464`，默认 Fit，**无 runtime 宽高比重算**。
- letterbox 环境光采样：BiliPai 有 `shouldCapturePortraitLetterboxAmbientFrame` + `resolvePortraitLetterboxBarHeightPx` + `captureVideoAmbientFrame`(PixelCopy) `PortraitVideoPager.kt:2301-2324`（横屏视频在竖屏上下黑边做动态模糊取色）；bbspace **无此特性**（纯视觉，不影响起播）。

---

### 2.4 播完行为

**BiliPai（五态 + repeat 解耦）**

```kotlin
// PlaybackCompletionPolicy.kt:20-47
enum class PlaybackEndAction { STOP, PLAY_NEXT_IN_PLAYLIST, PLAY_NEXT_IN_PLAYLIST_LOOP, REPEAT_CURRENT, AUTO_CONTINUE }
// resolvePlaybackEndAction: STOP_AFTER_CURRENT→STOP / PLAY_IN_ORDER→NEXT / REPEAT_ONE→REPEAT_CURRENT / LOOP_PLAYLIST→LOOP / CONTINUE_CURRENT_LOGIC→(自动) AUTO_CONTINUE|STOP
```
- 关键设计：**repeat 与自动翻页解耦**——只有用户显式 REPEAT_ONE 才 `REPEAT_MODE_ONE`，其余一律 `REPEAT_MODE_OFF` `PlaybackCompletionPolicy.kt:71-81`，因此 STATE_ENDED 总能发出，autoAdvance 由 pager 监听驱动。
- autoAdvance 幂等键：`advanceKey = "$playingBvid#$currentPlayingCid"` + `lastAutoAdvancedBvid` 去重 `PortraitVideoPager.kt:902-921`；REPEAT_CURRENT 走 `seekTo(0)+play` `:895-898`；翻页 `resolveNextPortraitPageAfterPlaybackEnd` + `animateScrollToPage` `:907-915`。

**bbspace**

```kotlin
// PortraitSwipePager.kt:149-161
DisposableEffect(player, pages) {
    val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) {
            if (s != Player.STATE_ENDED) return
            val next = pagerState.currentPage + 1
            if (next > pages.lastIndex) return
            scope.launch { pagerState.animateScrollToPage(next) }
        }
    }
    p.addListener(listener); onDispose { p.removeListener(listener) }
}
```
- repeat 由设置驱动：`desiredRepeatOne`→`REPEAT_MODE_ONE` `Media3PlayerEngine.kt:255-273`，映射 `playback.repeatMode` `StreamPlaybackSessionImpl.kt:143`，默认 `PlayerRepeatMode.Off` `PlayerSettingsModels.kt:127`。
- **已知耦合**：用户设 repeat=One → `REPEAT_MODE_ONE` → **STATE_ENDED 永不触发 → 自动翻页失效**。注释 `PortraitSwipePager.kt:145-147` 与 report-C7:260 明确记录此为"设计互斥"。

**移植价值评估**

- bbspace 默认(Off)下自动翻页已可用（report-C7 真机 20:02:16/20:02:22 旁证）。
- "自带播完行为"完整矩阵（STOP/顺序/单循/列表循/续播）对 bbspace 刷视频流价值低——流里没有"播放列表"概念，只有"下一支"。真正值得移的是**幂等键**（防止一次 STATE_ENDED 触发多次翻页/网络抖动重入）。
- repeat=One 与自动翻页互斥：这是合理设计（单曲循环本就不该翻页），**不建议**为了统一而解耦。

---

### 2.5 多 P / 合集 / 同 bvid 不同 cid

- BiliPai：mediaId 编码 cid：`resolvePortraitMediaId(bvid, cid) = "$bvid#$cid"` `PortraitPagerSwitchPolicy.kt:75-78`，设在 MediaItem `PortraitVideoPager.kt:1090`（音频 `audio_$mediaId` `:1099`）；切 P 判重载 `shouldSkipPortraitReloadForCurrentMedia`（同 bvid 但 mediaId 含 cid 不同 → 重载）`:55-73`；合集 follow-up `shouldPortraitAutoContinueToNextItem` `:879-885` + `onRequestCollectionItem`→`jumpToPortraitPageForVideo` `:1701`。
- bbspace：**mediaId 缺位**（`mediaItem` 不 `setMediaId` `Media3PlayerEngine.kt:521-526`）；多 P 靠会话层同 aid 复用详情 `StreamPlaybackSessionImpl.kt:343-348`；身份判定用 `SwipeEntry.isCurrentVideo(bvid, aid)` `PortraitSwipePager.kt:138,165,202` 与 aid 优先去重 `VideoViewModel.kt:537-543`。
- 差距：bbspace 以 **aid 级**身份去重，同 aid 不同 cid 会被判为"同一目标"而跳过重开。刷视频流里条目互异（影响低）；但**详情页多 P 切 cid** 时，若沿用同一去重判据有"该重开却不重开"的风险。mediaId=`bvid#cid` 是更精确的最小语义，值得在详情页多 P 场景补。

---

### 2.6 缓存与内存

- BiliPai：media 层 `SimpleCache`，目录 `cacheDir/playback_media_cache` `PlaybackMediaCache.kt:191-193`，`LeastRecentlyUsedCacheEvictor(512MB)` `:29,181`；`buildCachedDataSourceFactory` 包 `CacheDataSource`+`FLAG_IGNORE_CACHE_ON_ERROR` `:68-84`；CDN 镜像同 cacheKey `buildCdnOptimizedDataSourceFactory`(ResolvingDataSource) `:90-101`；`prefetchRange`(CacheWriter) `:104-131`；初始化失败降级直连 `:186`；`estimateBytes/clear` 供设置页清理 `:133-148`。封面：Coil3 磁盘+内存 `PureApplication.kt:102-135` + 返回预热 `HomeCoverReturnPrefetch.kt`。
- bbspace：**无 media SimpleCache**，`buildProgressiveMediaSourceFactory` 仅 `OkHttpDataSource`+`CdnFailoverDataSourceFactory` `Media3PlayerEngine.kt:498-519`，`SingleFileDash` 仅内存 91KB `SingleFileDashMediaSourceFactory.kt:150-168`。封面：Coil3 `BiliApplication.kt:205` + 封面预热 `HomeVideoPage.kt:148-239`（同 URL + 解码尺寸≥布局尺寸，命中同一 Coil 缓存）+ 封面取色 `VideoCardCoverColorStore.kt`。
- 差距：**封面缓存两边持平；媒体字节缓存 bbspace 完全缺失**——重看/反复回刷同一支会重复下载全量，也无 CDN 命中共享与跨页 head 复用。

---

### 2.7 异常与降级

- BiliPai：无可用流 → `pendingAutoPlayGeneration=-1` + 停 `PortraitVideoPager.kt:1052-1064`；CDN 备份 URL 回退 `resolvePortraitPlaybackCdnUrls` `PortraitVideoLoadPolicy.kt:300-329` + cdnPlugin；卡顿→换线恢复策略 `PlaybackStallRecoveryPolicy`（有单测 `PlaybackStallRecoveryPolicyTest.kt`）；缓存初始化失败降级直连 `PlaybackMediaCache.kt:186`。
- bbspace：无可用流 → `throw NoPlayableStreamException` + 整态重置 `StreamPlaybackSessionImpl.kt:425,472-489`；播放器错误 `onPlayerError`：可恢复(解码/AudioTrack)→`recoverSession` 重建会话+`RebuildThrottle`(60s/3 次) `Media3PlayerEngine.kt:97-113,363-399`，不可恢复(IO/解析)→错误态 `:111`，`boundGen != rebuildGen` 丢弃旧实例回调 `:98`；加载重试 `DefaultLoadErrorHandlingPolicy(3)`+CDN 换线 `:512-518`；详情失败与取流失败分离 `StreamPlaybackSessionImpl.kt:454-465`。
- 差距：CDN 换线+重试两边都有；bbspace 的**系统错误会话重建更完整**（BiliPai 未见等价物）；BiliPai 多一个**卡顿→换线**主动恢复 + **缓存失败优雅降级**。整体健壮性持平，bbspace 可在观测上补"卡顿→换线"指标。

---

## 三、Top 5 建议项

### Top 1 · playurl 进程内 LRU + 三触发预取（性价比最高）

- ① 改动点：
  - `core/playback/.../repository/VideoPlayerRepositoryImpl.kt`：加进程内 LRU（key=`bvid:cid:q`，TTL 10min，容量 ~80，仿 BiliPai `PlayUrlCache.kt:16-55`），`fetchPlaybackSource` 命中即返回。
  - `VideoPlayerRepository`（接口）加 `preloadPlayUrl(bvid, cid, aid, quality)`；`StreamPlaybackSession`/`Impl` 转发。
  - `PortraitSwipePager.kt`：新增两个 `LaunchedEffect`——拖动偏移 0.25 预取相邻页、停稳预取后 1~3 页（开关：仅 WiFi 或用户开预取，仿 `resolvePortraitPlayUrlPreloadCount` `PortraitVideoLoadPolicy.kt:368`）。
- ② 可单测纯逻辑：预取目标选择（`resolveSwipePrefetchTargetPage` / `resolvePlayUrlPreloadTargets`）、预取计数（WiFi/开关矩阵）、LRU 的 get/put/TTL 过期/`invalidate`。均为纯函数，无 Android 依赖。
- ③ 风险与回滚：预取是"尽力而为"，失败只删 LRU 条目不影响主链路（仿 `:1456-1457` 失败回滚）；加总开关，异常时一键关。回滚=移除两个 LaunchedEffect + LRU 查询。
- ④ 真机验收：抓 `PortraitSwipe`/取流日志，上滑到**已预取**页时 Grpc `PlayViewUnite` 调用数为 0（命中 LRU）；指标"上滑到起播 Grpc 往返"从 1 降到 0（命中时）。观测点：LRU hit/miss 日志 + `firstFrameSeq` 递增的时间戳差。

### Top 2 · 切页"提前起播"对齐 + STATE_READY 自动起播兜底

- ① 改动点：
  - 确认 bbspace `snapshotFlow { pagerState.currentPage }`（`PortraitSwipePager.kt:107`）在 snap 中点即触发 `openVideo`——若当前版本在停稳后才触发，改为中点触发（对齐 BiliPai `resolvePortraitEarlyPlaybackPage` `PortraitVideoPager.kt:1291`）。
  - `Media3PlayerEngine` 加 STATE_READY 自动起播兜底：仿 BiliPai `PortraitVideoPager.kt:767-778`，`prepare()` 后若 `playWhenReady` 但因竞态未真正 play，STATE_READY 时补 `play()`。
- ② 可单测纯逻辑：`resolveEarlyPlaybackPage(isScrollInProgress, currentPage, lastCommittedPage, lastIndex)`；"是否需要补踢"判定（playWhenReady && !isPlaying && state==READY）。
- ③ 风险与回滚：提前起播会增加"划过去又划回"的无效取流，靠 `openId` token 丢弃迟到结果兜底（已有）；补踢播放要防"用户已手动暂停"被强行起播——加 playWhenReady 判据。回滚=回退到停稳触发。
- ④ 真机验收：从"手指触发滑动"到"首帧"的间隔（logcat 时间戳：滑动事件 → `firstFrameSeq` 递增）是否前移 ~200-300ms；补踢场景下无"黑屏+大暂停键"残留（C8 遮罩应已覆盖，此项兜底）。

### Top 3 · media3 DataSource 接 SimpleCache + head 1.5MB/256KB 预热（内核级，独立立项）

- ① 改动点：
  - 新增 `infra/player/.../PlaybackMediaCache`（SimpleCache + `LeastRecentlyUsedCacheEvictor(512MB)`，目录 `cacheDir/playback_media_cache`，仿 `PlaybackMediaCache.kt:173-193`）。
  - `Media3PlayerEngine.buildProgressiveMediaSourceFactory` `:498` 与 `SingleFileDashMediaSourceFactory.buildDataSourceFactory` `:150` 的 upstream 包一层 `CacheDataSource.Factory`（`FLAG_IGNORE_CACHE_ON_ERROR` + cacheKeyFactory）。
  - 加 `prefetchRange`（`CacheWriter`），在 Top1 预取命中 playurl 后，对 video 头 1.5MB / audio 头 256KB 写盘。
- ② 可单测纯逻辑：`resolveMediaCacheMaxBytes`、`shouldUseMediaCache(uri)`（仅 http/https，仿 `PlaybackMediaCache.kt:31-39`）、`buildCacheKey`、head 分段大小计算。SimpleCache 本体用 media3 instrumentation 或 mock DataSource 验证命中。
- ③ 风险与回滚：缓存初始化失败必须降级直连（仿 `:186`），绝不能让缓存故障阻断播放；缓存 key 必须与 CDN 换线后的 URL 解耦（否则换线=缓存失效，需 ResolvingDataSource 同键，仿 `:90-101`）。回滚=摘除 CacheDataSource 包装。磁盘占用需在设置页可见可清（`estimateBytes/clear`）。
- ④ 真机验收：重看同一支时 `onCachedBytesRead` 命中（上游下载字节≈0）；预取命中页首帧"首段下载"阶段耗时从"下载 N MB"降到"读盘"。观测：`PlaybackMediaCache` upstream/cached/ignored 三计数日志。

### Top 4 · 详情页多 P / 合集 cid 语义（mediaId = bvid#cid）

- ① 改动点：
  - `Media3PlayerEngine.mediaItem` `:521` 支持 `setMediaId(resolveMediaId(bvid, cid))`（`"$bvid#$cid"`，仿 `PortraitPagerSwitchPolicy.kt:75-78`）。
  - `StreamPlaybackSessionImpl`/`VideoViewModel` 的多 P 去重从"同 aid 即跳过"改为"同 aid **且** 同 cid 才跳过"（`openSwipeTarget` 的 `isCurrentVideo` 增 cid 维度）。
- ② 可单测纯逻辑：`resolveMediaId(bvid, cid)`、`shouldSkipReload(currentBvid, targetBvid, currentCid, targetCid, currentMediaId)`（仿 `shouldSkipPortraitReloadForCurrentMedia` `:55-73`）。
- ③ 风险与回滚：只收紧"该跳过才跳过"，不影响刷视频流（流里 cid 通常唯一）；回滚=恢复 aid 级判等。注意别把"合集自动续播"引入刷视频流（那是详情页语义）。
- ④ 真机验收：详情页多 P 手动切 cid 时确认重新取流/换源（日志一次 `PlayViewUnite` + 一次 `setSource`），不误短路；同 cid 重复进入不重开。

### Top 5 · surface 恢复后补踢播放 + 卡顿观测

- ① 改动点：
  - `VideoPlayerPane.kt` surface 重绑后（`PlayerViewTargetBinder.bind` / 前台 ON_RESUME）补一次"若 `playWhenReady && !isPlaying` 则 `play()`"（仿 BiliPai `shouldKickPlaybackAfterSurfaceRecovery` `PortraitVideoPager.kt:1982` + 延迟重试 `:1968`）。
  - 增加卡顿→换线观测日志（对齐 BiliPai `PlaybackStallRecoveryPolicy`），先只打点不自动切，评估收益。
- ② 可单测纯逻辑：`shouldKickPlaybackAfterSurfaceRecovery(playWhenReady, isPlaying, playbackState)`、`shouldRecoverSurfaceOnResume(isCurrentPage, isReady, hasView)`（仿 `PortraitPagerSwitchPolicy.kt:511-517`）。
- ③ 风险与回滚：补踢必须严格以 `playWhenReady` 为准，防把用户暂停态强行起播；观测日志零行为改动，可随时删。回滚=移除补踢调用。
- ④ 真机验收：息屏/切后台回恢复后播放态正确延续（不出现"回来了但暂停"）；卡顿事件有 `stall` 时间线日志供后续决策。

---

## 四、不建议移植的项 + 理由

1. **完整五态播完行为矩阵（STOP/顺序/单循/列表循/续播）**：bbspace 刷视频流只有"下一支"，无播放列表语义；`resolvePlaybackEndAction` 的多数分支无对应场景。只移幂等键（Top4 已含精神）即可。移植整棵策略树收益低、维护面大。
2. **repeat 与自动翻页解耦**：bbspace 当前 repeat=One 时 STATE_ENDED 不触发→不翻页，是**合理且自洽**的设计（单曲循环本就不该翻页）。强行解耦会改变用户可预期行为，得不偿失。
3. **letterbox 环境光采样（PixelCopy 动态取色模糊）**：纯视觉增强，与起播/预取无关，且增加每帧 PixelCopy 开销与耗电；bbspace 已有封面取色与渐变遮罩，够用。若要做得单独立视觉项，不塞进播放链路优化。
4. **封面预热体系**：bbspace `HomeVideoPage.kt:148-239` 的封面预热与 BiliPai 基本对齐（同 URL + 解码尺寸≥布局尺寸命中同一 Coil 缓存），**非差距，重复投入无收益**。
5. **CDN 镜像同 cacheKey（ResolvingDataSource）**：属 Top3 落地 SimpleCache 时的**配套细节**，不应单独提前做；没有 SimpleCache 时 ResolvingDataSource 无附着点。
6. **generation 首帧判定改造**：bbspace 的粘滞键 `waitingKey` 已由 C8/C9 落地并真机验证，虽与 BiliPai 的 generation 方案路线不同但结果等价；推翻重做为高成本低收益，不建议。

---

## 附：一句话结论

bbspace 的**当前页起播编排已与 BiliPai 打平**（并行取流 + token 防串台 + 等首帧遮罩）；核心差距只剩**"相邻页零预热"**——即 playurl 进程内 LRU（Top1）、提前起播/READY 兜底（Top2）、media SimpleCache + head 字节预热（Top3）三项。三者按性价比排序，Top1 即可显著缩短"上滑→起播"的可省 Grpc 往返，Top3 解决重复下载与首段下载，需作为播放内核级改造独立立项。
