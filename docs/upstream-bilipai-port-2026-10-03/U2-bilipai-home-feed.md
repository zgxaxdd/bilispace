# U2 · BiliPai v0.2.4「首页推荐」运行原理（供 bbspace 对照）

> 上游：`D:\111j\c\_ref\BiliPai-024`（worktree，`origin/main` = `6e6bdce36`，`git describe` = `v0.2.4-6-g6e6bdce36`，tag `v0.2.4`）。
> 基线旧版：同一 worktree 的 `34d189620`。下游对照：`D:\111j\c\_ref\bbspace`（HEAD `a5df3387`，**未触碰**）。
> **只读分析：未修改任何仓库文件**（唯一写入 = 本报告）。
> **无真机**（`adb devices` 未使用）→ 本报告**不含任何帧率/内存/耗时数字**；需要运行才能判定的写 `【待真机】`。
> 证据标记：`【事实】`= 已读源码并核对行号；`【推断】`= 机制成立但需运行验证；`【待真机】`= 必须设备实测。
> 行号口径：行号 = **物理行**（与 read 工具一致）。`HomeViewModel.kt` 共 **2120 物理行 / 2001 非空行**（任务书写"2001 行"= 非空行口径，两者一致，不矛盾）。
> 多模块仓库：`app/`（UI 与领域策略）、`core-data/`（网络 + 仓库 + 模型）、`network-core/`（纯策略函数）、`plugin-sdk/`（第三方插件 API）。

---

## ⓪ 结论速览（先看这 8 条）

1. **首页推荐流是一个"单接口 + 三种取流模式"的流**：`FeedApiType.WEB / MOBILE / MERGED`，由设置决定（`SettingsManager.kt:5675-5683`）。默认 `WEB`。
2. **`core/plugin/feed/` 整套不属于首页推荐流**，它是**首页「订阅」Tab（RSS/Atom/OPML）**的实现；首页推荐与它只在「都属于首页顶部 Pager 的一页」这一层相交（`HomeScreen.kt:2086-2087`）。详见 §A4。
3. 首页推荐**有**插件介入，但插件是**过滤器/后处理器**（`FeedPlugin` / `FeedTransformPlugin`），**不是数据源**（`core/plugin/FeedPlugin.kt:14-35`、`PluginManager.kt:221-252`）。唯一"生成内容"的插件是 `TodayWatchPlugin`，它产出的是**一张独立卡片**，不与视频卡混排成流（`HomeCategoryPage.kt:612-630`）。
4. **首页推荐有增长守卫，而且是两道**：（a）列表长度守卫 `resolveHomeRefreshKeptOldItemCount`（>200 条只留 50，`HomeRefreshUiPolicy.kt:92-96`）；（b）请求重入守卫（`HomeCategoryPage.kt:160-172` + `HomeViewModel.kt:1346` + `:1197`）。但没有 bbspace 那种"连续重复页计数"（`stagnantLoads`）——上游改用 **`hasMore=false`** 停流（`HomeViewModel.kt:1575-1588`）。
5. **游标 `idx/refreshIdx` 只在"本页确有有效内容"时前进**（`HomeViewModel.kt:141-150` + `:1497-1505`），且前进量恒为 +1（`maxOf` 幂等）。
6. **v0.2.4 相对 `34d189620` 在首页推荐的数据链路/参数/签名上零行为变化**——8 个提交对 `HomeViewModel.kt` 只有 3 行 nullability 改写、对 `VideoRepository.kt` 只有一次 `api.getRecommendParams` → `SharedContentRepository.recommendationResponse` 的搬家。**唯一实质变化是折叠屏铰链列数与 system bars**（§⑥）。
7. **不存在** `HomeRefreshPolicy`、`HomePreloadPolicy`、`ActionRepositoryRecommendationFeedback`、`core/plugin/feed/FeedPlugin.kt` 这四个任务书里点名的文件；`FeedPrependScrollPolicy` 在 `core/ui/components/` 而非 home 目录。命名对照见 §附录A。
8. `bilibili_ticket`（`bili_ticket`）在 v0.2.4 里**完全不存在**（全仓 0 命中）；登录态靠 `SESSDATA` / `bili_jct` / `DedeUserID` / `buvid3` Cookie。

---

## A. 数据来源（必答）

### A1. 首页推荐流到底打哪个接口

首页推荐（`HomeCategory.RECOMMEND`）**只走一个接口**，URL 取决于设置模式：

| # | 接口 | URL 常量定义处 | 调用处 |
|---|---|---|---|
| 1 | Web 推荐（WBI 签名） | `core-data/.../core/network/ApiClient.kt:521` `@GET("x/web-interface/wbi/index/top/feed/rcmd")` | `SharedContentRepository.kt:25-26` → `VideoRepository.kt:671`（WEB 模式）/ `:768`（MERGED 的 Web 半边） |
| 2 | App 推荐（TV appkey 签名） | `ApiClient.kt:525` `@GET("https://app.bilibili.com/x/v2/feed/index")` | `VideoRepository.kt:721` |
| 3 | App 推荐（encoded 原样发送，HD 半边） | `ApiClient.kt:530-533` `getMobileFeedEncoded` | `VideoRepository.kt:835` |
| 4 | 推荐流"不感兴趣"回写 | `ApiClient.kt:535` `@GET("https://app.bilibili.com/x/feed/dislike")` | `ActionRepository.kt:130` |

`FeedApiType` 决定走哪条（`VideoRepository.kt:604-646`）：

- `WEB`（默认）→ `fetchWebFeed`（`:656-692`）
- `MOBILE` → `fetchMobileFeed`（`:695-743`），失败/空则**回退 WEB**（`:608-614`）
- `MERGED` → `coroutineScope { async web + async mobile }` 并行，再交错合并（`:616-644`，合并函数 `HomeFeedMergePolicy.mergeFeeds` `HomeFeedMergePolicy.kt:23-42`）

**任务书点名的其它"可能进首页流"的接口，逐条裁定：**

| 接口 | 定义处 | 是否进首页推荐流 | 依据 |
|---|---|---|---|
| `x/v2/feed/index` | `ApiClient.kt:525,530` | ✅ 是（MOBILE / MERGED 模式） | `VideoRepository.kt:721,835` |
| `x/web-interface/wbi/index/top/feed/rcmd` | `ApiClient.kt:521` | ✅ 是（WEB / MERGED 模式） | `VideoRepository.kt:671,768` |
| `x/feed/dislike` | `ApiClient.kt:535` | ⚠️ 是"回写"不是取流 | `ActionRepository.kt:115-135` |
| `x/web-interface/popular` | `ApiClient.kt:540-544` | ❌ 只服务「热门」Tab | `HomeViewModel.kt:1456` |
| `x/web-interface/ranking/v2` | `ApiClient.kt:546-549` | ❌ 只服务「热门→排行榜」 | `HomeViewModel.kt:1457` |
| `x/v2/feed/index/story` | `ApiClient.kt:1653`（`StoryApi`） | ❌ **不进首页**；被竖屏沉浸流用 | `StoryRepository.kt:37` → 唯一调用者 `VideoDetailPortraitOverlayAdapter.kt:149` |
| `x/polymer/web-dynamic/v1/feed/all`(+`/update`) | `ApiClient.kt:1790,1810` | ❌ 只服务「关注」Tab | `HomeViewModel.kt:1723-1732` |
| `xlive/app-interface/v2/index/feed`、`room/v3/area/getRoomList` | `ApiClient.kt:621,582` | ❌ 只服务「直播」Tab | `HomeViewModel.kt:1919,1957` |
| 插件/RSS 订阅源（`FeedSourceCatalog`） | `core/plugin/feed/FeedSourceCatalog.kt:37-74` | ❌ **完全独立页面**，不混入推荐 | `HomeScreen.kt:2086-2087` |

**【事实】首页推荐视频流是"单一 API 来源"，没有任何 RSS / 订阅源 / 插件条目被合流进推荐列表。**
【推断】之所以视觉上"首页内容很杂"，是因为首页顶部 Pager 把 8 个分类 + 分区 + 订阅做成了**并列的页**，而不是把不同来源混进同一条流。

### A2. 请求参数逐项

#### (1) WEB 模式 — `fetchWebFeed`（`VideoRepository.kt:666-670`）

| 参数 | 值 / 来源 | 说明 | 行号 |
|---|---|---|---|
| `ps` | `refreshCount`（设置 `homeRefreshCount`，默认 20，夹在 10..30） | 每页条数 | `:667`；设置 `SettingsManager.kt:504-510,5717-5735` |
| `fresh_type` | 常量 `"3"` | Web 推荐流刷新类型 | `:667` |
| `fresh_idx` | `idx`（游标） | 第几"刷" | `:667` |
| `feed_version` | **`System.currentTimeMillis().toString()`** ⚠️ | 每次请求都变；注释里叫 "feed_version" 实为时间戳混淆参数 | `:668` |
| `y_num` | `idx` | 附带游标 | `:668` |
| `wts` | `WbiUtils.sign` 内注入 `now/1000` | **WBI 签名参数** | `WbiUtils.kt:68` |
| `w_rid` | `WbiUtils.sign` 内计算 | **WBI 签名结果** | `WbiUtils.kt:92-98` |

#### (2) MOBILE 模式 — `fetchMobileFeed`（`VideoRepository.kt:703-716`）

| 参数 | 值 / 来源 | 行号 |
|---|---|---|
| `idx` | 游标 | `:704` |
| `pull` | `"1"`(idx==0) / `"0"` | `:705` |
| `column` | `"4"`（4 列布局） | `:706` |
| `flush` | `"5"`（刷新间隔） | `:707` |
| `autoplay_card` | `"11"` | `:708` |
| `ps` | `refreshCount` | `:709` |
| `access_key` | `TokenManager.accessTokenCache`（**无则直接失败**） | `:697-701,710` |
| `appkey` | `AppSignUtils.TV_APP_KEY` = `4409e2ce8ffd12b8` | `:711`；常量 `AppSignUtils.kt:18` |
| `ts` | 秒级时间戳 | `:712` |
| `mobi_app` | `"android"` | `:713` |
| `device` | `"android"` | `:714` |
| `build` | `"8130300"` | `:715` |
| `sign` | `signForTvLogin` 计算 | `:718`；`AppSignUtils.kt:68-70` |

