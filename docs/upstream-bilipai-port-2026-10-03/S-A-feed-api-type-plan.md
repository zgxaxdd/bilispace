# S-A 方案 · 首页推荐来源三档（FeedApiType）

> **状态：只出方案，本轮不改代码。**（用户 2026-10-03 决策）
> **默认值：`MOBILE` = 维持现状**（用户决策）。新增的 WEB / MERGED 只能由用户手动切换，**默认路径的行为与代码必须与当前完全一致**。
> **前置约束**：仓库当前有 **104 项未提交改动**（上一批任务书成果，HEAD `a5df3387`）。本方案落地时应**另起独立提交**，不要与那批混在一起。
> 上游参照：`D:\111j\c\_ref\BiliPai-024`（`origin/main` = `6e6bdce36`，v0.2.4）；bbspace 侧行号基于 `a5df3387`。

---

## 1. 目标与边界

**目标**：给 bbspace 的首页推荐补上上游一直有、bbspace 从未移植的**来源选择能力**——`WEB`（网页端 WBI 推荐）/ `MOBILE`（App 推荐，现状）/ `MERGED`（双源并行交错合并）。

**为什么这不是"同步 v0.2.4 的增量"**：两份独立分析（`U1-v024-delta.md`、`U2-bilipai-home-feed.md`）与 Lead 的 diff 复核三方一致确认——**v0.2.4 的 8 个提交没有改动推荐来源的数据链路**（`HomeViewModel.kt` 仅 3 行、`VideoRepository` 仅搬家）。所以本方案补的是**架构性缺口**，不是版本增量。这一点必须在 commit message 与文档里写清楚，否则下一个人会以为 v0.2.4 改了推荐。

**边界（不做）**：
- 不改默认值（默认 `MOBILE`）。
- 不引入上游插件系统、不引入 `miuix-navigation`、不做模块重构。
- 不做上游的"推荐流过滤已关注"（`is_followed`）等附带策略——除非单独立项。

---

## 2. 现状基线（bbspace）

| 环节 | 现状 | 行号 |
|---|---|---|
| 唯一来源 | `FEED_ENDPOINT = "/x/v2/feed/index"` | `core/feed/FeedRepository.kt:47` |
| 签名 | `restClient.getSigned(...)` → `AppSigner.sign(params, profile.appKey, profile.appSec)` | `FeedRepository.kt:64-68`；`infra/network-http/.../BiliRestClient.kt:78` |
| 客户端身份 | `hdFeed` 开关在 `BiliRestProfile.APP` / `HD` 间切 | `FeedRepository.kt:62-63` |
| 参数 | `buildParams(...)`：`idx/pull/flush/ps=20` + 约 35 个客户端声明 | `FeedRepository.kt:110-173` |
| 响应解析 | `data.items[]` → `FeedItem`；过滤广告卡；识别 live/ketang/pgc/dynamic/ugc | `:94-108`、`:175-249` |
| 游标 | `idx` 取**列表首项 / 末项的 `item.idx`** | `feature/home/HomeViewModel.kt:216`、`:254` |
| 合并 | `distinctBy { it.actionKey }` 全量重算；刷新截断 500 | `HomeViewModel.kt:221-227`、`:258-261` |
| 重复页守卫 | `stagnantLoads`（连续重复页停止补拉） | `HomeViewModel.kt:93`、`:265-271` |
| 负反馈 | `/x/feed/dislike` + `/cancel`（**依赖卡片的 `dislikeContext`**） | `core/feed/FeedDislikeRepository.kt:99-100` |
| 已有 WBI 能力 | `WbiSigner.sign` + `WbiKeyProvider`，**已在排行页用通** | `core/rank/RankRepository.kt:32,133` |
| 已有 Web GET 能力 | `getWeb(...)`（不做 appkey 签名，Cookie 由调用方显式给） | `BiliRestClient.kt:139` |
| 已有 Cookie/csrf | `webHeaders(referer)` / `webCsrf()` | `core/video/VideoActionRepository.kt:101-108` |

