# C10：竖屏刷视频「数据与队列」实现对比（BiliPai → bbspace）

- 性质：**只读对比分析**，未修改任何源码；本文件是本次任务唯一被写入的文件。
- 对比基线：BiliPai 上游参考仓库、bbspace 分支 `build/agp9-migration`（2026-09-28 三轮修复后现状）。
- 路径写法：`BiliPai/...` 与 `bbspace/...` 均为**仓库根相对路径**，行号为当前工作区实测行号。

---

## 一、总览表

| # | 维度 | BiliPai 做法（file:line） | bbspace 现状（file:line） | 差距性质 | 体验影响 | 移植成本 | 建议 |
|---|------|--------------------------|---------------------------|---------|---------|---------|------|
| 1 | 刷视频入口（StoryScreen）起步接口 | `getHomeVideos(idx=0)`（首页推荐流）起步：BiliPai/app/.../feature/story/StoryViewModel.kt:42；补货接力仍走 `getHomeVideos(idx=page)` + `mergeStoryFeedItems`：StoryViewModel.kt:68-99 | story 流（`x/v2/feed/index/story`）一次拉取做种子：bbspace/feature/video/.../VideoViewModel.kt:461-488；bbspace/core/feed/.../StoryFeedRepository.kt:46-69 | **等价（数据源不同但同级）** | 低 | 小 | 不移植（bbspace 的 story 流是官方竖屏源，比首页推荐流更对口；BiliPai 的 story 流只用于详情 overlay 与导航种子） |
| 2 | 详情页竖屏 overlay 数据源 | 先用详情自带的 `related`，再用 `getStoryFeed(aid,bvid)` **覆盖**（注释：避免首屏全相似/同 UP）：BiliPai/app/.../feature/video/screen/VideoDetailPortraitOverlayAdapter.kt:141-159 | 只用详情 relates 接力，无 story 流覆盖：bbspace/feature/video/.../VideoViewModel.kt:413-438、192-210 | 弱化（详情会话首屏多样性依赖 relates 质量） | 中 | 小 | 改造（可选：详情会话首屏补拉一次 story 流做种子扩充，见 Top5-5） |
| 3 | 去重：bvid 硬去重 | `mergePortraitRecommendationAppendItems`：bvid 空丢弃、≠当前页、∉既有：PortraitPagerSwitchPolicy.kt:224-233 | `appendSwipeEntries`：key（bvid 优先 / aid 兜底）硬去重含锚点：PortraitSwipeQueue.kt:191-227、76-78 | **等价（bbspace 额外有 aid 兜底，更强）** | 低 | — | 不移植 |
| 4 | 去重：标题关键词相似度 | 关键词交集 ≥3；≥2 且时长差 ≤30s；同 UP 且 ≥1 且时长差 ≤45s：PortraitPagerSwitchPolicy.kt:426-441；停用词表：:21-24；中文 2-6 字 / 英文 ≥3 正则：:458-497 | 仅「规范化标题全等」+「封面 key 全等」两档：PortraitSwipeQueue.kt:170-181、208-215；KDoc 自认压缩了关键词分词：:17-19 | **弱化** | 中 | 小~中 | 移植（Top5-2） |
| 5 | 去重：UP 主近期窗口限额 | 最近 12 条同 UP（按 `owner.mid`）最多 2 条：PortraitPagerSwitchPolicy.kt:19-20、361-373 | 最近 12 条同作者（按**名字字符串**）最多 2 条：PortraitSwipeQueue.kt:244-245、216-220 | 弱化（mid 更准；`StoryFeedItem.authorMid` 已解析却在建队时被丢弃：PortraitSwipeQueue.kt:111-118） | 低 | 小 | 改造（SwipeEntry 增加 ownerMid，relate/feed 源缺 mid 时回落名字） |
| 6 | 确定性洗牌（seed） | 首次 `resolvePortraitRecommendationShuffleSeed(初始 bvid+aid)`，补货 `resolvePortraitRecommendationAppendSeed(base, 当前bvid)`：PortraitPagerSwitchPolicy.kt:263-278；用于首次建队与每批追加：PortraitVideoPager.kt:465-470、515-524、535-544 | 无洗牌，「只追加、不重排」：PortraitSwipeQueue.kt:183-184 | **缺失** | 中 | 小 | 移植（Top5-3，只洗新批次不动已有页） |
| 7 | 相邻不同 UP 排布 | `shufflePortraitRecommendations` 洗牌后按「不与上一条相似 + 不与前一 UP 相同」逐条挑选：PortraitPagerSwitchPolicy.kt:311-329 | 无（纯追加，同 UP 推荐会连续出现） | **缺失** | 中 | 小 | 移植（Top5-3 同一函数内） |
| 8 | 竖屏过滤 | `filterPortraitOnlyVerticalRecommendations`：tri-state `isVertical`（true 直接过 / false 丢 / null 查详情），chunk 并发 8：PortraitPagerSwitchPolicy.kt:332-359；`VideoRepository.isVerticalVideo` 走 `getVideoInfo` 的 `dimension.isVertical` + ConcurrentHashMap 缓存：VideoRepository.kt:1166-1185、163；设置项 `getPortraitOnlyVerticalRecommendations` **默认 false**：SettingsManager.kt:5721-5725 | 完全没有竖屏过滤；`SwipeEntry`/`StoryFeedItem`/`VideoRelate`/`FeedItem` 均无尺寸字段（bbspace/core/model/.../feed/StoryFeedItem.kt:14-24、video/VideoDetail.kt:99-108）；仅有播放中尺寸推断（`currentStream.width<height`，仅当前页、起播后才有）：PortraitSwipePager.kt:194-199 | **缺失** | 中（横屏视频进竖屏流 → letterbox 黑边） | 中 | 改造（不逐条探测；用「story 流本身即竖屏源 + 播放后尺寸回填 + 可选封面比例粗判」，见 Top5-5 与第四节） |
| 9 | 补货触发阈值 | 竖屏模式 4 / 非竖屏 1：PortraitPagerSwitchPolicy.kt:15-16、186-192；判据 `shouldLoadMorePortraitRecommendations`：:174-184；另有 StoryScreen 自己的 `size-3`：StoryViewModel.kt:105 | 固定倒数第 2 页：`SWIPE_EXTEND_THRESHOLD = 2` + `shouldExtendSwipeQueue`：PortraitSwipeQueue.kt:234-243；pager 触发：PortraitSwipePager.kt:119-123 | 弱化（bbspace 阈值更小=补货更早，但单次补货质量差，见 #10/#11） | 中 | 小 | 移植（参数化，配合 Top5-1） |
| 10 | 补货重试循环「凑满 ≥8 条已验证」 | `while (fetchAttempts < 3 && verified.size < 8)` 最多拉 3 页首页流：PortraitVideoPager.kt:1355-1376 | 一次 `fetchFeed` 一页，去重后多少算多少，无重试：VideoViewModel.kt:496-523 | **缺失** | **高**（去重后不足 → 队列很快见底） | 小 | 移植（Top5-1） |
| 11 | 补货失败/不足时 related 兜底 | `verifiedHome.size < 8 → VideoRepository.getRelatedVideos(bvid)`（x/web-interface/archive/related）：PortraitVideoPager.kt:1380-1384；VideoRepository.kt:2331-2333；ApiClient.kt:969-970 | 无兜底；首页流失败仅"允许下次触发重试"（游标不前进）：VideoViewModel.kt:494、501-521 | **缺失** | **高** | 小 | 移植（Top5-1；bbspace 可用「下一视频详情 relates」或新增 related 接口两种实现） |
| 12 | 补货防抖 / 触发去重 | `isLoadingMoreRecommendations` + `appendedRecommendationSeeds`（每个 bvid 只触发一次补货）：PortraitVideoPager.kt:1337、1342-1344 | `loadingMoreSwipe` 防抖，失败不进阶可重试（合理）：VideoViewModel.kt:497-498、521 | 等价（bbspace 缺 per-bvid 去重，但 pager 阈值触发频率低，影响小） | 低 | 小 | 可选移植 |
| 13 | 稍后再看并入队列 | 登录态拉 `getWatchLaterList` → 映射 → bvid 去重 → 竖屏过滤 → 洗牌追加：PortraitVideoPager.kt:547-593；映射 `toRelatedVideoFromWatchLater`：PortraitDetailVideoListPolicy.kt:33-58 | 无（watch later 是独立页面：bbspace/feature/history/.../watchlater/WatchLaterScreen.kt） | **缺失** | 中 | 中 | 移植（Top5-4，需先确认 watch later 接口在 bbspace 网络栈可用） |
| 14 | UGC 合集 / 多 P follow-up 注入 | 多 P 后续分P 插到当前页之后（season 全集**故意不**注入，避免同 UP 聚集）：PortraitCollectionPolicy.kt:7-47、84-106；注入点：PortraitVideoPager.kt:1147-1174；仅竖屏模式且当前视频确为竖屏时注入：:1147-1151 | 无（多 P 视频播放完直接走推荐接力） | **缺失** | 中 | 中 | 移植（Top5-5 或单独立项；只移植多 P 部分） |
| 15 | 已看/历史过滤 | 无（pager 内不做 history 过滤；`VideoItem.progress` 未被 portrait 映射消费） | 无 | **等价（两边都不做）** | 低 | — | 不移植 |
| 16 | 补货接口的个性化程度 | 三种推荐流通道可按设置切换：WEB（WBI 签名，`fresh_idx`/`y_num`/`feed_version`）、MOBILE（TV appkey + access_key）、MERGED（`fresh_type=4`/`brush=idx` 双半边合并）：VideoRepository.kt:607-676、679-715、718-766、771-804 | 单一 App 签名通道 `/x/v2/feed/index`：`disable_rcmd`（个性化开关）、`interest_id=0`、`ps=20`、无 fresh_idx/y_num：bbspace/core/feed/.../FeedRepository.kt:61-70、110-173 | 弱化（少 WBI/MERGED 通道） | 低 | 大 | 不移植（网络栈架构差异，见第四节） |
| 17 | 负反馈 / 兴趣处理 | pager 内无负反馈（只有 watch later 与竖屏过滤）：PortraitVideoPager.kt:547-593 | 已解析 `three_point_v2` / `dislikeContext` 但队列不用：FeedRepository.kt:343-369、390-416 | **等价（队列层面两边都没有）** | 低 | — | 不移植 |