#### (3) MERGED 模式 · Web 半边 — `fetchMergedWebFeed`（`VideoRepository.kt:758-766`）

`version=1`、`feed_version="V8"`（**常量**，与 WEB 模式的时间戳不同）、`homepage_ver=1`、`ps`、`fresh_idx=idx`、`brush=idx`、`fresh_type="4"`；再 WBI 签名（`:767`）。

#### (4) MERGED 模式 · App 半边 — `fetchMergedMobileFeed`（`VideoRepository.kt:794-823`）

对齐 PiliNara 原版 app 端点，共 30+ 参数：`build=2001100`、`c_locale/s_locale=zh_CN`、`channel=master`、`column=4`、`device=pad`、`device_name=android`、`device_type=0`、`disable_rcmd=0`、`flush=5`、`fnval=976`、`fnver=0`、`force_host=2`、`fourk=1`、`guidance=0`、`https_url_req=0`、`idx`、`mobi_app=android_hd`、`network=wifi`、`platform=android`、`player_net=1`、`pull="true"/"false"`、`qn=32`、`recsys_mode=0`、`splash_id=""`、`statistics={appId:5,platform:3,version:2.0.1}`、`ts`、`voice_balance=0`；已登录再补 `access_key`（`:825-827`）；`signForAndroidHdLogin` + percent-encode（`:831-832`）。

#### (5) 任务书点名但**不存在**的参数

| 参数 | 结论 |
|---|---|
| `login_event` | 【事实】**全仓 0 命中**，v0.2.4 未使用 |
| `auto_refresh_state` | 【事实】**全仓 0 命中** |
| `bili_ticket` / `bili_ticket_expires` | 【事实】**全仓 0 命中** |
| `device` | 存在，但仅 `"android"`(MOBILE `:714`) / `"pad"`(MERGED `:799`) 两个字面量 |

### A3. 签名与鉴权

**两套签名体制并存：**

| 体制 | 实现 | 覆盖接口 | 关键行 |
|---|---|---|---|
| **WBI**（MD5(imgKey+subKey 混合 → mixinKey)） | `WbiUtils.kt:6-102`：重排表 `:7-12`、非法字符过滤 `:23-25`、`wts` `:68`、排序拼串 `:74-89`、`w_rid=md5(query+mixinKey)` `:92-98` | Web `rcmd` | 调用 `VideoRepository.kt:670,767` |
| **appkey+appsec MD5** | `AppSignUtils.kt`：`sign` `:51-63`、`signForTvLogin` `:68-70`、`signForAndroidHdLogin` `:93-100`（key/value 先 percent-encode 再签，`:112-118`） | App `feed/index` | 调用 `VideoRepository.kt:718,831` |

**WBI 密钥来源**：`WbiKeyManager.getWbiKeys()`（`WbiKeyManager.kt:43-63`），内存缓存 24h（`:23`）/ 预刷新阈值 1h（`:25`）/ `Mutex` 防并发刷新（`:34,52`），从 `getNavInfo().data.wbi_img` 拆出 img/sub key（`:82-90`）；`VideoRepository.resolveHomeFeedWbiKeys` 作为"缓存缺失时用 nav 兜底"的纯函数（`VideoRepository.kt:106-115`），调用处 `:658-664`、`:750-756`。

**Cookie 装配（`ApiClient.kt:54-175` 的 `AppSessionCookieJar`）**：

| 凭证 | 装配处 | 说明 |
|---|---|---|
| `buvid3` | `:108-121` | `TokenManager.buvid3Cache`，缺失时**本地生成** `UUID+"infoc"` |
| `SESSDATA` | `:124-134` | 登录态 |
| `bili_jct` | `:136-146` | CSRF |
| `DedeUserID` | `:148-158` | mid |
| `access_key` | 不走 Cookie，走 query（`VideoRepository.kt:710,827`） | **WBI 路径不使用 access_key** |
| `bili_ticket` | 【事实】不存在 | — |

**buvid 预热**：`ensureBuvid3FromSpi()`（`VideoRepository.kt:255-311`，含 `payload` 组包与 `activateBuvid`），只对 MOBILE 模式预热（`shouldPrimeBuvidForHomePreload` `VideoRepository.kt:78-80`，调用 `:339-341`）；MERGED 的 App 半边在缺 buvid 时补（`:790-793`，先 `awaitRestore()`）。

**未登录时走什么 —— 两条真实的匿名化策略（任务书点名的两个都在，且都在 `network-core`）：**

1. **`MergedAppFeedCookiePolicy`**（`network-core/.../policy/MergedAppFeedCookiePolicy.kt:31-39`）
   - 判定：`host == "app.bilibili.com" && encodedPath == "/x/v2/feed/index" && mobiApp == "android_hd"`（`:36-38`，常量 `:3-4,12`）。
   - 作用：**在 CookieJar 里返回空 Cookie 列表**（`core-data/.../ApiClient.kt:85-96`），身份改由请求头 + `access_key` + 签名承担。
   - 收窄理由写在注释里（`MergedAppFeedCookiePolicy.kt:26-29`）：不影响 Web 半边、不影响 MOBILE 单模式（同路径但 `mobi_app=android`）。
   - 配套身份头（`ApiClient.kt:3192-3246`）：`isHdFeedRequest` 判定 `:3196-3198`；UA 覆写 `:3209`；`app-key: android_hd` + `buvid` + `bili-http-engine: cronet` + `x-bili-trace-id` `:3217-3224`；`fp_local`/`fp_remote`/`session_id`/`x-bili-aurora-*` `:3240-3245`（常量 `:42-44`）。

2. **`HomeFeedAnonymizerPolicy` / `HomeFeedAnonymizerRuntime`**（`network-core/.../policy/HomeFeedAnonymizerPolicy.kt`）
   - 判定：`pluginEnabled && host == "api.bilibili.com" && encodedPath == "/x/web-interface/wbi/index/top/feed/rcmd"`（`:53-61`，常量 `:3-4`）。
   - 作用：命中即**清空该请求的全部 Cookie**（`ApiClient.kt:71-82`）→ 拿到"接近未登录公共热门"的推荐。
   - 统计：`totalHits/lastHitAtMs/lastHitHost/lastHitPath`（`:6-11,31-50`），仅本机计数。
   - 开关来源：`HomeFeedAnonymizerPlugin`（`feature/plugin/HomeFeedAnonymizerPlugin.kt:58-91`，`PLUGIN_ID="home_feed_anonymizer"` `:51`，`onEnable/onDisable` `:77-85`），URL 常量 `:53-54`。**它是插件，但被匿名化的判定与执行是纯策略 + CookieJar，不依赖插件运行时之外的东西。**
   - 【事实】任务书问的 `MergedAppFeedCookiePolicy` / `HomeFeedAnonymizerPolicy` **都存在**，且是本项目对"未登录/去个性化"的完整答案。

### A4. `core/plugin/feed/` 是不是首页推荐的一部分？——**不是**

**结论【事实】：`core/plugin/feed/` 是首页「订阅」Tab 的实现，与推荐流的唯一交点是"同属首页顶部 Pager 的一页"。**

证据链：

1. **数据源不同**：`FeedSourceCatalog.loadEnabledFeedSources` 只产出 RSS/Atom 的 `FeedSource(id,title,url)`（`FeedSourceCatalog.kt:5-9,37-74`），来源 = `SubscriptionFeedStore` + JS 插件 manifest 里 `kind=="feed"` 的模块（`:38-73`），与 B 站 API 无关。
2. **唯一入口不同**：`loadEnabledFeedSources` 的**唯一调用者**是 `SubscriptionFeedPage.kt:260`（grep 全仓仅此一处）。
3. **页面不同**：`SubscriptionFeedPage` 只在 `HomeTopTabEntry.Subscriptions` 分支渲染（`HomeScreen.kt:2086-2087`），而推荐页走 `HomeTopTabEntry.Category(RECOMMEND)` → `HomeCategoryPageContent`（`HomeScreen.kt:2447`）。
4. **Tab 是否出现由插件决定**：`isSubscriptionPluginOrFeedEnabled`（`FeedSourceCatalog.kt:10-35`）→ `ensureSubscriptionHomeTab`（`HomeTopCategoryPolicy.kt:190-207`）→ `HomeScreen.kt:483-504`。**订阅 Tab 默认不显示**，装了 `subscription_feed` 插件或配了 RSS 才出现。
5. **`FeedLayoutAllocation`、`FeedTitleResolver`、`FeedImportParser`、`FeedDocumentParser`、`FeedHtmlParser`、`FeedReadingStore`、`ArticleNoteStore`、`FeedConditionalStore` 的数据类型全是 `ParsedFeedItem` / `ImportedSubscription`**（`FeedModels.kt:16-29`、`FeedLayoutAllocation.kt:16-30`、`FeedImportParser.kt:9-12`），**从不出现 `VideoItem`**。`FeedLayoutAllocation.allocateCoverAspectRatio` 是给 RSS 卡片定 0.75/0.8/1.0 三档宽高比（`FeedLayoutAllocation.kt:3-10`），与首页视频卡的 16:10/16:9（`HomeFeedGridPolicy.kt:67-77`）是两套。

**各自职责（逐条）：**