**结论：前置能力全部就绪**，不需要新增任何依赖或跨模块能力。

---

## 3. 上游参照（实现要点）

| 环节 | 上游做法 | 行号 |
|---|---|---|
| 三档枚举 | `FeedApiType { WEB(0), MOBILE(1), MERGED(2) }`，默认 **WEB** | `core/store/SettingsManager.kt:5675-5683`、`:5688` |
| 存储 | DataStore `feed_api_type` + **同步镜像** `SharedPreferences("feed_api")` | `SettingsManager.kt:5666`、`:5696-5704` |
| WEB 取流 | `WbiUtils.sign({ps, fresh_type=3, fresh_idx, feed_version=now, y_num})` → `getRecommendParams` | `data/repository/VideoRepository.kt:656-692` |
| MOBILE 取流 | 需 `access_token`，无 token 直接失败并回退 WEB | `VideoRepository.kt:695-743` |
| MERGED | `coroutineScope { async web + async mobile }` → `HomeFeedMergePolicy.mergeFeeds(web, app)` 交错合并去重 | `VideoRepository.kt:616-644`、`feature/home/HomeFeedMergePolicy.kt:23-42` |
| 分发 | `getHomeVideosInternal(idx)` 按 `feedApiType` 分派；MOBILE 失败回退 WEB | `VideoRepository.kt:584-653` |

---

## 4. 设计

### 4.1 设置：`FeedApiType`（默认 MOBILE）

- 新增枚举，放在 `core/settings`（与 `AppSettings` 同模块，避免跨模块依赖）：
  ```kotlin
  enum class FeedApiType(val value: Int) {
      WEB(0),      // x/web-interface/wbi/index/top/feed/rcmd（WBI 签名）
      MOBILE(1),   // x/v2/feed/index（appkey+sign）—— 默认，维持现状
      MERGED(2);   // 双源并行交错合并
      companion object { fun fromValue(v: Int) = entries.find { it.value == v } ?: MOBILE }
  }
  ```
  ⚠️ **`fromValue` 的兜底必须是 `MOBILE` 而不是上游的 `WEB`**——脏值/缺省都要落在"维持现状"上。
- 存储：沿用 `AppSettings` 的既有 DataStore + **SharedPreferences 镜像**模式（`core/settings/.../AppSettings.kt:131-141,215,234-245`）。
- **必须遵守的既有纪律**（`AppSettings` 的 KDoc 与 `docs/ai-loop/FINDINGS.md` P4）：镜像**首读也要回填**，否则从不改设置的用户永远没有镜像、快路径形同虚设；`buildThemeConfig` 式 `valueOf` 直抛要改成带兜底的解析（`FINDINGS` S4）。

### 4.2 数据层：`FeedRepository` 三路取流

保持现有签名不变，只在内部按来源分派：
```
fetchFeed(idx, pull, flush)
  └─ when (feedApiType.first()) {
        MOBILE -> fetchMobile(idx, pull, flush)        // = 现有实现，逐字不变
        WEB    -> fetchWeb(idx, pull)
        MERGED -> mergeFeeds(fetchWeb(...), fetchMobile(...))   // 并行
     }
```
- `fetchMobile` = **把现有 `fetchFeed` 主体原样搬进来**（零逻辑改动），保证默认路径零回归。这是本方案最重要的安全措施。
- `fetchWeb` 新增：`WbiKeyProvider.getKeys()` → `WbiSigner.sign(params, imgKey, subKey)` → `restClient.getWeb(url, query, headers)`；headers 用 `webHeaders(referer)`（带 Cookie 时有登录态推荐，无 Cookie 也能匿名取流）。
- Cookie 策略：**第一版不剥 Cookie**（上游的 `HomeFeedAnonymizerPlugin` 是"反回流"方向，与本方案目标相反，不做）。

### 4.3 WEB 响应 → `FeedItem` 映射表

上游模型：`RecommendResponse { code, data: { item: [RecommendItem] } }`（`core-data/.../ListModels.kt:299-374`）。