---

## 二、逐维度详解（含原文代码）

### 维度 1：队列来源与优先级

**BiliPai — StoryScreen（首页刷视频入口）起步用 `getHomeVideos(idx=0)`（首页推荐流），不是 story 流：**

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/story/StoryViewModel.kt:22-24
/**
 * 竖屏视频流：数据源与首页推荐一致（[VideoRepository.getHomeVideos]），内容更元化。
 */
// StoryViewModel.kt:37-50
private fun loadInitialStories() {
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        nextPageIndex = 0
        val result = VideoRepository.getHomeVideos(idx = 0)
        result.onSuccess { videos ->
            val items = videoItemsToStoryItems(videos)
            ...
            nextPageIndex = 1
```

接力仍是首页推荐流分页 + 三键去重（aid/bvid/id）：

```kotlin
// StoryViewModel.kt:68-99
fun loadMoreStories() {
    if (_uiState.value.isLoading) return
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val page = nextPageIndex
        val result = VideoRepository.getHomeVideos(idx = page)
        result.onSuccess { videos ->
            val newItems = videoItemsToStoryItems(videos)
            val mergedItems = mergeStoryFeedItems(existingItems = currentItems, newItems = newItems)
            ...
            if (newItems.isNotEmpty()) { nextPageIndex = page + 1 }
```

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/story/StoryFeedPolicy.kt:52-78
internal fun mergeStoryFeedItems(existingItems: List<StoryItem>, newItems: List<StoryItem>): List<StoryItem> {
    val seenAids = existingItems.mapNotNull { it.playerArgs?.aid?.takeIf { aid -> aid > 0L } }.toMutableSet()
    val seenBvids = existingItems.mapNotNull { it.playerArgs?.bvid?.trim()?.takeIf { bvid -> bvid.isNotEmpty() } }.toMutableSet()
    val seenIds = existingItems.mapNotNull { it.id.takeIf { id -> id > 0L } }.toMutableSet()
    ...
    val alreadySeen = (aid > 0L && aid in seenAids) || (bvid.isNotEmpty() && bvid in seenBvids) || (id > 0L && id in seenIds)
```

StoryScreen 把 items 变成 pager 的 `initialInfo + recommendations`（种子 bvid 从推荐里剔除）：

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/story/StoryScreen.kt:87-89
val portraitFeed = remember(uiState.items, seed) { buildStoryPortraitFeed(uiState.items, seed = seed) }
// StoryFeedPolicy.kt:80-99
internal fun buildStoryPortraitFeed(items: List<StoryItem>, seed: StoryFeedSeed? = null): StoryPortraitFeed? {
    val playableItems = items.mapNotNull(::toPlayableStoryItem)
    if (seed != null && seed.bvid.isNotBlank()) {
        val recommendations = playableItems.filter { item -> item.playbackId != seed.bvid }.map { item -> item.toRelatedVideo() }
        return StoryPortraitFeed(initialInfo = seed.toViewInfo(), recommendations = recommendations)
    }
    ...
```

**BiliPai — 详情页竖屏 overlay：先详情自带 related，再 getStoryFeed 覆盖（story 流优先）：**

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPortraitOverlayAdapter.kt:141-159
// 竖屏流推荐:优先用 Story 短视频流(按当前视频请求,内容多样化),
// 避免此前直接使用同主题相关推荐导致首屏全是相似视频/同一 UP 主;
// Story 流不可用时才回退相关推荐。
var portraitRecommendations by remember(info.bvid) { mutableStateOf(playableState.related) }
LaunchedEffect(showPortraitFullscreen, info.bvid) {
    if (!showPortraitFullscreen) return@LaunchedEffect
    val storyItems = StoryRepository.getStoryFeed(aid = info.aid, bvid = info.bvid).getOrNull().orEmpty()
    val storyRecommendations = storyItems.mapNotNull(::storyItemToRelatedVideo)
        .filter { it.bvid.isNotBlank() && it.bvid != info.bvid }
    if (storyRecommendations.isNotEmpty()) { portraitRecommendations = storyRecommendations }
}
```

**三个接口的字段能力（决定去重/过滤能做什么）：**

| 接口 | 模型 | 携带字段 | 尺寸信息 |
|------|------|---------|---------|
| `getRelatedVideos(bvid)`（x/web-interface/archive/related） | `RelatedVideo` | aid/bvid/cid/title/pic/owner(mid)/stat/duration/pubdate，`isVertical: Boolean? = null`（RelatedResponse.kt:11-23） | tri-state null（需查详情） |
| `getHomeVideos(idx)` | `VideoItem` | bvid/aid/cid/title/pic/owner/stat/duration，`isVertical = dimension?.isVertical == true`（ListModels.kt:191-219、353） | 有（web 流带 dimension） |
| `getStoryFeed(aid,bvid)` | `StoryItem` | player_args(aid/cid/bvid)/title/cover/owner/stat/duration（StoryModels.kt:25-82） | 无 |

**bbspace — story 会话用 story 流一次拉取做种子（无第二页），详情会话只用 relates：**

```kotlin
// bbspace/feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoViewModel.kt:452-488
/**
 * 刷视频模式（story）会话的启动：以入口视频为页 0 锚点，竖屏故事流
 * （`x/v2/feed/index/story`，BiliPai 上游同款接口）作为后续队列。
 * ...
 * 流失败时锚点仍设为入口视频（降级为"单视频全屏"，返回可重进）。
 */
fun bootstrapStorySession(seed: VideoTarget) {
    if (storyBootstrapStarted) return
    storyBootstrapStarted = true
    ...
    val items = runCatchingCancellable {
        storyFeedRepository.fetchStoryFeed(aid = seedUgc.aid, bvid = seedBvid)
    }.getOrDefault(emptyList())
    ...
    _swipeQueue.value = appendSwipeEntries(
        existing = emptyList(),
        incoming = items.mapNotNull(::swipeEntryFromStory),
        anchorKey = seedUgc.bvid.orEmpty().ifEmpty { "aid:${seedUgc.aid}" }
    )
}
```

```kotlin
// bbspace/feature/video/.../VideoViewModel.kt:404-438（详情全屏会话）
fun beginSwipeSession() {
    val detail = videoState.value.detail ?: return
    ...
    _swipeQueue.value = appendSwipeEntries(
        existing = emptyList(),
        incoming = swipeEntriesFromRelates(detail.relates),
        anchorKey = anchorTarget.bvid.orEmpty().ifEmpty { "aid:${anchorTarget.aid}" }
    )
```

```kotlin
// bbspace/core/feed/src/main/java/com/naaammme/bbspace/core/feed/StoryFeedRepository.kt:22-24
/** 分页说明（BiliPai 同款取舍）：该接口**没有游标参数**（BiliPai 只拉一次作种子），
 * 无限加载由调用方接力 `FeedRepository.fetchFeed(idx)` 的首页推荐流完成。 */
```

**差距结论**：bbspace 的「story 流种子 + 首页推荐流接力 + relates 合并」三源结构与 BiliPai 的「首页推荐流种子 + pager 内多源混合」是不同但同级的架构；bbspace 在**入口数据源上更优**（story 流天然竖屏），但在**详情会话首屏**上没有 BiliPai 的 story 流覆盖（多样性依赖 relates）。三个数据源在 bbspace 都带 aid/cid/bvid（relate 的 bvid 靠本地 `avToBv` 补齐：VideoDetailRepository.kt:362-368），但**没有任何一个源带尺寸字段**——这是维度 3 的根因。

---

### 维度 2：去重与多样性

**BiliPai — 相似度判据（标题签名 / 封面 / 关键词交集 / 时长差 / 同 UP）：**

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitPagerSwitchPolicy.kt:15-24
private const val PORTRAIT_RECOMMENDATION_PREFETCH_THRESHOLD = 1
private const val PORTRAIT_VERTICAL_RECOMMENDATION_PREFETCH_THRESHOLD = 4
private const val PORTRAIT_VERTICAL_FILTER_CONCURRENCY = 8
private const val PORTRAIT_VERTICAL_RECOMMENDATION_FETCH_ATTEMPTS = 3
private const val PORTRAIT_RECENT_DIVERSITY_WINDOW_SIZE = 12
private const val PORTRAIT_MAX_RECENT_ITEMS_PER_OWNER = 2
private val PORTRAIT_RECOMMENDATION_STOP_WORDS = setOf(
    "视频", "合集", "最新", "一个", "我们", "你们", "今天", "真的", "这个",
    "竖屏", "横屏", "官方", "完整版", "全集", "合集版"
)
```

```kotlin
// PortraitPagerSwitchPolicy.kt:407-442
private fun arePortraitRecommendationSignaturesSimilar(
    firstSignature: PortraitRecommendationSignature,
    secondSignature: PortraitRecommendationSignature
): Boolean {
    if (firstSignature.normalizedTitle.isNotBlank() && firstSignature.normalizedTitle == secondSignature.normalizedTitle) return true
    if (firstSignature.coverKey.isNotBlank() && firstSignature.coverKey == secondSignature.coverKey) return true
    val keywordOverlap = firstSignature.titleKeywords.intersect(secondSignature.titleKeywords).size
    val durationDelta = abs(firstSignature.duration - secondSignature.duration)
    if (keywordOverlap >= 3) return true
    if (keywordOverlap >= 2 && durationDelta <= 30) return true
    if (firstSignature.ownerMid > 0L && firstSignature.ownerMid == secondSignature.ownerMid &&
        keywordOverlap >= 1 && durationDelta <= 45) return true
    return false
}
```

```kotlin
// PortraitPagerSwitchPolicy.kt:458-497（分词：中文 2-6 字连续段、英文 ≥3 字符，各取前 6/4 个，过滤停用词）
private val PORTRAIT_TITLE_ZH_TOKEN_PATTERN = Regex("[\\u4e00-\\u9fa5]{2,6}")
private val PORTRAIT_TITLE_EN_TOKEN_PATTERN = Regex("[a-z0-9]{3,}")
private fun extractPortraitRecommendationKeywords(title: String): Set<String> {
    val normalized = normalizePortraitRecommendationTitle(title)
    if (normalized.isBlank()) return emptySet()
    val zhTokens = PORTRAIT_TITLE_ZH_TOKEN_PATTERN.findAll(normalized).map { it.value }
        .filter { it !in PORTRAIT_RECOMMENDATION_STOP_WORDS }.take(6).toList()
    val enTokens = PORTRAIT_TITLE_EN_TOKEN_PATTERN.findAll(normalized).map { it.value }.take(4).toList()
    return (zhTokens + enTokens).toSet()
}
```

UP 多样性窗口（按 mid）：

```kotlin
// PortraitPagerSwitchPolicy.kt:361-373
private fun violatesPortraitRecentOwnerDiversity(
    acceptedRecommendations: List<RelatedVideo>,
    candidate: RelatedVideo,
    recentWindowSize: Int = PORTRAIT_RECENT_DIVERSITY_WINDOW_SIZE,
    maxRecentItemsPerOwner: Int = PORTRAIT_MAX_RECENT_ITEMS_PER_OWNER
): Boolean {
    val ownerMid = candidate.owner.mid
    if (ownerMid <= 0L) return false
    val recentOwnerCount = acceptedRecommendations.takeLast(recentWindowSize.coerceAtLeast(1)).count { it.owner.mid == ownerMid }
    return recentOwnerCount >= maxRecentItemsPerOwner.coerceAtLeast(1)
}
```

**bbspace — 只有「规范化标题全等 + 封面 key 全等」两档 + 按作者名字的窗口限额：**

```kotlin
// bbspace/feature/video/src/main/java/com/naaammme/bbspace/feature/video/swipe/PortraitSwipeQueue.kt:166-181
/** 标题规范化：截断括号段（【】[]() 等常带推荐后缀）、保留字母数字并小写化。 */
fun normalizeSwipeTitle(title: String): String {
    val open = title.indexOfAny(charArrayOf('【', '[', '（', '('))
    val head = if (open < 0) title else title.substring(0, open)
    return head.lowercase().filter { it.isLetterOrDigit() }
}
/** 封面 key：取路径末段去 query 与 @ 尺寸后缀 —— 同图不同 CDN 参数视为同图。 */
fun swipeCoverKey(pic: String): String =
    pic.substringBefore('?').substringBefore('@').substringAfterLast('/').lowercase()
```

```kotlin
// PortraitSwipeQueue.kt:208-225（appendSwipeEntries 主循环）
incoming.forEach { candidate ->
    val key = candidate.key
    val title = normalizeSwipeTitle(candidate.title)
    val cover = swipeCoverKey(candidate.cover)
    val owner = candidate.displayAuthor
    if (key in seenKeys) return@forEach
    if (title.isNotEmpty() && title in seenTitles) return@forEach
    if (cover.isNotEmpty() && cover in seenCovers) return@forEach
    if (owner != null) {
        val recentOwners = accepted.takeLast(recentWindow.coerceAtLeast(1)).mapNotNull { it.displayAuthor }
        if (recentOwners.count { it == owner } >= maxPerOwnerPerWindow) return@forEach
    }
    accepted += candidate
    ...
}
```

bbspace 自己记录了压缩理由（KDoc）：

```kotlin
// PortraitSwipeQueue.kt:17-20
// - BiliPai 的标题签名相似去重（关键词交集 / 时长差）压缩为「规范化标题 + 封面 key」两档——
//   bbspace 的接力流来自各视频自己的相关推荐（同 UP 互推重复率高），bvid + 标题 + 封面
//   三档硬去重已覆盖实测重复场景，关键词分词的收益/成本比不划算；
// - UP 主多样性（最近窗口内同 UP 限额）原样保留，但按作者名字符串判（VideoRelate 无 mid）。
```

**差距结论**：
- 「标题全等 + 封面全等」：**等价**（BiliPai 的 signature 前两条就是它）。
- 「关键词交集 + 时长差」：**缺失**。这是同 UP 互推的主力判据（换标题后缀/换封面的同一视频），bbspace 现两档判不出来——title 规范化只截断括号段，`【】` 之后的差异词不参与；且 bbspace 缺**时长数值**（`VideoRelate.durationText` 是字符串、`FeedItem` 无时长、`StoryFeedItem.durationSec` 有秒），要做时长差需先补数值字段。
- UP 多样性：阈值等价（12/2），判据**弱化**（名字 vs mid）。`StoryFeedItem.authorMid` 已从接口解析（StoryFeedItem.kt:21）却在 `swipeEntryFromStory` 建队时被丢弃（PortraitSwipeQueue.kt:111-118），补 mid 的成本接近零。

---

### 维度 3：竖屏过滤

**BiliPai — `filterPortraitOnlyVerticalRecommendations`（tri-state + 并发 8 + 详情查询 + 缓存 + 设置默认关）：**

```kotlin
// PortraitPagerSwitchPolicy.kt:332-359
/** Filters a portrait feed to videos whose actual media dimensions are portrait. */
internal suspend fun filterPortraitOnlyVerticalRecommendations(
    recommendations: List<RelatedVideo>,
    enabled: Boolean,
): List<RelatedVideo> {
    if (!enabled || recommendations.isEmpty()) return recommendations
    // Detail lookup is required because lightweight recommendation cards do not consistently
    // carry dimensions. Bound concurrency so filtering finishes before insertion without
    // turning a feed page into an unbounded request burst.
    return recommendations.chunked(PORTRAIT_VERTICAL_FILTER_CONCURRENCY).flatMap { batch ->
        coroutineScope {
            batch.map { candidate ->
                async {
                    when (candidate.isVertical) {
                        true -> candidate
                        false -> null
                        null -> candidate.takeIf {
                            VideoRepository.isVerticalVideo(bvid = candidate.bvid, aid = candidate.aid)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
```

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt:1166-1185
suspend fun isVerticalVideo(bvid: String, aid: Long = 0L): Boolean = withContext(Dispatchers.IO) {
    val normalizedBvid = bvid.trim()
    if (normalizedBvid.isEmpty() && aid <= 0L) return@withContext false
    val cacheKey = normalizedBvid.ifEmpty { "av$aid" }
    verticalVideoCache[cacheKey]?.let { return@withContext it }   // :163 ConcurrentHashMap
    ...
    val isVertical = viewResp.data?.dimension?.isVertical == true
    verticalVideoCache[cacheKey] = isVertical
    isVertical
}
```

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt:5721-5725
/** Whether the portrait pager should exclude landscape recommendations (default disabled). */
fun getPortraitOnlyVerticalRecommendations(context: Context): Flow<Boolean> =
    context.settingsDataStore.data.map { preferences ->
        preferences[KEY_PORTRAIT_ONLY_VERTICAL_RECOMMENDATIONS] ?: false
    }
```

调用点（三处：首次建队 / watch later 追加 / 补货循环；pager 参数默认 `true` 但设置流初值 `false`）：

```kotlin
// PortraitVideoPager.kt:271
onlyVerticalRecommendations: Boolean = true,
// PortraitVideoPager.kt:509-513（首次建队）
LaunchedEffect(initialInfo.bvid, recommendations, onlyVerticalRecommendations) {
    val filteredRecommendations = filterPortraitOnlyVerticalRecommendations(recommendations = recommendations, enabled = onlyVerticalRecommendations)
// StoryScreen.kt:56-58（设置流 initialValue = false）
val onlyVerticalRecommendations by ...SettingsManager.getPortraitOnlyVerticalRecommendations(context).collectAsStateWithLifecycle(initialValue = false)
```

**bbspace — 完全没有竖屏过滤**，且轻量卡全部无尺寸字段：

```kotlin
// bbspace/core/model/src/main/java/com/naaammme/bbspace/core/model/feed/StoryFeedItem.kt:14-24
data class StoryFeedItem(
    val aid: Long, val cid: Long, val bvid: String, val title: String, val cover: String,
    val author: String?, val authorMid: Long, val durationSec: Long
)   // 无 dimension/isVertical
```

唯一与"竖屏"相关的推断在播放页（只对当前播放视频、起播后有效，无法用于入队前过滤）：

```kotlin
// bbspace/feature/video/.../player/PortraitSwipePager.kt:194-199
val isPortraitVideo = remember(playbackState.currentStream) {
    val stream = playbackState.currentStream ?: return@remember true
    (stream.width ?: 0) in 1..<(stream.height ?: 0)
}
```

**差距结论与代价**：BiliPai 的逐条探测代价是**每条候选一次 `getVideoInfo` 详情请求**（所以默认关闭、只有用户开设置才付这个代价），且它的 home 流（web）自带 dimension 可免检。bbspace 的三源都不带尺寸，逐条探测意味着每条一次 gRPC 详情——比 BiliPai 更贵（bbspace 翻页本身就会拉详情，但探测发生在入队时、会放大请求）。**更轻的替代判据**（建议改造而非移植）：
1. story 流本身就是官方竖屏源（story 会话零成本）；
2. 播放后回填：`currentStream.width/height` 已知后给该条目标记 `isVertical`，仅供后续会话/统计，不回踢已入队条目；
3. 首页推荐流卡片若带 `cover_left_text`/卡片比例可做粗判（本次未验证字段，不作为依据）；
4. 真要移植 BiliPai 行为，只移植 tri-state 结构与并发上限，`isVerticalVideo` 用 bbspace 已有的详情 gRPC 结果查（切到该视频时详情自然会到），**不做入队时同步探测**。

---

### 维度 4：补货策略

**BiliPai — 触发阈值（竖屏 4 / 非竖屏 1）+ 重试循环凑 8 条 + related 兜底：**

```kotlin
// PortraitPagerSwitchPolicy.kt:174-200
internal fun shouldLoadMorePortraitRecommendations(
    committedPage: Int, totalItemsCount: Int, isLoadingMoreRecommendations: Boolean,
    prefetchThreshold: Int = PORTRAIT_RECOMMENDATION_PREFETCH_THRESHOLD
): Boolean {
    if (isLoadingMoreRecommendations) return false
    if (committedPage < 0 || totalItemsCount <= 0) return false
    val lastTriggerIndex = (totalItemsCount - 1 - prefetchThreshold).coerceAtLeast(0)
    return committedPage >= lastTriggerIndex
}
internal fun resolvePortraitRecommendationPrefetchThreshold(onlyVerticalRecommendations: Boolean): Int =
    if (onlyVerticalRecommendations) PORTRAIT_VERTICAL_RECOMMENDATION_PREFETCH_THRESHOLD else PORTRAIT_RECOMMENDATION_PREFETCH_THRESHOLD
internal fun resolvePortraitRecommendationFetchAttemptLimit(onlyVerticalRecommendations: Boolean): Int =
    if (onlyVerticalRecommendations) PORTRAIT_VERTICAL_RECOMMENDATION_FETCH_ATTEMPTS else 1
```

```kotlin
// PortraitVideoPager.kt:1333-1419（触发与补货主循环，节选）
if (shouldLoadMorePortraitRecommendations(
        committedPage = committedPage,
        totalItemsCount = pageItems.size,
        isLoadingMoreRecommendations = isLoadingMoreRecommendations,
        prefetchThreshold = resolvePortraitRecommendationPrefetchThreshold(onlyVerticalRecommendations = onlyVerticalRecommendations),
    ) && bvid !in appendedRecommendationSeeds
) {
    appendedRecommendationSeeds.add(bvid)
    isLoadingMoreRecommendations = true
    launch {
        try {
            val (existingBvids, existingRecommendations, startingFeedCursor) = ...
            val verifiedHomeRecommendations = mutableListOf<RelatedVideo>()
            var nextFeedCursor = startingFeedCursor
            var fetchAttempts = 0
            val fetchAttemptLimit = resolvePortraitRecommendationFetchAttemptLimit(onlyVerticalRecommendations = onlyVerticalRecommendations)
            while (fetchAttempts < fetchAttemptLimit && verifiedHomeRecommendations.size < 8) {
                val homeFeedPage = VideoRepository.getHomeVideos(idx = nextFeedCursor).getOrNull().orEmpty()
                    .mapNotNull(::toRelatedVideoForPortraitRecommendation)
                nextFeedCursor += 1
                fetchAttempts += 1
                verifiedHomeRecommendations += filterPortraitOnlyVerticalRecommendations(
                    recommendations = homeFeedPage, enabled = onlyVerticalRecommendations,
                )
            }
            ...
            val relatedFallbackRecommendations = if (verifiedHomeRecommendations.size < 8) {
                VideoRepository.getRelatedVideos(bvid)
            } else { emptyList() }
            val verticalFetchedRecommendations = filterPortraitOnlyVerticalRecommendations(
                recommendations = verifiedHomeRecommendations + relatedFallbackRecommendations,
                enabled = onlyVerticalRecommendations,
            )
            val shuffledFetchedRecommendations = shufflePortraitRecommendations(
                seed = resolvePortraitRecommendationAppendSeed(baseSeed = recommendationShuffleSeed, currentBvid = bvid),
                recommendations = verticalFetchedRecommendations,
                precedingOwnerMid = ...
            )
            val appendItems = mergePortraitRecommendationAppendItems(
                currentBvid = bvid, existingBvids = existingBvids,
                existingRecommendations = existingRecommendations, fetchedRecommendations = shuffledFetchedRecommendations
            )
            if (appendItems.isNotEmpty()) { recommendationItems.addAll(appendItems); pageItems.addAll(appendItems) }
        } finally { isLoadingMoreRecommendations = false }
    }
}
```

注意 merge 时的多样性判据也一起生效（相似 + UP 窗口）：

```kotlin
// PortraitPagerSwitchPolicy.kt:224-240
val canAppend = candidate.bvid.isNotBlank() &&
    candidate.bvid != currentBvid &&
    candidate.bvid !in existingBvids &&
    appended.none { it.bvid == candidate.bvid } &&
    accepted.none { existing -> isSimilar(existing, candidate) } &&
    !violatesPortraitRecentOwnerDiversity(acceptedRecommendations = accepted, candidate = candidate)
```

**bbspace — 只拉一页、无重试、无兜底、阈值固定 2：**

```kotlin
// bbspace/feature/video/.../VideoViewModel.kt:490-523
/**
 * 队列补货：翻到倒数第 [...SWIPE_EXTEND_THRESHOLD] 页时由 pager 触发，接力首页推荐流
 * （`fetchFeed(idx)` 分页，BiliPai StoryViewModel 的 loadMoreStories 同款数据源）。
 * 防抖：进行中忽略；失败允许下一次触发重试（游标不前进）。
 */
fun loadMoreSwipeQueue() {
    if (loadingMoreSwipe) return
    loadingMoreSwipe = true
    viewModelScope.launch {
        val page = storyFeedCursor
        runCatchingCancellable {
            feedRepository.fetchFeed(idx = page, pull = false, flush = 0)
        }.onSuccess { result ->
            if (result.items.isNotEmpty()) {
                storyFeedCursor = page + 1
                val anchorKey = _swipeAnchor.value?.key.orEmpty()
                val incoming = result.items.mapNotNull(::swipeEntryFromFeedItem)
                _swipeQueue.value = appendSwipeEntries(existing = _swipeQueue.value, incoming = incoming, anchorKey = anchorKey)
                ...
            }
        }
        loadingMoreSwipe = false
    }
}
```

```kotlin
// bbspace/feature/video/.../swipe/PortraitSwipeQueue.kt:229-245
/** 补流判据：停稳页进入「倒数第 [threshold] 页」区间即应补流。 */
fun shouldExtendSwipeQueue(committedPage: Int, totalPages: Int, threshold: Int = SWIPE_EXTEND_THRESHOLD): Boolean {
    if (totalPages <= 0) return false
    return committedPage >= (totalPages - threshold).coerceAtLeast(0)
}
internal const val SWIPE_EXTEND_THRESHOLD = 2
internal const val SWIPE_OWNER_DIVERSITY_WINDOW = 12
internal const val SWIPE_MAX_PER_OWNER = 2
```

pager 侧触发点（每次翻页到阈值都调用，防抖在 ViewModel）：

```kotlin
// bbspace/feature/video/.../player/PortraitSwipePager.kt:119-123
// 补货判据（BiliPai loadMoreStories 同款）：进入倒数第 2 页区间就拉
// 下一批推荐流追加进队列；防抖由调用方（ViewModel）负责。
if (shouldExtendSwipeQueue(committedPage = page, totalPages = pages.size)) { onNeedMore() }
```

**差距结论**：bbspace 的补货是「一页定生死」——`fetchFeed` 一页约 20 条卡片，经 `swipeEntryFromFeedItem`（丢弃非 UGC/直播卡）+ `appendSwipeEntries`（bvid/title/cover/UP 四重去重）后净增可能只有几条；没有 BiliPai 的「最多 3 页、凑满 8 条」循环，也没有 related 兜底。**队列见底（滑到最后一页没有更多）是当前最可能的高频体验断点**。阈值 2 vs 4 的差异反而是次要的（2 更早补货）。

---

### 维度 5：其它数据面

**(a) 稍后再看（watch later）并入队列 —— BiliPai 有，bbspace 无：**

```kotlin
// PortraitVideoPager.kt:547-565
LaunchedEffect(Unit) {
    if (TokenManager.sessDataCache.isNullOrEmpty()) { watchLaterVideos = emptyList(); return@LaunchedEffect }
    runCatching { NetworkModule.api.getWatchLaterList() }
        .onSuccess { response ->
            if (response.code == 0) {
                watchLaterVideos = response.data?.list.orEmpty()
                    .mapNotNull(::toRelatedVideoFromWatchLater).distinctBy { it.bvid }
            }
        }
        ...
}
// PortraitVideoPager.kt:567-593（追加：bvid 去重 → 竖屏过滤 → seed 洗牌 → addAll）
```

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitDetailVideoListPolicy.kt:33-58
internal fun toRelatedVideoFromWatchLater(item: WatchLaterItem): RelatedVideo? {
    val bvid = item.bvid?.trim().orEmpty()
    if (bvid.isEmpty()) return null
    return RelatedVideo(aid = item.aid, bvid = bvid, title = item.title.orEmpty(), pic = item.pic.orEmpty(),
        owner = Owner(mid = item.owner?.mid ?: 0L, ...), stat = Stat(...), duration = item.duration ?: 0, pubdate = item.pubdate ?: 0L)
}
```

bbspace 只有独立的稍后再看页面（feature/history/.../watchlater/WatchLaterScreen.kt），竖屏队列不消费。

**(b) UGC 合集 / 多 P follow-up 注入 —— BiliPai 有（仅多 P，season 故意不做），bbspace 无：**

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitCollectionPolicy.kt:7-21
/**
 * Follow-up segments for a single multi-P video inside portrait immersive.
 * UGC season episodes deliberately stay out of the recommendation queue: inserting the whole
 * season makes normal swiping cluster videos from the same uploader. Explicit season selection
 * remains available through the detail sheet.
 */
internal fun resolvePortraitCollectionFollowUps(info: ViewInfo, currentCid: Long = info.cid): List<RelatedVideo> =
    resolvePortraitMultiPageFollowUps(info = info, currentCid = currentCid)
```

```kotlin
// PortraitCollectionPolicy.kt:23-47
internal fun resolvePortraitMultiPageFollowUps(info: ViewInfo, currentCid: Long = info.cid): List<RelatedVideo> {
    val pages = info.pages.filter { it.cid > 0L }
    if (pages.size <= 1) return emptyList()
    val currentIndex = pages.indexOfFirst { it.cid == currentCid }.takeIf { it >= 0 }
        ?: pages.indexOfFirst { it.cid == info.cid }.coerceAtLeast(0)
    return pages.drop(currentIndex + 1).map { page ->
        RelatedVideo(aid = info.aid, bvid = info.bvid, cid = page.cid,
            title = page.part.ifBlank { "P${page.page.coerceAtLeast(1)}" }, pic = info.pic, ...)
    }
}
```

注入点（当前页之后、仅竖屏模式且当前视频确为竖屏时）：

```kotlin
// PortraitVideoPager.kt:1147-1174
val followUps = if (onlyVerticalRecommendations && info.dimension?.isVertical != true) { emptyList() }
                else { resolvePortraitCollectionFollowUps(info = info, currentCid = resolvedCid) }
val injectItems = resolvePortraitCollectionInjectionPlan(pageItems = pageItems.toList(), currentPage = currentPageIndex, followUps = followUps)
if (injectItems.isNotEmpty()) {
    val insertAt = (currentPageIndex + 1).coerceIn(0, pageItems.size)
    pageItems.addAll(insertAt, injectItems)
    ...
}
```

**(c) 观看历史/已看不看过滤 —— 两边都没有。** BiliPai 的 portrait 映射不消费 `VideoItem.progress`（ListModels.kt:191-219 有 progress 字段，但 `toRelatedVideoForPortraitRecommendation`（PortraitPagerSwitchPolicy.kt:375-392）不读它），pager 内也没有 history 过滤；bbspace 同样没有。**结论：不存在"抄漏"，属共同空白。**

**(d) feed 参数差异（fresh_idx / y_num 等）—— BiliPai 有三通道，bbspace 单通道：**

```kotlin
// BiliPai/app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt:627-668（按设置切换 WEB / MOBILE / MERGED）
when (feedApiType) {
    SettingsManager.FeedApiType.MOBILE -> { ... fetchMobileFeed(idx, refreshCount) ... }
    SettingsManager.FeedApiType.MERGED -> { ... fetchMergedWebFeed + fetchMergedMobileFeed 并行交错合并 ... }
    else -> return fetchWebFeed(idx = idx, refreshCount = refreshCount)
}
// VideoRepository.kt:689-692（WEB：WBI 签名 + fresh_idx/y_num/feed_version）
val params = mapOf("ps" to refreshCount.toString(), "fresh_type" to "3", "fresh_idx" to idx.toString(),
    "feed_version" to System.currentTimeMillis().toString(), "y_num" to idx.toString())
// VideoRepository.kt:781-789（MERGED WEB 半边）
val params = mapOf("version" to "1", "feed_version" to "V8", "homepage_ver" to "1", "ps" ...,
    "fresh_idx" to idx.toString(), "brush" to idx.toString(), "fresh_type" to "4")
```

```kotlin
// bbspace/core/feed/src/main/java/com/naaammme/bbspace/core/feed/FeedRepository.kt:110-173（单通道 App 签名）
val personalizedRcmd = appSettings.personalizedRcmd.first()
...
put("disable_rcmd", if (personalizedRcmd) "0" else "1")
put("idx", idx.toString())
put("ps", FEED_PAGE_SIZE.toString())     // = 20（:55）
put("interest_id", "0")
...
// 无 fresh_idx / y_num / brush / feed_version
```

---

### 维度 6：推荐流的「个性化」程度

- **补货接口**：BiliPai 补货 = `getHomeVideos`（WEB WBI 带 cookie / MOBILE TV appkey+access_key / MERGED 双通道，VideoRepository.kt:607-804）；bbspace 补货 = `FeedRepository.fetchFeed`（App 签名通道，`accessToken` 或 HD `access_key`，FeedRepository.kt:110-124）。**不是同一个接口家族**：BiliPai 的 web 半边走 WBI 签名的 `x/web-interface/index/top/feed/rcmd` 风格，bbspace 走 App 签名 `x/v2/feed/index`。登录态两边都带（cookie vs access_key）。
- **兴趣/负反馈**：BiliPai 的 portrait pager 内**没有**负反馈处理（pager 文件里只有 watch later 与 seek/速度反馈，见 PortraitVideoPager.kt:547-593 与 grep 结果）；bbspace 的 `FeedRepository` 已解析 `three_point_v2`（点踩理由/反馈）与 `dislikeContext`（FeedRepository.kt:343-369、390-416），但竖屏队列不消费。**两边在「队列个性化」上打平——都没有把负反馈接入竖屏流。**
- **个性化开关**：bbspace 有 `personalizedRcmd → disable_rcmd`（FeedRepository.kt:117、133）；BiliPai 靠登录态与通道选择间接体现。**这一项 bbspace 不亏。**

---

## 三、Top 5 建议移植项（按体验收益排序）

### Top 1｜补货重试循环：「凑满 8 条已验证」+ 最多 3 页 + related 兜底

- **改动点文件**：`bbspace/feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoViewModel.kt` 的 `loadMoreSwipeQueue()`（:496-523）。
- **需要新增的纯逻辑（可单测，放 PortraitSwipeQueue.kt 同包测试）**：
  - `resolveSwipeRefillPlan(committedPage, totalPages, onlyVertical /*bbspace 可恒 false*/)`: 阈值与尝试次数参数化（移植 `resolvePortraitRecommendationPrefetchThreshold` / `resolvePortraitRecommendationFetchAttemptLimit`，PortraitPagerSwitchPolicy.kt:186-200）。
  - `shouldContinueSwipeRefill(attempts, verifiedCount, attemptLimit)`: `attempts < limit && verifiedCount < 8` 的循环条件（PortraitVideoPager.kt:1361-1364 同款）。
  - 兜底选择器：`resolveSwipeRefillFallback(verifiedCount)`: `verifiedCount < 8 → 触发 related/relates 兜底`（PortraitVideoPager.kt:1380-1384 同款）。
- **兜底实现二选一**：① 新增 `x/web-interface/archive/related` 调用（BiliPai 同款，VideoRepository.kt:2331-2333）；或 ② 零新接口方案——把「当前队列尾部视频的详情 relates」作为兜底源（bbspace 切到每个视频本来就拉详情，relates 已在 `init` 监听里 merge，VideoViewModel.kt:192-210；缺的只是「主动预取下一批候选视频的 relates」）。建议先做 ②（零网络新面），不够再上 ①。
- **风险**：多页请求放大流量与风控概率（最多 3 页首页流，BiliPai 同款剂量，可控）；游标前进策略必须与现状一致（仅在有净增时 +1，VideoViewModel.kt:504-505 已如此）；兜底源若选 ② 要注意 relates 已在自动 merge，别双重计数（`appendSwipeEntries` 幂等，无害）。
- **验收判据**：单测覆盖循环条件与兜底选择；真机/集成：mock `fetchFeed` 第一页只产出 3 条新条目 → 自动拉第二页；三页后仍 <8 → 触发兜底源；日志 `more:` 行出现 `page=… items=… mapped=…` 且队列下限 ≥8。

### Top 2｜标题关键词相似去重（交集 ≥3 / ≥2+时长差 ≤30s / 同 UP+≥1+≤45s）+ 停用词

- **改动点文件**：`bbspace/feature/video/.../swipe/PortraitSwipeQueue.kt`（`appendSwipeEntries` :191-227、`normalizeSwipeTitle` :170-174）。
- **需要新增的纯逻辑（可单测）**：
  - `extractSwipeTitleKeywords(title)`: 移植 `extractPortraitRecommendationKeywords`（PortraitPagerSwitchPolicy.kt:479-497：中文 2-6 字连续段取前 6、英文 ≥3 取前 4、停用词过滤）。
  - `areSwipeTitlesSimilar(a, b, durationASec, durationBSec, sameOwner)`: 移植 `arePortraitRecommendationSignaturesSimilar` 的后四条判据（:426-441）。
  - 前置依赖：`SwipeEntry` 增加 `durationSec: Int?`（StoryFeedItem.durationSec 直接有；VideoRelate 需把 `durationText` 解析成秒——VideoDetailRepository.kt:388-390 已有 `formatDur` 反向需求，注意对称实现；FeedItem 无时长 → null，判据自动退化为不含时长的两支）。
- **风险**：误杀（不同视频关键词碰巧重叠 ≥3 且时长接近）——BiliPai 已用停用词 + 上限 take(6) 压制，移植时原样照搬；O(n²) 两两比较——BiliPai 用 signatureCache 缓存（PortraitPagerSwitchPolicy.kt:212-215、291-294），移植时必须带缓存，且只在**新批次内部 + 新批次 vs 已接受列表**比较，别对全队列每轮重算。
- **验收判据**：单测：同 UP 互推换后缀标题（"XX 教程【2026 新版】" vs "XX 教程")判相似；不同视频长标题关键词交集 ≥3 且时长差 ≤30s 判相似、>30s 不判；停用词不计入交集。真机：同 UP 连续推荐显著下降。

### Top 3｜确定性洗牌 seed + 相邻不同 UP 排布

- **改动点文件**：`bbspace/feature/video/.../swipe/PortraitSwipeQueue.kt`（新增 `shuffleSwipeEntries`）；调用点 `VideoViewModel.beginSwipeSession`（:428-432）、`bootstrapStorySession`（:482-486）、`loadMoreSwipeQueue`（:508-512）。
- **需要新增的纯逻辑（可单测）**：
  - `resolveSwipeShuffleSeed(anchorBvid, anchorAid)` / `resolveSwipeAppendSeed(baseSeed, currentBvid)`：移植 PortraitPagerSwitchPolicy.kt:263-278（`31*seed + hashCode`）。
  - `shuffleSwipeEntries(seed, entries, precedingAuthor)`: 移植 `shufflePortraitRecommendations`（:280-330：`shuffled(Random(seed))` → 相似去重 → 逐条挑选「不与上一条相似且不与前一 UP 相同」）。
- **关键约束（bbspace 特有，移植时不能丢）**：只洗**新批次**再追加，绝不对整个队列重排——bbspace 的 KDoc 明确「只追加、不重排（保护用户当前所在页）」（PortraitSwipeQueue.kt:183-184）。BiliPai 也正是这么做的（append 路径 `shufflePortraitRecommendations(appendItems)` 后 `addAll`，PortraitVideoPager.kt:535-544）。
- **风险**：洗牌让"下一批"顺序与接口返回顺序不同——若上游有任何依赖接口顺序的逻辑（无）会受影响；相邻不同 UP 的挑选在极端情况（整批同 UP）会退化为顺序排列，需保留 `?: 0` 兜底（:325）。
- **验收判据**：单测：同 seed 两次洗牌结果一致；seed 不同结果不同；构造「同 UP 连续 5 条」输入 → 输出中相邻两条 UP 不同（除退化场景）；不改变既有页顺序（append 语义）。

### Top 4｜稍后再看（watch later）并入队列

- **改动点文件**：`bbspace/feature/video/.../VideoViewModel.kt`（新增 watch later 拉取与追加，参照 `bootstrapStorySession` 的协程写法）；`PortraitSwipeQueue.kt`（复用 `appendSwipeEntries`，无需改）。
- **需要新增的纯逻辑（可单测）**：`swipeEntryFromWatchLater(item)`——移植 `toRelatedVideoFromWatchLater`（PortraitDetailVideoListPolicy.kt:33-58）的判据：bvid 空 → null；bvid ≠ 锚点；distinctBy bvid。
- **前置确认**：bbspace 网络栈是否已暴露 watch later 接口（`x/v2/watchlater` 之类）——feature/history 的 WatchLaterScreen 已有数据通道，复用它，不要新造网络层。
- **风险**：未登录要跳过（BiliPai 判 `sessDataCache` 空，PortraitVideoPager.kt:548）；watch later 可能包含已看过/已下架项——BiliPai 不过滤，移植时保持一致（避免范围蔓延）；量级大时会稀释推荐多样性，建议只在**队列为空或补货失败时**注入（比 BiliPai 的无条件追加更稳）。
- **验收判据**：单测映射判据；集成：未登录不请求、登录后 watch later 条目出现在队列且不与既有条目重复。

### Top 5｜多 P follow-up 注入（仅多 P，不做 UGC 全集）

- **改动点文件**：`bbspace/feature/video/.../swipe/PortraitSwipeQueue.kt`（新增 `resolveSwipeMultiPageFollowUps` / `resolveSwipeInjectionPlan`）；调用点 `VideoViewModel` 的 relates merge 监听（:192-210）或 `openSwipeTarget` 之后。
- **需要新增的纯逻辑（可单测）**：
  - `resolveSwipeMultiPageFollowUps(pages, currentCid)`: 移植 `resolvePortraitMultiPageFollowUps`（PortraitCollectionPolicy.kt:23-47）：`pages.size <= 1 → empty`；当前 cid 之后的分 P 逐条转 SwipeEntry（同 bvid 不同 cid）。
  - `resolveSwipeInjectionPlan(existingKeys, currentPage, followUps)`: 移植 `resolvePortraitCollectionInjectionPlan`（:84-106）：跳过已存在的 (bvid,cid) 组合。
- **注意 bbspace 的页身份机制**：SwipeEntry 的 `key` 是 bvid 优先（PortraitSwipeQueue.kt:76-78），多 P 条目同 bvid 不同 cid 会**互相去重**！必须先引入 `(bvid, cid)` 复合键（BiliPai 的 `portraitCollectionIdentityKey`，PortraitCollectionPolicy.kt:108-111），否则注入后只有第一条存活。这是本项最大的隐藏成本。
- **风险**：复合键改动会波及 `openSwipeTarget` 的同条目短路判据（VideoViewModel.kt:530-552，目前按 aid 比）与 `isCurrentVideo`（PortraitSwipeQueue.kt:68-73）——三者必须同批改、同批测；UGC season 全集**不要**注入（BiliPai 注释明确会造成同 UP 聚集，PortraitCollectionPolicy.kt:9-12）。
- **验收判据**：单测：多 P 视频（3 P）的第二条入队后，注入计划 = [P2, P3]；重复注入幂等；单 P 视频返回 empty。真机：多 P 视频播放完自动进入 P2 而非推荐。

---

## 四、明确不建议移植的项（避免下一个人重复评估）

1. **WEB/MOBILE/MERGED 三推荐流通道与 WBI 签名体系**（VideoRepository.kt:607-804：`fresh_idx`/`y_num`/`feed_version`/`brush`/`fresh_type`）。理由：bbspace 网络栈是 App 签名 + gRPC（BiliRestProfile），没有 WBI 密钥管理与 web cookie 通道；个人化已由 `personalizedRcmd → disable_rcmd` 覆盖（FeedRepository.kt:117、133）。迁移=新造一半网络层，收益仅是"多一种拉流姿势"，**成本大、收益低**。
2. **「入队时逐条 `getVideoInfo` 探测竖屏」的重实现**（PortraitPagerSwitchPolicy.kt:332-359 + VideoRepository.kt:1166-1185）。理由：BiliPai 自己默认关闭该设置（SettingsManager.kt:5721-5725），因为每条候选一次详情请求太贵；bbspace 轻量卡三源都无尺寸，探测比 BiliPai 更贵。bbspace 的更优解是维度 3 列的四条轻量替代（story 流即竖屏源 / 播放后回填 / 复合键 / 不探测）。**只移植 tri-state 数据结构（`isVertical: Boolean?`），不移植探测循环。**
3. **已看历史过滤**。理由：BiliPai 自己也没做（`VideoItem.progress` 不被 portrait 映射消费），不是"漏移"而是上游也没有的实现；引入需要历史接口 + 本地已看集合，收益不确定。
4. **详情 overlay 的「story 流覆盖 related」逻辑**（VideoDetailPortraitOverlayAdapter.kt:144-159）。理由：bbspace 已有两条等价路径覆盖该场景——story 模式入口直接以 story 流做队列（VideoViewModel.kt:461-488），详情模式靠 relates 接力 + Top1 的补货强化后多样性可接受。再叠一层 story 流覆盖会让"两种会话的数据链竞争"（bbspace 的 VideoLogicGuardTest.kt:444-448 明确要求两条链不竞争）。
5. **`mergeStoryFeedItems` 三键去重（aid/bvid/id）**（StoryFeedPolicy.kt:52-78）。理由：bbspace 的 `appendSwipeEntries` 已是 key(bvid/aid 兜底) + 标题 + 封面 + UP 四档，覆盖严格更强；id 去重对 bbspace 的模型无对应字段。
6. **UGC season 全集注入**。理由：BiliPai 明确拒绝（PortraitCollectionPolicy.kt:9-12 注释：整季插入会让同 UP 聚集）；只移植多 P 部分（见 Top5）。
7. **pager 播放层的预取/提前起播/首帧 veil 等播放策略**（PortraitVideoLoadPolicy.kt 的 preload、prefetch head；PortraitVideoPager.kt:1291-1304 的 early playback）。理由：属于「播放」维度而非「数据与队列」维度，且 bbspace 已有同款 early-swipe 起播（PortraitSwipePager.kt:62-65、106-125），不在本任务范围。

---

## 附：本分析引用的文件清单

**BiliPai（只读）**
- `app/src/main/java/com/android/purebilibili/feature/story/StoryViewModel.kt`
- `app/src/main/java/com/android/purebilibili/feature/story/StoryFeedPolicy.kt`
- `app/src/main/java/com/android/purebilibili/feature/story/StoryScreen.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPortraitOverlayAdapter.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitPagerSwitchPolicy.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitCollectionPolicy.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/ui/pager/PortraitDetailVideoListPolicy.kt`
- `app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt`
- `app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt`
- `app/src/main/java/com/android/purebilibili/data/repository/StoryRepository.kt`
- `app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt`
- `app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt`
- `app/src/main/java/com/android/purebilibili/data/model/response/{RelatedResponse,StoryModels,ListModels,VideoDetailResponse}.kt`

**bbspace（只读）**
- `feature/video/src/main/java/com/naaammme/bbspace/feature/video/swipe/PortraitSwipeQueue.kt`
- `feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoViewModel.kt`
- `feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoScreen.kt`
- `feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt`
- `core/feed/src/main/java/com/naaammme/bbspace/core/feed/{StoryFeedRepository,FeedRepository}.kt`
- `core/video/src/main/java/com/naaammme/bbspace/core/video/VideoDetailRepository.kt`
- `core/model/src/main/java/com/naaammme/bbspace/core/model/{feed/StoryFeedItem,video/VideoDetail}.kt`
- `app/src/main/java/com/naaammme/bbspace/video/VideoDetailActivity.kt`
- 测试：`feature/video/src/test/java/com/naaammme/bbspace/feature/video/swipe/PortraitSwipeQueueTest.kt`、`feature/video/src/test/java/com/naaammme/bbspace/feature/video/VideoLogicGuardTest.kt`