| 文件 | 职责 | 关键行 |
|---|---|---|
| `FeedPlugin.kt`（**在 `core/plugin/`，不在 `feed/`**） | 信息流插件接口：`shouldShowItem(item)` / `shouldShowItem(item, feedKind)`；另有 `FeedTransformPlugin.transformFeedItems`；`enum FeedKind{GENERIC,HOME_RECOMMEND,HOME_POPULAR,HOME_RANK,HOME_REGION,SEARCH}` | `core/plugin/FeedPlugin.kt:14-30,33-35,40-53` |
| `feed/FeedFetcher.kt` | RSS 抓取：独立 OkHttp（连接 6s/读 8s/call 8s，`:23-29`）、2MB 上限（`:20,46,104`）、ETag/Last-Modified 条件请求（`:86-87,96`）、`loadFeedSources` 并发拉多源（信号量 4，`:159-188`）、排序按 `publishedEpochSec` 倒序（`:149-152`）、每源完成即回调（`:186`） | — |
| `feed/FeedSourceCatalog.kt` | 把「内置订阅插件 + JS 插件 feed 模块」归拢成 `FeedSource` 列表 | `:37-74` |
| `feed/FeedConditionalStore.kt` | 存每源的 ETag/Last-Modified，避免重复拉全量 | 被 `SubscriptionFeedPage.kt:270,283` 使用 |
| `feed/FeedReadingStore.kt` | 已读集合 + 正文缓存（`:35` 起） | `SubscriptionFeedPage.kt:265-267,280` |
| `feed/ArticleNoteStore.kt` | 文章的本地笔记（`:25` 起） | `SubscriptionFeedPage.kt:670-671,1054` |
| `feed/SubscriptionFeedStore.kt` | 订阅列表 CRUD + `revision` 流（`:19` 起） | `SubscriptionFeedPage.kt:182`、`HomeScreen.kt:476` |
| `feed/FeedDocumentParser.kt` / `FeedHtmlParser.kt` / `FeedArticleExtractor.kt` | RSS/Atom/HTML 解析与正文抽取 | `FeedFetcher.kt:167` 调用 `parseFeedDocument` |
| `feed/FeedImportParser.kt` | OPML / 文本清单批量导入（`:14-33`、`parseOpmlSubscriptions :35`） | `SubscriptionFeedPlugin.kt:413` |
| `feed/FeedTitleResolver.kt` | 源标题推导（手动 > 解析 > host/path），导入时并发 4 取标题 | `:11-26,42-54` |
| `feed/FeedModels.kt` | `FeedSource/ParsedFeed/ParsedFeedItem/FeedBlock/FeedInline/FeedLoadSnapshot` | `:5-59` |
| `feed/FeedLayoutAllocation.kt` | 封面宽高比分桶 + `stabilizeFeedOrder`（不在顶部时保序、新条目接尾） | `:3-10,16-26` |
| `feature/plugin/SubscriptionFeedPlugin.kt` | 订阅插件本体；capability = `{FEED_SOURCE, NETWORK, PLUGIN_STORAGE}`；`PLUGIN_ID="subscription_feed"` | `:43-60,73` |

**它把"推荐流"从"单一接口"变成了什么？**
【事实】**首页推荐流本身没有被改变，仍是单接口。**被改变的是**首页这个容器**：从"一条推荐流"变成"多页容器"（8 个分类页 + 1 个分区插件页 + 1 个订阅页），其中订阅页是一个**完全独立的多源、条件请求、带缓存、可导入导出、可阅读原文**的流（`SubscriptionFeedPage.kt:257-293`）。
【推断】任务书"把推荐流从单一接口变成什么"的预期，在 v0.2.4 里对应的其实是 **MERGED 模式**（两个接口交错合并成一条流），而不是插件系统。

### A4-附. 数据流图（文字版，含线程）

```
[MainActivity] MainActivity.kt:1055  VideoRepository.preloadHomeData()
      └─ AppScope.ioScope.async                                  (IO 线程)
         -> getHomeVideosInternal(idx=0)                          VideoRepository.kt:327-361,584
            ├─ MOBILE : fetchMobileFeed  -> api.getMobileFeed    :695-743   (Retrofit suspend → OkHttp 线程)
            ├─ MERGED : coroutineScope { async{web} async{app} } :616-644
            │            -> HomeFeedMergePolicy.mergeFeeds       HomeFeedMergePolicy.kt:23-42
            └─ WEB    : fetchWebFeed -> WBI 签名 -> SharedContentRepository.recommendationResponse
         -> preloadedHomeVideos / hasCompletedHomePreloaded      :314-316
      └─ MainActivity.kt:1084 / :2324 轮询 isHomeDataReady() 给启动页

[HomeViewModel] init HomeViewModel.kt:386-455
   ├─ viewModelScope.launch × (设置 collect、插件 collect)        Main.immediate
   ├─ PluginManager.awaitPluginReady(ADFILTER/BILIPAI_FEED_FILTER) :407-414
   │     └─ reFilterAllContent()  ← 冷启动补过滤                  :458-495
   └─ loadData()  :1179-1194   (只在 RECOMMEND 首次)
        └─ viewModelScope.launch { fetchData(isLoadMore=false) }  Main

[HomeViewModel.fetchData] :1396-1637
   ├─ 分支: LIVE  -> fetchLiveRooms(...)            :1413-1417 / :1906-1989
   ├─ 分支: FOLLOW -> fetchFollowFeed(...)          :1420-1427 / :1693-1834
   │        └─ DynamicRepository.getDynamicFeed(scope=HOME_FOLLOW, type="video")  :1723
   ├─ 分支: RECOMMEND -> VideoRepository.getHomeVideos(recommendRequestIndex)     :1453
   │        └─ withContext(Dispatchers.IO)  + 预加载命中直接返回   VideoRepository.kt:557-581
   ├─ 分支: POPULAR / tid>0 -> getPopular|getRanking|getWeekly|getPrecious|getRegionVideos :1454-1469
   │
   ├─ 过滤与去重（CPU 密集，显式切线程）:
   │     withContext(Dispatchers.Default) { prepareHomeFeedVideos(...) }   :1484-1493
   │        ├─ validVideos = bvid/title 非空                        :103
   │        ├─ 屏蔽 UP（Room 名单）                                 :104
   │        ├─ 本地负反馈（今日观看反馈）                            :105 / filterHomeFeedbackVideos :1157-1164
   │        ├─ PluginManager.filterFeedItems(feedKind)              :106 / PluginManager.kt:221-252
   │        │     ├─ FeedPlugin.shouldShowItem(item, feedKind)      core/plugin/FeedPlugin.kt:29
   │        │     └─ FeedTransformPlugin.transformFeedItems         :33-35
   │        └─ JsonPluginManager.filterVideos(...)                  :107-108 / JsonPluginManager.kt:208-259
   │
   ├─ 去重/合流（仍在 fetchData 的 Main 上下文内，列表为 persistentList）:
   │     selectHomeFeedIncomingVideos(...)                          :1515-1520 / :72-90
   │     appendDistinctByKey / prependDistinctByKey                 :1526,1528 / FeedMergeUtils.kt:6-37
   │     resolveHomeRefreshKeptOldItemCount（增长守卫）             :1530-1535 / HomeRefreshUiPolicy.kt:92-96
   ├─ 写状态 updateCategoryState / updatePopularCategoryState       :1564-1568,1590-1594 / :1639-1689
   └─ RECOMMEND 时 viewModelScope.launch { rebuildTodayWatchPlan }  :1596-1608
            └─ performTodayWatchPlanRebuild :627-712
                 ├─ TodayWatchPlugin.buildRecommendations        :682-701 (RecommendationPluginApi.kt:88-92)
                 └─ VideoRepository.getHomeVideos(refreshIdx+1)  :732  ← 用同一游标体系扩池

[HomeScreen 渲染] HomeScreen.kt
   ├─ topTabEntries = resolveHomeTopTabEntries(...) + ensureSubscriptionHomeTab   :495-504
   ├─ HorizontalPager(topTabEntries)                                             :2055-2067
   │    ├─ Entry.Partition      -> PartitionContent                             :2069-2085
   │    ├─ Entry.Subscriptions  -> SubscriptionFeedPage                          :2086-2087
   │    │     └─ LaunchedEffect { withContext(IO){loadEnabledFeedSources} ; loadFeedSources(...) }
   │    │           SubscriptionFeedPage.kt:257-293 / FeedFetcher.kt:140-190 (Semaphore 4, Dispatchers.IO)
   │    └─ Entry.Category(c)    -> HomeCategoryPageContent                       :2447-2547
   │          └─ 自动翻页触发: derivedStateOf(shouldRequestHomeCategoryLoadMore) + LaunchedEffect
   │                HomeCategoryPage.kt:296-316 / :160-172
   │
   ├─ 骨架态: HomeFeedSkeletonCard × (columns*5)                                :2377-2390
   └─ 封面预取: LaunchedEffect{...snapshotFlow...}  HomeScreen.kt:3151-3224
        └─ resolveHomeCoverPreloadRange (上限 2 条, 省流量=0)  HomePerformancePolicy.kt:21-49

[详情返回封面预热] AppNavigation.kt:1355 prefetchHomeCoverImages(context, candidates)
   └─ HomeCoverReturnPrefetchRegistry (LRU 64)  HomeCoverReturnPrefetch.kt:24-100
```

**线程小结**：网络在 `Dispatchers.IO`（`VideoRepository.kt:557`、`SharedContentRepository.kt:55`）；过滤/去重在 `Dispatchers.Default`（`HomeViewModel.kt:1485`）；状态写在 `viewModelScope`(Main)（`:1182`、`:1198`、`:1359`）；RSS 抓取在 `Dispatchers.IO` + `Semaphore(4)`（`FeedFetcher.kt:161-163`），回调切回 `Dispatchers.Main.immediate`（`:186`）。

---

## ② 接口与参数全表