| web 字段 | `FeedItem` 落点 | 说明 |
|---|---|---|
| `goto`（`"av"`） | `cardGoto` / `goto` | 常量填 `"av"` |
| `title` | `title` | 直接 |
| `pic` | `cover` | 需走 `httpsImageUrl()`（web 返回 `//i0.hdslb.com/...` 协议相对地址） |
| `id`（= aid） | `param` | `id.toString()`，点击路由依赖它 |
| `bvid` / `cid` / `aid` | `target = VideoTarget.Tool(...)` | 复用现有 `VideoTargetTool.feed(...)` 的构造口径 |
| `duration` | `coverLeftText1` | 角标左侧（时长） |
| `stat.view` | `coverRightText` | 角标右侧（播放量，需用既有 `x.x万` 格式化） |
| `owner.name` | `descButton.text` + `args.upName` | UP 名 |
| `owner.mid` / `tid` / `tname` | `args.upId / args.tid / args.tname` | 与 app 协议同义 |
| `dimension.isVertical` | 复用现有竖屏判定 | |
| `cardType` | 常量（如 `"web_small_cover"`） | 仅用于 `contentType` 复用分组，取值稳定即可 |

**⚠️ 两个字段缺口（必须在设置项描述里告知用户，否则会被当成 bug）**：

1. **web item 没有 `idx`** → `FeedItem.idx` 只能填 `0` 或另行维护（见 §4.4）。这直接影响游标。
2. **web item 没有三连点 / `dislikeContext`** → **WEB 档下首页卡片的"不感兴趣"不可用**（`FeedDislikeRepository` 依赖 `item.dislikeContext`，`HomeViewModel.kt:290-293` 会直接落到"当前卡片暂不支持此操作"）。这需要在设置项里写明，或把"不感兴趣"按钮在 WEB 档下隐藏。
3. 附带：web 流**只返回 UGC 视频**，没有直播卡 / 番剧卡 / 课卡 / 动态卡 → WEB 档的首页内容构成会明显不同（更"纯"）。

### 4.4 游标方案（**本方案最关键的设计点**）

现状游标 = `item.idx`（app 协议下发）。web item 无该字段，**两套游标语义不同，不能混用**。

方案：把游标从"列表项携带"改为**来源自持**：
- 在 `HomeViewModel` 引入一个 `feedCursor`，按来源分别维护：
  - `MOBILE`：保持现状（`首项/末项.idx`），**不动**；
  - `WEB`：用单调递增的 `pageIndex`（0,1,2…），映射到 web 的 `fresh_idx` / `y_num`，并按上游做法带 `feed_version = 当前时间戳`；
  - `MERGED`：**两个游标各自独立推进**（web 的 `pageIndex` 与 app 的 `idx` 互不影响）。
- 切换来源时游标重置并触发一次刷新（列表内容本来就会变）。
- `pull`/`flush` 语义：`flush` 只对 app 协议有意义 → WEB 档忽略；`pull=true` 只用于"刷新"，WEB 档对应 `fresh_type` 的取值差异（上游刷新用 `=4`、首屏用 `=3`，见 `VideoRepository.kt:617-620` 的注释——落地前需按上游两处参数各读一遍再定）。

**风险**：这是全方案唯一"容易静默错"的地方（错的表现是翻页重复/跳页）。必须有纯 JVM 单测覆盖游标推进（见 §6）。

### 4.5 MERGED 合并

- 并行：`coroutineScope { async(web), async(mobile) }`，两边都失败才失败。
- 合并：照上游 `HomeFeedMergePolicy.mergeFeeds` 的**交错**语义（而非"web 全部在前"），按 bvid/aid 去重。
- 单边失败：返回存活的那一边（不要整体失败）。
- 与现有 `distinctBy(actionKey)` 的关系：MERGED 的合并发生在**repository 层**，`HomeViewModel` 的既有去重仍然生效（双层，冗余但安全）。