| # | 接口 | 用途 | 参数（●=签名产生 ◎=设置来源 ○=游标） | 行号 |
|---|---|---|---|---|
| 1 | `GET x/web-interface/wbi/index/top/feed/rcmd` | 首页推荐 · WEB / MERGED-Web | `ps`◎ `fresh_type` `fresh_idx`○ `feed_version`(=时间戳) `y_num`○ ●`wts` ●`w_rid` | 定义 `ApiClient.kt:521`；参数 `VideoRepository.kt:666-670`；MERGED 变体 `:758-767` |
| 2 | `GET https://app.bilibili.com/x/v2/feed/index` | 首页推荐 · MOBILE | `idx`○ `pull`○ `column` `flush` `autoplay_card` `ps`◎ `access_key` `appkey` `ts` `mobi_app` `device` `build` + `sign` | 定义 `ApiClient.kt:525`；参数 `VideoRepository.kt:703-716`；签名 `:718` |
| 3 | 同上（`@QueryMap(encoded=true)`） | 首页推荐 · MERGED-App | 30+ 参数见 §A2(4)，+ `sign` | 定义 `ApiClient.kt:530-533`；参数 `VideoRepository.kt:794-832` |
| 4 | `GET https://app.bilibili.com/x/feed/dislike` | 不感兴趣/反馈回写 | `goto` `id` `reason_id`\|`feedback_id` `build` `mobi_app` `access_key` `appkey` `ts` `sign` | 定义 `ApiClient.kt:535-538`；构造 `ActionRepository.kt:56-75`；签名+发送 `:130` |
| 5 | `GET x/web-interface/popular` | 热门→综合热门 | `pn`○ `ps=30` | 定义 `ApiClient.kt:540-544`；调用 `VideoRepository.kt:860-862`；Tab 分支 `HomeViewModel.kt:1456` |
| 6 | `GET x/web-interface/ranking/v2` | 热门→排行榜 | `rid=0` `type=all` | 定义 `:546-549`；分支 `HomeViewModel.kt:1457` |
| 7 | `GET x/web-interface/popular/series/list` / `series/one` | 热门→每周必看 | `number` | 定义 `:554-560`；分支 `HomeViewModel.kt:1458` |
| 8 | `GET x/web-interface/popular/precious` | 热门→入站必刷 | — | 定义 `:551-552`；分支 `HomeViewModel.kt:1459` |
| 9 | `GET x/web-interface/dynamic/region` / `x/web-interface/newlist` | 分区页（游戏/知识/科技/追番） | `rid`=tid `pn`○ `ps=30` | 定义 `:564-578`；分支 `HomeViewModel.kt:1464-1465`；tid `HomeUiState.kt:52-55` |
| 10 | `GET x/polymer/web-dynamic/v1/feed/all`（+`/update`） | 关注 Tab | `update_baseline` 等（`DynamicRepository`） | 定义 `:1790,1810`；调用 `HomeViewModel.kt:1723-1732` |
| 11 | `GET xlive/app-interface/v2/index/feed` / `room/v3/area/getRoomList` | 直播 Tab（页内已是"已独立"提示） | `parent_area_id` `area_id` `page`○ `page_size` `sort_type` | 定义 `:621,582`；调用 `HomeViewModel.kt:1919,1957`；页内提示 `HomeCategoryPage.kt:557-581` |
| 12 | `GET x/v2/feed/index/story` | **竖屏沉浸流**（非首页） | `fnval=4048` `fnver` `force_host` `fourk` `qn=32` `ps=20` `aid` `bvid` | 定义 `ApiClient.kt:1653-1663`；调用 `StoryRepository.kt:37` → `VideoDetailPortraitOverlayAdapter.kt:149` |
| 13 | RSS/Atom 源（任意 http(s)） | 订阅 Tab（非推荐） | `If-None-Match` / `If-Modified-Since`（条件请求） | `FeedFetcher.kt:74-125`；来源 `FeedSourceCatalog.kt:37-74` |

---

## ③ 加载状态机（HomeViewModel）

### 3.1 状态载体

`HomeUiState`（`HomeUiState.kt:155-204`）里**没有单一 `videos` 作为真相**，真相是**按分类分槽的 Map**：

- `categoryStates: Map<HomeCategory, CategoryContent>`（`:165`）
- `popularCategoryStates: Map<PopularSubCategory, CategoryContent>`（`:167`）
- `CategoryContent{videos, liveRooms, followedLiveRooms, isLoading, error, pageIndex, hasMore}`（`:140-148`）
- 兼容字段 `videos/liveRooms/isLoading/error`（`:157-161`）由 `updateCategoryState` 同步回写（`HomeViewModel.kt:1679-1687`）——**双写，是明确标注的迁移期设计**。

每个分类通过 `homeStateFlow{}`（`HomeViewModel.kt:341-350`，`map+distinctUntilChanged+stateIn(Eagerly)`）暴露独立 `StateFlow`，避免一个分类变化让全首页重组。

### 3.2 四类入口

| 入口 | 行号 | 守卫 | 线程 |
|---|---|---|---|
| `loadData()` 首次加载 | `:1179-1194` | `categoryInitialLoadJob?.cancel()` 先取消上一轮（`:1180`） | `viewModelScope` |
| `refresh(category)` 下拉刷新 | `:1196-1266` | `if (_isRefreshing.value) return`（`:1197`） | `viewModelScope` |
| `loadMore()` 上拉翻页 | `:1338-1362` | `isLoading \|\| _isRefreshing \|\| !hasMore` 早退（`:1346`）+ 直播无更多早退（`:1354`）+ 热门仅综合热门可翻页（`:1347-1351`） | `viewModelScope` |
| 自动翻页（UI 驱动） | `HomeCategoryPage.kt:296-316` | `shouldRequestHomeCategoryLoadMore`（`:160-172`）：`hasVisibleContent && totalItems>0 && lastVisibleItemIndex >= totalItems-4 && !isLoading && hasMore` | Compose 侧 |

**【事实】自动翻页的"防风暴"是三重**：`derivedStateOf` 只在依赖变化时重算（`HomeCategoryPage.kt:296-313`）+ `LaunchedEffect(shouldLoadMore)` 只在布尔翻转时发一次（`:314-316`）+ 上面 ViewModel 层早退。**任务书问的"增长守卫"在"请求"这一侧存在。**

### 3.3 `fetchData` 主流程（`:1396-1637`）

1. 置 `isLoading=true`（`:1406-1410`）。
2. 特例分流：`LIVE`（`:1413-1417`）、`FOLLOW`（`:1420-1427`）各自独立函数，**不参与下面的游标/去重逻辑**。
3. 计算两个页码：
   - 分页流页码 `pageToFetch = resolvePagedFeedPageToFetch(...)`（`:1439-1444` → `HomeRefreshUiPolicy.kt:14-23`）：`loadMore → pageIndex+1`；`manualRefresh && advanceOnManualRefresh → pageIndex+1`；否则 `1`。
   - **推荐游标** `recommendRequestIndex = resolveRecommendFeedRequestIndex(...)`（`:1445-1449` → `:115-125`）：**`loadMore || manualRefresh` → `refreshIdx+1`；否则 `0`**。
4. 取流（`:1452-1470`）。
5. `ensureActive()` 后取流失败若是 `CancellationException` 直接抛（`:1471-1474`）——**不把取消当失败**。
6. `!isLoadMore` 时后台刷用户信息（`:1476-1478`）；`isLoadMore` 时 `delay(100)`（`:1480`）。
7. **过滤/去重在 `Dispatchers.Default`**（`:1484-1493`，注释明写"Keep it off the UI dispatcher"）。
8. 成功分支（`:1495-1608`）：
   - **游标前进规则**（`:1497-1505`）：`shouldAdvanceRecommendFeedRequestIndex(category, isLoadMore, isManualRefresh, validVideoCount)`（`:141-150`）= `category==RECOMMEND && (loadMore||manualRefresh) && validVideoCount>0` → `refreshIdx = maxOf(refreshIdx, recommendRequestIndex)`。**幂等；无有效内容则不前移（避免跳页）。**
   - 判定增量刷新：`useIncrementalRecommendRefresh = !isLoadMore && RECOMMEND && (incrementalTimelineRefreshEnabled || homeRefreshTipVisible)`（`:1511-1513`）。
   - `selectHomeFeedIncomingVideos`（`:1515-1520` → `:72-90`）。
   - 合并（`:1524-1538`）：
     - `isLoadMore` → `appendDistinctByKey`（`:1526`）
     - 增量刷新 → `prependDistinctByKey` + **增长守卫**（`:1527-1536`）
     - 其它（普通下拉刷新、首屏）→ **整体替换** `incomingVideos.toImmutableList()`（`:1537`）
   - `pageIndex` 推进（`:1545-1556` → `HomeRefreshUiPolicy.kt:36-49`）：`loadMore` 仅在 `incomingCount>0` 时 +1；手动翻页在 `incomingCount>0` 时落到 `pageToFetch`，否则**回到 0**。
   - `hasMore`：POPULAR 用 `supportsPopularLoadMore`，其余**恒 `true`**（`:1557-1561`）。
9. 空页 / 全被过滤 / 全重复分支（`:1573-1595`）：**`hasMore=false`**，`error` 仅在"非 loadMore 且当前列表为空"时给"没有更多内容了"（`:1576-1579`）。
10. 失败分支（`:1609-1635`）：`isLoadMore` 时**保留 `hasMore`**（`shouldKeepHomeCategoryAutoPagingAfterFailure` `:187-189`）；非 loadMore 且列表空时写 `error`。
11. RECOMMEND 成功后触发 `rebuildTodayWatchPlan`（`:1596-1608`）。

### 3.4 空页与重复页

**【事实】重复页 = `incomingVideos.isEmpty()`**：`selectHomeFeedIncomingVideos` 在 `isLoadMore||isIncrementalRefresh` 时先按 `bvid` 去掉当前列表已有的条目（`:78-84`），所以"整页都是已看过的"会得到空列表 → 进入 `:1573` 分支 → **`hasMore=false`，翻页停止**。
**【推断】代价**：一次服务端重复下发会让该分类的自动翻页**停到下一次下拉刷新为止**（`refresh` 的 `else -> 1`/整表替换分支会重算）。上游没有 `stagnantLoads` 那种"再看几次"的容忍度，bbspace 的 `MAX_STAGNANT_LOADS=2` 在这里是更宽容的做法。

**【事实】首屏空页**：`!isLoadMore && oldState.videos.isEmpty()` → `error="没有更多内容了"`（`:1576-1578`）。
**【事实】MOBILE 模式空页会自动回退 WEB**（`VideoRepository.kt:608-614`）——这是在仓库层，不在 ViewModel 层。

### 3.5 游标（`idx`）推进规则汇总

| 变量 | 初值/定义 | 何时前进 | 前进量 | 上限 |
|---|---|---|---|---|
| `refreshIdx`（推荐专用） | `private var refreshIdx = 0`（`HomeViewModel.kt:352`） | `shouldAdvanceRecommendFeedRequestIndex`（`:141-150`）为真时 `maxOf`（`:1504`） | +1 | 无 |
| `pageIndex`（分页流） | `CategoryContent.pageIndex=1`（`HomeUiState.kt:146`） | `resolvePagedFeedPageIndexAfterFetch`（`HomeRefreshUiPolicy.kt:36-49`） | +1 或回 0 | 无 |
| `livePage` | `:353` | `fetchLiveRooms` 加载更多时 `livePage`（`:1907`） | 【推断】见 `:1980` 附近分支 | 无 |

**首屏 `idx` 语义**：`resolveRecommendFeedRequestIndex` 在既非 loadMore 也非手动刷新时返回 **0**（`VideoRepository.kt:115-125`），即"首屏永远从 idx=0 起"；且 **`getHomeVideos(idx=0)` 会优先消费预加载结果**（`VideoRepository.kt:559-577`），所以首屏通常不发第二个请求。

**去重 key**（`videoItemKey`，`HomeViewModel.kt:1872-1879`）：`dynamicId > cid > (owner.mid+title+pubdate)`；`HomeFeedMergePolicy.keyOf` 是另一套（`dynamicId > bvid > aid > id > mid:title:pubdate`，`HomeFeedMergePolicy.kt:48-54`）。
**【推断】两套 key 不完全一致**：MERGED 合并用 bvid 优先，进入列表后的去重用 cid 优先。同一视频若 bvid 相同但 cid 缺失，可能被合并层去重、而被列表层视作两条。**建议 bbspace 不要照抄这两套并存。**

### 3.6 增量刷新的"旧内容"语义（`FeedPrependScrollPolicy` 的搭档）

- 刷新后 UI 关系：`refreshNewItemsCount` 记录新增条数（`:1570-1572`），`recommendOldContentAnchorBvid` 记录刷新前首条 bvid（`:1208-1232`），`recommendOldContentStartIndex` 记录分界（`:1222-1226`），`recommendOldContentRevealKey` 表示分界线已解锁（`:1247-1255`）。
- 分界线显示条件 `shouldShowRecommendOldContentDivider`（`HomeRefreshUiPolicy.kt:73-85`）。
- **刷新撤销**：快照 `HomeRefreshUndoSnapshot`（`HomeViewModel.kt:152-156`，构造 `:158-174`），`undoRefresh()`（`:1300-1314`），5s 超时自动丢弃（`:1325-1331`，常量 `:205`）。
- **`FeedPrependScrollPolicy`（`core/ui/components/FeedPrependScrollPolicy.kt:16-33`）**：把"前置插入 N 条后，用户当前看的那张卡"换算成新的 grid index + offset，使插入后**视口不跳**。判据 `gridIndex <= anchor.index` 时返回 null（`:31`）——"已经量过/普通分页/替换都不该移动视口"（注释 `:30`）。

---

## ④ 多 Tab 与混合内容

### 4.1 首页顶部有哪些 Tab

**【事实】Tab 不是硬编码列表，而是"设置顺序/可见性 + 三个来源"的合成结果**：

- 数据模型 `HomeTopTabEntry`：`Category(HomeCategory)` / `Partition` / `Subscriptions`（`HomeTopCategoryPolicy.kt:6-20`）。
- `HomeCategory` 共 8 个：`RECOMMEND, FOLLOW, POPULAR, LIVE, ANIME(13), GAME(4), KNOWLEDGE(36), TECH(188)`（`HomeUiState.kt:47-56`）。
- 默认可见 5 个：`RECOMMEND, FOLLOW, POPULAR, LIVE, GAME`（`HomeTopCategoryPolicy.kt:22-33`）。
- 用户可自定义的顺序/可见集合（`:65-106`）；`Partition`（分区页，内置插件）与 `Subscriptions`（订阅）在可自定义集合里（`:91-92`）。
- **`Subscriptions` 需 `ensureSubscriptionHomeTab` 才插入**（`:190-207`），由 `isSubscriptionPluginOrFeedEnabled` 决定（`FeedSourceCatalog.kt:10-35`；`HomeScreen.kt:483-504`）。
- `HomeUiState.popularSubCategory` 又给出热门页的 4 个子 Tab：`COMPREHENSIVE/RANKING/WEEKLY/PRECIOUS`（`HomeUiState.kt:66-71`），直播页 2 个：`FOLLOWED/POPULAR`（`:61-64`）。

| Tab | 数据源 | 缓存策略 | 行号 |
|---|---|---|---|
| 推荐 RECOMMEND | 见 §A1（WEB/MOBILE/MERGED） | 进程内 `categoryStates[RECOMMEND]`；`refreshIdx` 内存游标；无磁盘缓存；启动预加载 `preloadedHomeVideos` 一次性消费 | `HomeViewModel.kt:1453`、`VideoRepository.kt:313-361` |
| 关注 FOLLOW | `x/polymer/web-dynamic/v1/feed/all`（`refresh=true`, `scope=HOME_FOLLOW`, `type=video`） | `DynamicRepository` 内部分页游标 + 基线（`update_baseline`）；`categoryStates[FOLLOW]` | `HomeViewModel.kt:1714-1732` |
| 热门 POPULAR | 4 个子源见 §② #5-8 | **每个子分类独立 `popularCategoryStates[sub]`**（切回不丢） | `HomeUiState.kt:167`、`HomeViewModel.kt:1639-1663` |
| 直播 LIVE | `LiveRepository.getFollowedLive/getLiveRooms` | `livePage` 游标 + `hasMoreLiveData` 标志；`categoryStates[LIVE]` | `HomeViewModel.kt:1906-1989` |
| 追番/游戏/知识/科技 | `x/web-interface/dynamic/region`（`tid`） | `categoryStates[c]` + `pageIndex` | `HomeViewModel.kt:1464-1465` |
| 分区 PARTITION | 内置 Partition 插件页 | 自带状态 | `HomeScreen.kt:2069-2085` |
| 订阅 SUBSCRIPTIONS | RSS/Atom（多源） | **磁盘缓存**：`FeedReadingStore`（条目/已读/正文）+ `FeedConditionalStore`（ETag/LM）+ `SubscriptionFeedStore` | `SubscriptionFeedPage.kt:260-285` |

**Pager 与状态同步（`HomePagerSyncPolicy`）**

`HomeScreen.kt:2053` 起的 `HorizontalPager` + `pagerState`（`:518`）。`policy/HomePagerSyncPolicy.kt` 的纯函数解决"哪些翻页该写回 ViewModel"：

- `shouldSwitchHomeCategoryFromPager`（`:31-44`）四重与：非顶层 / 未同步 / Pager 正在滚 / 程序化切页 → 都不写。
- `resolveHomeInitialTopTabPage`（`:104-123`）与 `shouldTreatInitialHomePagerPageAsSyncedWithState`（`:125-132`）**按"入口身份"而非页码**恢复，`Partition`/`Subscriptions` 也算已同步。
- `resolveHomePagerTargetPage`（`:134-149`）用 `retainedEntry` 定位，**避免自定义顺序后把旧页码解释成另一个分类**（`HomeScreen.kt:533-540`）。
- `resolveHomePagerSettledAction`（`:46-72`）返回 `NONE/SWITCH_CATEGORY/OPEN_LIVE_LIST/OPEN_BANGUMI`。

**`HomeScrollCoordinator`（`policy/HomeScrollCoordinator.kt`）**的作用：把"滚动增量"折成三件互不干扰的事——

1. 顶栏/搜索框折叠偏移：`reduceHomePreScroll`（`:140-183`），带 `SHOW_AT_TOP_ONLY` 语义（离开首屏保持收起，`:160-162`）与回顶锁定强制展开（`:159`）；量化 `quantizeHomeHeaderOffset`（`:33-39`）减少逐像素状态写入；`resolveHomeHeaderSettleTransition`（`:60-69`）只在差 >0.5px 时才动画。
2. 底栏自动隐藏意图：`BottomBarVisibilityIntent`，阈值 ±10px（`:151,166-171`）。
3. 全局滚动偏移（供 liquid glass 用）：`resolveNextHomeGlobalScrollOffset`（`:176-180`）。
另有 `resolveHomeEmbeddedPageTopPaddingPx`（`:41-48`）让各页 top padding 跟随折叠；`resolveHomeHeaderListIndex`（`:94-104`）**把订阅页的首可见项也算进来**（`:99-103`），即"订阅 Tab 上滑同样要折叠顶栏"。

### 4.2 混合内容：首页到底混了什么

**【事实】视频网格里没有直播卡/专栏卡/广告位。** 能出现在推荐页的"非视频卡"只有 3 类，且**全部是整行（`StaggeredGridItemSpan.FullLine`）头部块，不是插在视频之间的混排卡**：