### 4.6 设置 UI

- 落点：`feature/settings/.../feed/FeedSettingsScreen.kt`（已存在该页）。
- 三项单选 + 每项一行说明（WEB = 网页端推荐算法；MOBILE = 手机端算法，需登录；MERGED = 两者合并，失败自动回退）。
- **描述里必须写明 WEB 档的两条限制**（无不感兴趣入口、内容构成偏纯视频），否则用户会报 bug。

### 4.7 降级与回退

| 情形 | 行为 |
|---|---|
| 默认（未设置） | `MOBILE`，与现状逐字一致 |
| WEB 且 WBI key 拿不到 | 回退 `MOBILE` 一次，并记日志（不静默） |
| MERGED 单边失败 | 返回存活边 |
| MERGED 两边失败 | 返回失败，UI 走既有错误态 |
| 未登录 + WEB | 允许（匿名 web 推荐），但个性化弱——**待真机确认是否需要提示** |
| 未登录 + MOBILE | 现状：`access_token` 为空时 app 协议仍可尝试（bbspace 现在是 `getSigned` + 空 token），**保持现状不额外限制** |

---

## 5. 改动清单

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `core/settings/.../FeedApiType.kt` | 枚举 + `fromValue` 兜底 MOBILE |
| 改 | `core/settings/.../AppSettings.kt` | `feedApiType` DataStore 流 + 镜像读写；**首读回填** |
| 改 | `core/feed/.../FeedRepository.kt` | `fetchFeed` 改为分派；现有主体原样搬为 `fetchMobile`；新增 `fetchWeb` / 合并 |
| 新增 | `core/feed/.../WebFeedMapper.kt` | `RecommendItem → FeedItem` 纯函数（**可纯 JVM 单测**，不依赖 Android） |
| 新增 | `core/feed/.../FeedMergePolicy.kt` | 交错合并纯函数（可纯 JVM 单测） |
| 改 | `feature/home/.../HomeViewModel.kt` | 游标按来源分持；`fetchFeed` 调用点带上来源 |
| 改 | `feature/settings/.../feed/FeedSettingsScreen.kt` | 三档单选 + 限制说明 |
| 新增 | `core/feed/src/test/.../WebFeedMapperTest.kt` 等 | 见 §6 |

**预计规模**：新增约 250–400 行 + 测试约 150–250 行；不新增依赖、不改 Manifest、不动数据库。

---

## 6. 静态验收（无真机可判死）

| # | 断言 | 说明 |
|---|---|---|
| V1 | `FeedApiType.fromValue(任意脏值) == MOBILE` | 含 `-1`、`99`、缺省；纯 JVM |
| V2 | **默认来源 = MOBILE**：不设置任何值时 `fetchFeed` 走 `fetchMobile` 分支 | 可用 fake client 计数调用 |
| V3 | `fetchMobile` 与改动前的 `fetchFeed` **参数与 URL 逐字一致** | 结构断言 + 快照式单测（把 buildParams 结果与一份固定期望表比对） |
| V4 | `WebFeedMapper` 对给定 JSON 的映射逐字段正确（含 `pic` → `https:` 前缀、`id` → `param`、`duration`/`view` 角标落位） | 纯 JVM；含"缺字段不崩"用例 |
| V5 | **web item 无 `dislikeContext`** → `FeedItem.dislikeContext == null`，且 UI 走"暂不支持"分支 | 锁住 §4.3 的限制，防后续误判为 bug |
| V6 | 游标：WEB 档连续翻 3 页，`pageIndex` 单调 +1 且不串页 | 纯 JVM |
| V7 | MERGED：单边失败返回另一边的全部条目；两边成功时结果含两边的 bvid 且无重复 | 纯 JVM |
| V8 | WBI key 取不到时 WEB 档回退 MOBILE 且**有日志**（不静默降级） | 结构断言 + fake |
| V9 | 设置项描述文案**包含**两条限制说明 | 结构断言（防止后人删掉说明） |
| V10 | 现有 `HomeViewModel` 单测/守卫全绿；`FeedRepository` 的 `BASE_URL_APP` 拼接口径未变 | 回归 |