| 元素 | 出现条件 | 行号 |
|---|---|---|
| Hero Carousel（顶部横滑大图，从推荐视频里挑） | `RECOMMEND && homeHeroCarouselEnabled` | `HomeCategoryPage.kt:584-611`；选择/排除 `:318-339`；策略 `HomeHeroCarouselPolicy.kt` |
| 「今晚看什么」推荐单卡（插件产出） | `RECOMMEND && todayWatchEnabled` | `HomeCategoryPage.kt:612-630`；卡片实现 `:907` |
| 「旧内容分界」分割线 | `RECOMMEND && shouldShowRecommendOldContentDivider` | `HomeCategoryPage.kt:655-672`，插入 `:677-710` |
| 热门子分类分段控件 / 每周必看入口 | POPULAR | `:632-641`、`:644-653` |
| 直播页"已独立"引导块 | `LIVE` | `:557-581` |
| 底部 loading/上拉指示 | `categoryState.isLoading \|\| hasMore` | `:747-754` |

**因此任务书问的 `FeedLayoutAllocation` / `FeedTitleResolver` 是订阅流的东西，不参与首页混排**；`HomeFeedGridPolicy` 是**列数/宽高比/间距**策略，不是"混排"策略：

- `resolveHomeFeedGridColumns`（`HomeFeedGridPolicy.kt:30-61`）：单列模式最小卡宽 280dp / 最多 2 列；多列按 `cardWidthPreset.minCardWidthDp ?: 180` 与宽度档上限（Compact 4 / Medium 6 / Expanded 6 / Large 7 / ExtraLarge 8）。
- `resolveHomeFeedCardAspectRatio`（`:67-77`）：Compact/Medium 16:10；Expanded 以上 16:9。
- `resolveHomeFeedStoredColumnCount`（`:24-28`）：**窄屏与宽屏各记一套捏合列数**（折叠屏外屏/内屏互不污染）。
- 行对齐：`resolveHomeFeedAlignedRows`（`HomeCategoryPage.kt:697-701`）让"截断卡片"按整行推进，避免某条长标题把单条 lane 拉歪（注释 `:695-696`）。
- 间距：`HomeScreen.kt:2047`（`Arrangement.spacedBy(homeFeedCardLayout.itemSpacingDp.dp)`）与 `HomeCategoryPage.kt:283`（同式，供 `HomeFeedAlignedRows` 的整行 `Row` 用）；0.2.4 把铰链专用 arrangement 换成了 hinge-safe 内容容器，见 §⑥。

**`HomeFeedMergePolicy`（`HomeFeedMergePolicy.kt:23-42`）**：只在 MERGED 模式的**仓库层**用，交错 `app[i], web[i]`（app 在前），按 `keyOf` 去重，一方耗尽后追加另一方剩余（`:31-40`）。**纯函数、无副作用**（注释 `:11`），有单测友好性。

**模型**：`RecommendItem`（`core-data/.../ListModels.kt:312-333`）→ `VideoItem`（`:334+`）。**`RecommendResponse.data` 只有 `item` 一个字段**（`:306-309`），**没有 banner/ad 字段**。
【推断】Web 端点若返回 `goto != "av"` 的条目，会经 `toVideoItem()` 得到空 bvid 后被 `filter { it.bvid.isNotEmpty() }` 丢掉（`VideoRepository.kt:679`）；MOBILE/MERGED 侧则显式 `filter { it.goto == "av" }`（`:729`、`:843`）。**即"广告位"在数据层就不存在模型**，不是被 UI 藏起来的。

---

## ⑤ 交互回写路径

### 5.1 不感兴趣（`HomeNotInterestedPolicy` / `HomeNotInterestedReasonSheet`）

**触发链**：长按卡（`HomeScreen.kt:2411-2416`）→ 卡片先播消散动画 → 弹原因面板（`HomeScreen.kt:3261-3284`）→ 选原因 → `viewModel.markNotInterested(video, reason, dissolveAnimationEnabled=false)`（`:3272-3277`）。
【事实】顺序是"**先移出列表、再问原因**"——面板文案自己写了这一点（`HomeNotInterestedReasonSheet.kt:52`："视频已移除。选择原因可减少相关推荐，直接关闭则仅减少该视频推荐。"）。

**原因来源**：优先用服务端下发的 `video.recommendationFeedback.reasons`（`HomeNotInterestedPolicy.kt:78-81`）；没有则本地造 5 条：这个内容 / UP主 / 分区 / 此类内容过多 / 推荐过（`:83-118`）。关键词抽取 `extractHomeFeedbackKeywords`（`:141-157`，中文 2-6 字 + 英文数字 3+，带停用词，最多 6+4 个）。

**动作决策**：`resolveHomeNotInterestedAction`（`:33-52`）→ `RecommendationFeedbackLocalAction{VIDEO_ONLY, CREATOR, CATEGORY, SIMILAR_CONTENT}`；旧数据可能是 `VIDEO_ONLY`，用文案二次推断（`:58-68`）。

**三条回写路径（是否回写推荐流 = 是，且分本地/服务端两侧）**：

| 路径 | 生效范围 | 行号 |
|---|---|---|
| ① 本地"今日观看负反馈"落盘（`TodayWatchFeedbackStore`） | 立即影响**当前列表**与**今日看什么**；**重启后仍生效**（SharedPreferences `today_watch_feedback/feedback_payload_v1`） | `HomeViewModel.kt:1123-1145`；`TodayWatchFeedbackStore.kt:17-22,41-44,80-180` |
| ② 本地屏蔽 UP（Room `BlockedUpRepository`）+ 全量重过滤 | `blockedMids` 持久；`reFilterAllContent()` 重扫所有分类 | `HomeViewModel.kt:1045-1052,1099-1121,458-495`；Room 实体 `AppDatabase.kt:17` |
| ③ 服务端反馈（`x/feed/dislike`） | 需 `recommendationFeedback.supportsServerSync && reason.id != null`；失败降级为"已在本地生效，服务器同步失败" | `HomeViewModel.kt:1078-1094`；`ActionRepository.kt:115-135` |

**容量与守卫**（`TodayWatchFeedbackStore.kt:41-44`）：`dislikedBvids` 上限 200、`dislikedCreatorMids` 120、`dislikedKeywords` 80、`recentDislikedVideos` 24，全部 `takeLast`。

**过滤匹配规则**（`HomeNotInterestedPolicy.kt:159-218`）：bvid 精确不等；creator mid 不等；分区用 `tname` 精确/互相包含（中文 2 字分区名特殊处理 `:189-204`）；标题关键词要求"命中词长 ≥4 或命中 ≥2 个"（`:206-218`，防"日常"这类短词误伤）。

**一个精细的时序**：`pendingNotInterestedRefilterBvids`（`HomeViewModel.kt:380`）——若本次动作会产生新关键词/新屏蔽 UP，先登记 bvid（`:1053-1055`），等**消散动画结束时**才 `reFilterAllContent()`（`:926-944`，判据 `:928`），避免动画中途列表重排。同时在 `:401-404` 有一个反向守卫：屏蔽名单 Flow 变化时，**若还有 pending 就跳过重过滤**，把重活留给动画结束那一刻。

**这是否影响下一次请求？**【事实】**不影响 HTTP 请求参数**（不往 feed 请求里加任何"已屏蔽"字段）；只影响**客户端过滤**与**今日看什么插件**的 `feedbackSignals`（`HomeViewModel.kt:694-699`）。
【推断】所以服务端下一刷仍可能再下发同一条视频，客户端再过滤一次。**bbspace 若想做"下次不再出现"，只能靠本地集合（上游就是这么做的）。**

### 5.2 今日观看反馈（`TodayWatchFeedbackStore` + `TodayWatchPlugin`）