**破坏性验证**（每条守卫按 W5/W22 口径）：改坏 → FAILED → 还原后 sha256 一致；单模块 `--rerun` 确认真跑过。

---

## 7. 风险与回滚

| 风险 | 等级 | 处置 |
|---|---|---|
| 默认路径被无意改动 → 全体用户推荐行为变化 | **高** | `fetchMobile` 主体**逐字照搬**；V2/V3 两条断言死守；默认值 MOBILE |
| 游标语义混用 → 翻页重复/跳页 | **高** | §4.4 的来源自持游标 + V6 单测；WEB 档上线前先只用 `WEB` 单项验证 |
| WEB 档"不感兴趣"失效被当成 bug | 中 | §4.3 明示 + V5/V9 断言 + 设置项文案 |
| WBI 密钥获取失败（nav 接口异常） | 中 | 回退 MOBILE + 日志 |
| web 协议字段与上游模型不符（上游模型可能已滞后于服务端） | 中 | 映射器按"缺字段不崩"写；**待抓包确认**（§8） |
| 104 项未提交改动被混入 | 中 | 独立提交、独立分支；落地前先确认那批已提交或已 stash |
| 与上游 MERGED 语义有差异（交错顺序） | 低 | 照抄 `HomeFeedMergePolicy.mergeFeeds`，并写明来源 |

**回滚**：把 `feedApiType` 的读取点改回常量 `MOBILE` 即可（或直接 revert 单个提交）；数据层新增代码不被默认路径执行，属于"死代码可留"。

---

## 8. 待真机 / 待抓包

| 编号 | 待验证 | 取证 |
|---|---|---|
| F1 | web rcmd 的**实际响应字段**是否与上游模型一致（上游模型可能滞后） | 抓包 `x/web-interface/wbi/index/top/feed/rcmd`，比对 §4.3 映射表 |
| F2 | WEB 档在**未登录**下的返回质量（是否为空/是否限流） | 同一网络下匿名 vs 登录对比条数与 `code` |
| F3 | WEB 档的 `fresh_type` / `fresh_idx` / `y_num` 的正确取值组合 | 抓包对照上游两处（`VideoRepository.kt:667` 与 `:617-620` 注释） |
| F4 | MERGED 的交错观感是否符合预期（是否会出现"两边风格打架"） | 人工走查 + 与上游同设置的观感对比 |
| F5 | 切换来源后首页刷新是否出现重复卡/空白 | 连切 5 次观察 |
| F6 | WEB 档是否触发风控（相对 App 档的差异） | 观察 `code != 0` 与 `-412` 类返回码 |
| F7 | 三档各自的滚动流畅度是否与基线相当 | `dumpsys gfxinfo framestats`（沿用 `docs/performance-baseline.md` 口径） |

---

## 9. 分期建议

| 期 | 内容 | 可静态验收 | 依赖 |
|---|---|---|---|
| **P1** | `FeedApiType` 设置 + `fetchMobile` 抽取（**行为零变化**）+ V1/V2/V3/V10 | ✅ 全部 | 无 |
| **P2** | `WebFeedMapper` + `fetchWeb` + 游标自持 + V4/V5/V6/V8 | ✅ 全部 | P1 |
| **P3** | MERGED 合并 + V7 | ✅ 全部 | P2 |
| **P4** | 设置 UI + V9 + 文案 | ✅ 全部 | P1 |
| **真机** | F1~F7 | ❌ | P1–P4 |

**建议**：P1 先单独落地（它本身零行为变化，却是后面全部的地基，且能立刻验证"没有回归"）；F1 抓包应与 P2 并行，因为映射表的正确性完全取决于真实响应。

---

*方案 v1 · 2026-10-03 · 依据 `U1-v024-delta.md`、`U2-bilipai-home-feed.md` 与 Lead 的上游 diff 复核。未修改任何代码。*