- 落盘结构 `TodayWatchFeedbackSnapshot{dislikedBvids, dislikedCreatorMids, dislikedKeywords, recentDislikedVideos}`（`TodayWatchFeedbackStore.kt:17-22`）。
- 启动时 `syncTodayWatchFeedbackFromStore()`（`HomeViewModel.kt:1147-1155`），init 里调一次（`:406`）。
- 消费点：`filterHomeFeedbackVideos`（`:1157-1164`）在**每次过滤链**里跑；`RecommendationFeedbackSignals`（`:694-699`）喂给插件。
- **`markTodayWatchVideoOpened`（`:960-982）** 把已看过的 bvid 记入 `todayConsumedBvids`，作为 `consumedBvids` 信号——即"今天推过的不再推"。

### 5.3 下拉刷新（无 `HomeRefreshPolicy`，实际由 `HomeRefreshUiPolicy` + `HomeUiState` 承担）

- 入口 `refresh()`（`:1196-1266`）见 §3.2；UI 侧 `HomeRefreshIndicator.kt:208`、`HomePullRefreshUiPolicy.kt:156`（下拉距离/阈值）、`HomeRefreshMotionSpec.kt:20`。
- 刷新前后状态机：`refreshKey → refreshNewItemsKey → refreshNewItemsHandledKey`（`HomeUiState.kt:173,182,185`），`markRefreshNewItemsHandled`（`HomeViewModel.kt:1283-1288`）保证一次性提示不重复（`shouldHandleRefreshNewItemsEvent` `HomeRefreshUiPolicy.kt:3-9`）。
- **`_isRefreshing` 是独立的 `MutableStateFlow`**（`:338-339`），不在 `HomeUiState` 里——避免整个 UiState 复制。UI 用它做"刷新中"分支（`HomeScreen.kt:2470` `cardAnimationEnabled && !isPageRefreshing`）。
- 【事实】**刷新失败不回滚**（成功路径才写 `refreshKey`/`refreshNewItemsCount`，`:1238-1258` 在 `fetchData` 之后；`fetchData` 失败时只改 `error`）。【推断】这意味着"刷新提示条"只在成功时出现，是对的。

### 5.4 前置插入时滚动保持（`FeedPrependScrollPolicy`）

见 §3.6。补充：**它只对"分界线之后的替换/插入"生效**，`gridIndex <= anchor.index` 时拒绝移动（`FeedPrependScrollPolicy.kt:31`），所以普通分页追加不会触发视口跳动。

---

## ⑥ v0.2.4（`6e6bdce36`）相对 `34d189620` 在 home/feed 上的净变化

范围 `34d189620..6e6bdce36` 共 8 个提交：`6cac80f45`, `2fc35a71d`, `a83598622`, `c1ff890df`, `483099f98`, `30daebd0b`, `4a79d46bb`, `6e6bdce36`（395 文件 / +20328 / −2950）。

### 6.1 逐提交：谁动了 home/feed

| sha | 主题 | 动 home/feed？ | 具体 |
|---|---|---|---|
| `6cac80f45` | Release 0.2.4（QR 授权 / 设备迁移 / 用户协议 / 折叠屏） | ✅ 是 | `HomeFeedGridPolicy.kt` **删 93 行**（铰链 arrangement 全部移除）；`HomeCategoryPage.kt` −13（改用普通 `spacedBy`）；`HomeFeedPinchZoomPolicy.kt` +102/−…（手势与动画）；`HomeScreen.kt` +46/−…（新增 `AppHingeSafeContent` 包裹）；`SettingsManager.kt` +37（导航/听视频相关，**与 feed 无关**） |
| `2fc35a71d` | Update CHANGELOG.md | ❌ 否 | 仅 `CHANGELOG.md` |
| `a83598622` | 折叠屏 feed 跨软铰链；视频 Tab 可滚动 | ⚠️ 间接 | 只改 `AppHingeSafeContent.kt`、`VideoContentSection.kt`、versionCode；**未改首页** |
| `c1ff890df` | 视频 Tab 裁剪 + 后台播放恢复 | ❌ 否 | `MiniPlayerManager`、`VideoContentSection`、`Android17Diagnostics` |
| `483099f98` | 悬浮视频卡 release 落位（新增 `miuix-navigation` 模块） | ❌ 否 | 导航模块 + `VideoCardBackCompletionPolicy` |
| `30daebd0b` | 抽 `core-data` / `playback` / `design` 模块 + 加 TV app | ✅ 结构 | `VideoRepository.kt` **净 −149 行**（大量 `requireNotNull` 化 + 详情/心跳/取流下沉）；**首页取流逻辑零改动** |
| `4a79d46bb` | 自适应播放 / 草稿恢复 / 插件工具 UI | ✅ 3 行 | `HomeViewModel.kt`：`result.data.list` → `requireNotNull(result.data).list`（`:2076-2077`）；`HomeScreen.kt` +12：**system bars 不抢权**（沉浸播放覆盖层期间不恢复系统栏，`:1432-1443`） |
| `6e6bdce36` | 插件/TV 文档 + 示例 + 脚本 | ❌ 否 | docs / examples / scripts / `docs/PLUGIN_DEVELOPMENT.md` |

### 6.2 净变化清单（按主题）

| 主题 | 变化 | 新版本行号 | 证据（+ 侧） |
|---|---|---|---|
| **折叠屏列数** | 删除 `HomeFeedBookHingeGridSpec` / `HomeFeedBookHingeArrangement` / `resolveHomeFeedHorizontalArrangement`；改用 `AppHingeSafeContent { resolveHingeSafeFeedColumns(...) }` 在**内容容器**层解决，网格只 `Arrangement.spacedBy` | `HomeScreen.kt:2031-2047`（新）、`HomeFeedGridPolicy.kt`（现 77 行，铰链部分已无） | diff `+ com.android.purebilibili.core.ui.adaptive.AppHingeSafeContent(` / `+ val effectiveGridColumns = if (appWindowAdaptiveInfo.shouldAvoidHinge) { resolveHingeSafeFeedColumns(...) }` / `+ val homeFeedHorizontalArrangement = Arrangement.spacedBy(homeFeedCardLayout.itemSpacingDp.dp)` |
| **捏合列数** | `HomeFeedPinchZoomPolicy.kt` 大改（+102）：新增 `calculateCentroid` 用于以捏合中心缩放 | `HomeFeedPinchZoomPolicy.kt:15,207` | diff 导入 `calculateCentroid`、`togetherWith`；使用点 `:207 val centroid = event.calculateCentroid(useCurrent = true)` |
| **系统栏恢复** | 沉浸覆盖层隐藏系统栏期间首页**不再抢权**；从沉浸页返回时重置 `systemBarsBehavior` 并整体 `show(systemBars())` | `HomeScreen.kt:1432-1443` | diff `+ val systemBarsVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(...) ?: true` / `+ if (!systemBarsVisible) return@SideEffect` / `+ insetsController.systemBarsBehavior = BEHAVIOR_DEFAULT` |
| **推荐取流** | **无行为变化**：`api.getRecommendParams` → `SharedContentRepository.recommendationResponse`（仅搬家） | `VideoRepository.kt:671,768`；`SharedContentRepository.kt:25-26` | diff `- val feedResp = api.getRecommendParams(signedParams)` / `+ val feedResp = SharedContentRepository.recommendationResponse(signedParams)` |
| **HomeViewModel** | **无 feed 逻辑变化**，仅 1 处 nullability | `HomeViewModel.kt:2076-2077` | diff `- val list = result.data.list ?: break` / `+ val checkedResultData = requireNotNull(result.data)` |
| **新模块** | 新建 `core-data/`（ApiClient/WbiUtils/WbiKeyManager/TokenManager/模型/仓库）、`network-core/`（策略）、`plugin-sdk/` | `core-data/build.gradle.kts`（新）、`network-core/src/main/.../policy/*` | `git diff --name-status` 中大量 `R0xx` 重命名 |
| **新增文件（feed 相关）** | `core-data/.../SharedContentRepository.kt`（新）、`ArticleHistoryReportFields.kt`（新）、`SessionRepository.kt`（新）、`data/session/BiliPaiSessionBundle.kt`（新） | — | `--name-status` 的 `A` 行 |

### 6.3 明确**没有**变的（防止过度归因）

- 首页推荐的三条取流路径、全部参数、WBI/AppSign 实现、CookieJar 策略、MERGED 合并算法：**零行为变化**（`fetchWebFeed`/`fetchMobileFeed`/`fetchMergedWebFeed`/`fetchMergedMobileFeed` 在 range diff 中只出现 `getRecommendParams` 一处调用改写）。
- `HomeFeedMergePolicy.kt`、`FeedMergeUtils.kt`、`HomeRefreshUiPolicy.kt`、`HomeCategoryPage.kt` 的**加载/去重/阈值逻辑**：range diff 未触及（`HomeCategoryPage.kt` 的 13 行全是铰链 arrangement）。
- `core/plugin/feed/*`、`feature/plugin/*`：**range 内无任何改动**（`FeedSourceCatalog`/`SubscriptionFeedPage` 等不在 `--name-status` 列表里）。
- 首页顶部 Tab 集合、`HomePagerSyncPolicy`、`HomeScrollCoordinator`、`HomeNotInterestedPolicy`：**未变**。

【待真机】折叠屏铰链改动（`AppHingeSafeContent` + `resolveHingeSafeFeedColumns`）是本版 home 侧**唯一**需要设备验证的行为变化：需要 Book 姿态 + 纵向铰链真机/Fold 模拟器确认列数与安全区。

---

## ⑦ 「bbspace 可以学但没学」候选清单

前置实测（对 `D:\111j\c\_ref\bbspace` 的只读核查）：

- bbspace **只有 App 端单源**：`FeedRepository.kt:47` `FEED_ENDPOINT = "/x/v2/feed/index"`，`FEED_PAGE_SIZE=20`（`:55`）。
- bbspace **已有 WBI 能力但只用在别处**：`infra/crypto/WbiSigner.kt`、`infra/network-web/WbiKeyProvider.kt`（服务排行/搜索/Web playurl），**未用于推荐流**。
- bbspace **没有**插件系统、没有 RSS/OPML 订阅（全仓仅无关命中）。
- bbspace **已有**的对应项（**不要列成"没学"**）：增长守卫 `MAX_FEED_ITEMS=500`（`HomeViewModel.kt:65,226`）、重复页计数 `MAX_STAGNANT_LOADS=2` + `stagnantLoads`（`:54,93,264-272`）、自动补页上限 `AUTO_FILL_MAX=2`（`:51,161-166`）、提前取页阈值 `LOAD_MORE_THRESHOLD=6`（`:31`）、去重 `distinctBy { it.actionKey }`（`:225,260`）、不感兴趣提交/撤回（`FeedDislikeRepository.kt:27`、`HomeViewModel.kt:289-341`）、广告/直播卡解析层丢弃（`FeedRepository.kt:182,207-223`）——**这一项 bbspace 比上游更细**（上游只 `goto=="av"`，bbspace 有 `adCardGotos` + `isLiveCard` + `isPugv` + `isPgc`）。

| # | 候选 | 上游行号 | 一句话价值 | 依赖插件系统？ |
|---|---|---|---|---|
| 1 | **MERGED 双源交错合并** | `VideoRepository.kt:616-644` + `HomeFeedMergePolicy.kt:23-42` | 一次刷新拿到 Web+App 两套推荐算法的并集，交错而非拼接，覆盖率明显提升，且是纯函数易测 | ❌ 不依赖（只需设置项 + WBI + HD 头） |
| 2 | **App 半边匿名取流（去 Cookie + HD 身份头）** | `MergedAppFeedCookiePolicy.kt:31-39` + `ApiClient.kt:85-96,3192-3246` | app 端点不带 SESSDATA 才会得到原版行为，否则推荐结果与风控都偏离；判定收紧到 path+mobi_app 保证不影响其它接口 | ❌ 不依赖 |
| 3 | **Web 首页推荐匿名化（"初见推荐"）** | `HomeFeedAnonymizerPolicy.kt:53-99` + `ApiClient.kt:71-82` | 一个 CookieJar 早退就能让推荐"失忆"，是最好的 A/B 与隐私体验开关；带本机命中统计 | ⚠️ 形态上是插件（`HomeFeedAnonymizerPlugin.kt:77-85`），但**机制不依赖**，可做成设置项 |
| 4 | **列表增长守卫（增量刷新旧内容截断）** | `HomeRefreshUiPolicy.kt:92-96`（阈值 200 / 保留 50）+ `HomeViewModel.kt:1530-1535` | 增量刷新是"只增不减"语义，没有它列表会随刷新次数无限膨胀、`distinctBy` 越来越贵 | ❌ 不依赖 |
| 5 | **旧内容分界线 + 一键刷新撤销** | `HomeViewModel.kt:1196-1266,1300-1336`；`HomeRefreshUiPolicy.kt:73-85`；`FeedPrependScrollPolicy.kt:16-33` | 刷新后不丢上下文：明确标出"新内容在哪"、给 5s 撤销窗口、并用锚点 key 换算 index 保证视口不跳 | ❌ 不依赖 |
| 6 | **`prepend/appendDistinctByKey`（只去重增量批，不重扫全表）** | `FeedMergeUtils.kt:6-37` | 上游每次合并只把"新批"对已有 key 集合去重（O(new)），bbspace 现在每次刷新都 `(新 + 全量).distinctBy{}.take(500)`（O(全量)）；条目越多越贵 | ❌ 不依赖 |
| 7 | **封面"超前预取"分级 + 按 cacheKey 去重** | `HomePerformancePolicy.kt:21-49` + `HomeScreen.kt:3149-3224` + `HomeCoverRequestPolicy.kt:31-48` | 预取范围由"最后可见 index + N"决定、N 受省流量夹到 0、滚动中直接不预取、同级共享 cacheKey 只解码一次；并给出 480/640/960/1280 四档 px | ❌ 不依赖 |
| 8 | **详情返回的封面预热注册表** | `HomeCoverReturnPrefetch.kt:24-100` + `AppNavigation.kt:1355` | 详情页停留期间首页 scene 被卸载、封面被逐出，返回落位会"冻结帧→占位→图到"闪变；预热同一 cacheKey 即可消除 | ❌ 不依赖 |
| 9 | **骨架的"单点 draw 相位"** | `HomeFeedSkeletonCard.kt:53-69` + `HomeFeedSkeletonVisualSpec.kt` | 脉冲值包成 `State` 并只在 `drawBehind` 读 → 骨架期间逐帧只重绘、不失效组合作用域（与 bbspace 现有 shimmer 台账直接相关） | ❌ 不依赖 |
| 10 | **Pager 按"入口身份"而非页码恢复** | `HomePagerSyncPolicy.kt:104-149` + `HomeScreen.kt:526-540` | 自定义 Tab 顺序 / 从详情返回时，用 `retainedEntry` + 四重与判定写回，避免把旧页码解释成另一个分类 | ❌ 不依赖 |
| 11 | **滚动状态的纯函数归约（顶栏三态 + 底栏隐藏 + 全局偏移）** | `HomeScrollCoordinator.kt:140-183`、`:33-39`、`:60-69` | 把三种滚动副作用拆成可单测的纯函数，并量化偏移减少逐像素状态写入；`SHOW_AT_TOP_ONLY` 语义写死在策略里 | ❌ 不依赖 |
| 12 | **推荐流来源分型过滤（`FeedKind`）** | `core/plugin/FeedPlugin.kt:29,40-53` + `HomeViewModel.kt:106,130-139` | 同一个过滤器需要"只作用于推荐、不作用于排行/搜索"时必须有来源标记；上游把来源做成显式枚举与 `toFeedKind()` 映射 | ⚠️ 接口在插件体系内，但**枚举与映射本身可脱离插件移植** |
| 13 | **过滤器/变换器的后处理管线（含异常兜底）** | `PluginManager.kt:221-252` + `applyFeedTransformPlugins :264-276` | 插件抛异常时"默认放行 + 记日志"，绝不因为一个规则崩掉整个流；并提供"本次过滤掉 N 条"的效果提示 | ✅ 依赖插件系统（但"默认放行 + 计数提示"两条可独立借鉴） |
| 14 | **今日看什么（推荐单插件）** | `RecommendationPluginApi.kt:88-92` + `HomeViewModel.kt:627-712` | 把"候选池 → 打分 → 生成队列 + 解释 + UP 排行"抽成插件接口；宿主负责供候选池（`getHomeVideos(refreshIdx+1)` `:732`）与代际守卫（`todayWatchBuildGeneration` `:620-625,654,705,734`） | ✅ 依赖插件系统 |
| 15 | **首页内嵌订阅（RSS/Atom/OPML）Tab** | `FeedSourceCatalog.kt:10-74` + `SubscriptionFeedPage.kt:257-293` + `HomeTopCategoryPolicy.kt:190-207` | 条件请求（ETag/LM 304）+ 磁盘缓存 + 保序插入（`stabilizeFeedOrder` `FeedLayoutAllocation.kt:16-26`）+ 已读/笔记，一套完整"非 B 站内容"流 | ✅ 依赖插件系统（Tab 出现由插件门控） |
| 16 | **冷启动跨模式预加载** | `VideoRepository.kt:71-104,313-361` + `MainActivity.kt:1055,1084,2324` | 启动页阶段就把首页第一页打出去并暴露 `isHomeDataReady()`，首页首帧直接命中；`shouldStartHomePreload/shouldReuseInFlightPreloadForHomeRequest` 两个纯函数防重复请求 | ❌ 不依赖 |
| 17 | **游标"无有效内容不前进"** | `HomeViewModel.kt:141-150` + `:1497-1505` | 防止服务端返回空/全无效页时把 `idx` 推掉，导致下一刷跳页；恒 +1 且 `maxOf` 保证幂等 | ❌ 不依赖 |

**Top 8（若只能做 8 件）**：① MERGED 双源 · ② App 匿名取流 · ③ 增长守卫 · ④ 旧内容分界+撤销 · ⑤ O(new) 去重合并 · ⑥ 封面超前预取 · ⑦ 返回封面预热 · ⑧ 骨架单点 draw 相位。
理由：这 8 条**全部不依赖插件系统**，且分别命中"内容量 / 风控一致性 / 内存与 CPU 增长 / 刷新体验 / 合并开销 / 图片与首帧 / 返回闪变 / 重组"八个不同维度；而 ⑭⑮⑬ 三条最有"产品感"的项都强依赖插件体系，属于更大投入。

---

## 附录 A · 命名对照（任务书点名 vs 实际）

| 任务书写的 | 实际情况 | 位置 |
|---|---|---|
| `core/plugin/feed/FeedPlugin.kt` | **不存在**；`FeedPlugin` 接口在 `core/plugin/FeedPlugin.kt`（48→53 行） | `app/src/main/java/com/android/purebilibili/core/plugin/FeedPlugin.kt:14` |
| `HomeRefreshPolicy` | **不存在**；实际是 `HomeRefreshUiPolicy.kt`（96 行）+ `HomePullRefreshUiPolicy.kt`（156 行）+ `HomeRefreshIndicator.kt` | `feature/home/HomeRefreshUiPolicy.kt` |
| `HomePreloadPolicy` | **不存在**；预加载决策在 `VideoRepository.kt:71-104`，预取范围在 `HomePerformancePolicy.kt:21-49` | — |
| `ActionRepositoryRecommendationFeedback` | **不存在**；实际是 `ActionRepository.submitRecommendationFeedback` + 3 个顶层纯函数 | `data/repository/ActionRepository.kt:40-75,115-135` |
| `FeedPrependScrollPolicy` | 存在，但在 `core/ui/components/`（33 行） | `core/ui/components/FeedPrependScrollPolicy.kt:16` |
| `FeedMergeUtils` | 存在（`core/util/`，37 行）；但 `HomeFeedMergePolicy` 是另一个东西（MERGE 模式的交错合并，`feature/home/`，55 行） | 两者不要混 |
| `FeedLayoutAllocation` / `FeedTitleResolver` / `FeedModels` | 存在，但属**订阅流**（`ParsedFeedItem` 域），不参与首页推荐 | `core/plugin/feed/` |
| `HomeScrollCoordinator` | 存在，是**纯函数集合**不是类/对象 | `feature/home/policy/HomeScrollCoordinator.kt:1-183` |
| `bili_ticket` / `login_event` / `auto_refresh_state` | **全仓不存在** | — |

## 附录 B · 待真机清单

1. 【待真机】MERGED 模式是否真的能拿到两套推荐（需登录 + 真实 access_key）；App 半边剥离 Cookie 后是否被风控（`-412`）。
2. 【待真机】折叠屏 Book 姿态下 `resolveHingeSafeFeedColumns` 的列数是否符合预期（v0.2.4 唯一实质 home 变更）。
3. 【待真机】`pendingNotInterestedRefilterBvids` 的"动画结束才重过滤"在低端机上是否出现可见重排。
4. 【待真机】`preloadHomeData` 与 `HomeViewModel.loadData` 的竞态：`consumePreloadedHomeVideos` 是**一次性**的（`VideoRepository.kt:549-554`），`StoryViewModel.kt:42` / `PortraitVideoPager.kt:628` 也会用 `getHomeVideos(0)` —— 谁先拿到预加载结果。
5. 【待真机】增量刷新 + 200/50 截断后，`FeedPrependScrollPolicy` 的锚点换算是否仍稳定（尤其加固守卫触发截断时 `addedCount` 与 `keptOldCount` 的组合，`HomeViewModel.kt:1528-1535`）。
