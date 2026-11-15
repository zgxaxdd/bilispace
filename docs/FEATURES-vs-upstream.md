# bilispace 相对上游 BBSpace 的功能差异

> 本文是「基于上游源码持续二次开发」的差异台账，用于回答一个具体问题：
> **相比原版 `naaammme/bbspace`，这个 fork 到底多了什么、改好了什么。**
>
> README 里现有的 v1.8.04 更新日志只覆盖最初一版（2026-09-06）的工作，
> 不能代表当前 HEAD 的实际差异；本文以仓库实际情况为准，逐条可指向文件或提交哈希。

---

## 0. 口径速览

| 项 | 值 | 证据 |
|---|---|---|
| 上游基线 | `upstream/main` = `93eb904b`（2026-08-10，`tweaks`） | `git log -1 upstream/main` |
| 合并基点 | `93eb904b`（上游 history 是当前分支的祖先，无分叉合并） | `git merge-base upstream/main HEAD` |
| 当前分支 / HEAD | `build/sx-swipe-experience` / `87fefe6d` | `git rev-parse --abbrev-ref HEAD`、`git log -1` |
| 领先提交数 | **267**（其中 1 个 merge 提交） | `git rev-list --count upstream/main..HEAD` |
| HEAD vs 上游 | **742 files changed, +94299 / −4998** | `git diff --stat upstream/main HEAD` |
| 工作区 vs 上游 | **741 files changed, +107008 / −6332** | `git diff --stat upstream/main`（不含未跟踪文件） |
| 工作区未提交 | 169 个已修改 + 119 个未跟踪 = 288 条（**写作时快照**；见 §4.2 第 6 点） | `git status --porcelain` |
| 上游已有源码规模 | 349 个 `.kt`、2 个示例测试（`ExampleUnitTest` + `ExampleInstrumentedTest`） | `git ls-tree -r --name-only upstream/main` |
| 本版源码规模 | 776 个 `.kt`（src，排除 `build/`）、200 个单测文件、6 个仪器测试 | 目录实测 |

新增的 Gradle 模块（`settings.gradle.kts`）：

```
+ :core:rank          + :core:sponsorblock      + :core:swipe
+ :feature:rank       + :ci-lint
- :core:navigation     （上游留下的空模块，只有 build.gradle.kts、无源码）
```

---

## 1. 相对原版新增的功能

### 1.1 播放与刷视频（竖屏连播）

原版只有「视频详情页 + 应用内小窗」，没有任何竖屏上下滑连播能力。
本版从零建了一个独立的会话域 `:core:swipe`（27 个 main 源文件 + 23 个单测文件），
里面全是**无 Compose / 无 Android 依赖的纯逻辑**（判据收成 `object` 或顶层纯函数，可纯 JVM 单测），UI 落在 `feature/video/player/`。

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 播放与刷视频 | 竖屏全屏上下滑刷视频会话 | 页 0 = 进入时的视频锚点，页 1..n = 推荐接力流；全屏沉浸、无顶栏 | `feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt`、`core/swipe/src/main/java/com/naaammme/bbspace/core/swipe/session/SwipeSessionController.kt`（提交 `a68900b7`） |
| 播放与刷视频 | 会话状态机（单一真值源 + 进程重建恢复） | `Idle/Bootstrapping/Ready/Failed` + `restore()` 结构上不可能触发网络；`exit()` 清空一切会话内状态 | `core/swipe/.../session/SwipeSessionController.kt`、`SwipeSessionState.kt`、`SwipeSessionSnapshot.kt`（提交 `5ce3cb5c`） |
| 播放与刷视频 | 多源编排（按 cost/diversity 排序、蜂窝剔除） | 便宜源先问、多样的先问；蜂窝下剔除 `allowedOnCellular=false` 的源 | `core/swipe/.../source/SwipeSourceRegistry.kt`、`feature/video/.../swipesource/SwipeSources.kt`（提交 `85e9a88f`） |
| 播放与刷视频 | 相邻页预取 + 补货凑满 | 跨过 25% 偏移即预取目标页；停稳后按网络预取 1~3 页；一页不够则最多拉 3 页凑够 8 条 | `core/swipe/.../SwipePrefetchPolicy.kt`、`SwipeRefillPolicy.kt`（提交 `a68900b7`、`ef6e372a`） |
| 播放与刷视频 | 跨会话已推去重 | 进程级记忆刷到过的视频，下次进会话不再重复推同一支 | `core/swipe/.../queue/SwipeSeenMemory.kt`、`feature/video/.../swipesource/SwipeSeenStore.kt`（提交 `85e9a88f`） |
| 播放与刷视频 | 相似度去重 + 确定性洗牌 + 相邻不同 UP | 同一支视频换标题后缀反复出现会被判重；新批次洗牌并避免连续同 UP | `core/swipe/.../SwipeSimilarity.kt`、`SwipeArrangement.kt`（提交 `3de1214f`） |
| 播放与刷视频 | 队列条数上限 | 队列此前无界，长挂机会内存只涨不落；现按上限裁剪 | `core/swipe/.../SwipeQueueBoundPolicy.kt` |
| 播放与刷视频 | 翻页跟手与出页动效（缩放/淡出 + 越界阻尼） | `VerticalPager` 本身 1:1 跟手，这里补"带走感"与越界回弹 | `core/swipe/.../SwipePageTransformPolicy.kt`（提交 `9a69d6d9`），接线在 `PortraitSwipePager.kt:675` |
| 播放与刷视频 | 失败分类 + 重试策略 + 遮罩文案 | 网络不可用/取流失败/解码失败分别给文案与"该不该自动重试" | `core/swipe/.../SwipeErrorPolicy.kt`（提交 `506545d6`） |
| 播放与刷视频 | 加载三态统一（骨架/粒子/错误） | 把 veil 的 `visible`/`failed` 两个布尔收成一个纯判据 | `core/swipe/.../SwipeLoadPhasePolicy.kt` |
| 播放与刷视频 | 封面→画面交接时序（暗遮罩与封面分段淡出） | 修"淡到一半像闪黑"：暗遮罩先走、封面后走 | `core/swipe/.../SwipeHandoffTiming.kt`（提交 `56c81ea9`） |
| 播放与刷视频 | 等首帧遮罩最短展示策略（默认关） | "快网不闪、慢网不空"，默认不改行为 | `core/swipe/.../SwipeWaitPolicy.kt`（提交 `1a4e1c10`） |
| 播放与刷视频 | 播完自动下一个开关（与单曲循环互斥） | 对照 BiliPai `playbackCompletionBehavior` 的最小版 | `core/swipe/.../SwipeAutoAdvancePolicy.kt`（提交 `5072cab3`） |
| 播放与刷视频 | 双击点赞 + 粒子爱心（种子可复现） | 主心放大上浮之外散出小子心，位置/角度/延迟由 token 决定 | `core/swipe/.../SwipeHeartBurstPolicy.kt`、接线 `feature/video/.../VideoPlayerPane.kt:2268-2330`（提交 `ee1b5c2f`） |
| 播放与刷视频 | 单行文本跑马灯（只用于 @UP 一行） | 两行标题不做跑马灯，避免与"点标题展开"打架 | `core/swipe/.../SwipeMarqueePolicy.kt`、`VideoPlayerPane.kt:2032,2162-2189` |
| 播放与刷视频 | 会话内防烧屏（静置变暗 + 像素漂移） | 常驻控件在 OLED 上长时间静止会烧屏；提供开关 | `core/swipe/.../SwipeChromeBurnInPolicy.kt`、`feature/video/.../SwipeChromeBurnIn.kt`（提交 `fefb22b2`） |
| 播放与刷视频 | 控制层归属三态（会话层 / 播放器层 / 全让位） | 修"点一下屏幕出现两个 ⋮、两套顶栏"；竖屏两层互斥，横屏 = 清屏 | `core/swipe/.../SwipeChromeOwner.kt`（`SwipeChromePolicy`）、测试 `core/swipe/src/test/.../SwipeChromePolicyTest.kt`（第 35 轮 P0-1） |
| 播放与刷视频 | 互动栏（头像/点赞/评论/收藏/更多 + 长按三连） | 右侧竖排；头像可进 UP 空间；长按点赞触发三连 | `feature/video/.../VideoPlayerPane.kt:1478`（`SwipeSessionActionRail`），接线 `PortraitSwipePager.kt:830-860`（提交 `648d42c4`） |
| 播放与刷视频 | "更多"面板 + 会话内「不感兴趣」闭环 | 不感兴趣（可撤回，退队/补货/前进）/ 弹幕开关 / 复制链接 / 系统分享 / 拉黑该 UP | `feature/video/.../SwipeMoreSheet.kt`、`PortraitSwipePager.kt:898-1000`、`core/swipe/.../SwipeDislikeRemovalPolicy.kt`（提交 `7de784a2`） |
| 播放与刷视频 | 会话内评论悬浮卡片 | 68% 高、半透明、从下方 1/3 滑入；明确要求"不要占满屏弹层" | `feature/video/.../SwipeSessionChrome.kt:132-238`（`SwipeCommentCard`，`COMMENT_CARD_HEIGHT_FRACTION=0.68`） |
| 播放与刷视频 | 会话内底部信息层（@名/标题/话题/合集） | 常驻标题 + UP 行，点标题可展开全文 | `feature/video/.../SwipeSessionChrome.kt:76-130`（`SwipeSessionBottomZone`，提交 `e7ddcd48`、`dc5164fd`） |
| 播放与刷视频 | 横屏入口 / 横屏清屏 / 横屏侧滑回竖屏 | 竖屏时给悬浮「横屏」钮；进横屏整屏清空控件；侧滑返回优先"切回竖屏"而非退出会话 | `feature/video/.../SwipeSessionLeaves.kt`、`core/swipe/.../SwipeBackPolicy.kt`、`PortraitSwipePager.kt:738-760`（提交 `eb6408de`、`eef0f8b7`） |
| 播放与刷视频 | 联合创作信息渲染（竖屏全屏） | 成员 >1 时出「等 N 人联合创作」chip 与成员弹层 | `feature/video/.../SwipeCoCreation.kt`（PT-08，语义移植 BP `PortraitFullscreenOverlay.kt`） |
| 播放与刷视频 | 手势轴仲裁宽限（斜滑不再被吞） | 横向要"明显主导"才锁轴，否则给一段意图宽限；未决期间不消费事件 | `feature/video/.../PlayerDragAxisPolicy.kt`（提交 `56c81ea9`） |
| 播放与刷视频 | 会话内 QoE 观测 + 刷视频诊断页 | 内存环形缓冲：首帧/翻页/补货计数，不联网、无第三方 SDK、不含标题/UP/账号 | `core/swipe/.../qoe/SwipeQoeHub.kt`、`SwipeQoeMetrics.kt`、`feature/settings/.../qoe/SwipeDiagnosticsScreen.kt`（提交 `fd968f96`） |
| 播放与刷视频 | 两个刷视频入口 | ① 首页顶栏 38dp 圆形「刷视频」芯片（可无缝展开）；② 详情页简介区**边缘下滑**（不新增控件） | `feature/home/.../HomeScreen.kt:93-100,280-329`、`feature/video/.../PlaybackPageMode.kt:110-123`（提交 `17f0f55c`、`f935d672`） |

### 1.2 播放器内核

原版播放链路是「取 playurl → ExoPlayer 播」，没有字节缓存、没有线路健康度、没有卡死检测。

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 播放器内核 | 播放字节缓存（SimpleCache，128MiB） | 播过的字节跨会话复用；回看/拖动回退/重进详情不再重下 | `infra/player/src/main/java/com/naaammme/bbspace/infra/player/PlaybackMediaCache.kt`（`MAX_BYTES = 128MiB`，提交 `0a7b7b4e`） |
| 播放器内核 | 缓存占用与清理入口 | 128MiB 磁盘占用不能是静默的：隐私页给占用与清理，并提示"播放中拒绝清理" | `feature/settings/.../privacy/PrivacySettingsScreen.kt`（`playbackCacheBytes` / `playbackCacheToast`）、`core/common/.../cache/PlaybackCacheControl.kt` |
| 播放器内核 | 起播头部预热（1.5MiB 视频 + 256KiB 音频，仅 Wi-Fi） | 与"蜂窝不预热详情"同一道闸门 | `infra/player/.../PlaybackHeadPrewarmPolicy.kt`、`core/common/.../cache/PlaybackHeadPrewarmer.kt`（提交 `cacd99f9`） |
| 播放器内核 | playurl 进程内 LRU（正常开播与预取共用） | 键含影响请求内容的设置摘要，TTL 90s（比上游 10min 保守） | `core/playback/src/main/java/com/naaammme/bbspace/core/playback/repository/PlayUrlCache.kt`、`PlayUrlCacheMetrics.kt` |
| 播放器内核 | 重试前失效 playurl 缓存 | 否则重试必然拿回同一份失效 URL | `core/playback/.../repository/PlayUrlCache.kt`、`feature/video/src/test/.../RetryInvalidationGuardTest.kt`（提交 `5fea067c`） |
| 播放器内核 | CDN 线路健康度（可衰减评分）+ 智能选线 | 记录"这条线在本机实际表现"，分数高的优先；纯函数、注入时钟、可单测 | `core/model/.../player/CdnHealthPolicy.kt`、`CdnSmartSelectionPolicy.kt`、`core/playback/.../CdnHealthStore.kt`、`CdnHealthPersistence.kt` |
| 播放器内核 | CDN 属地优选 / 自定义改写规则（实验，默认关） | 按归属地改写候选 host，或按 `pattern => replacement` 自定义；**只重排不改预算** | `infra/player/.../cdn/CdnRegionPlugin.kt`、`app/src/main/res/raw/cdn_region_catalog.json`、设置 `feature/settings/.../audioVideo/CdnRegionSettingsSection.kt` |
| 播放器内核 | CDN 并行分片下载 | 一次范围请求切 2~4 片并发竞速，按缓冲/码率自适应 | `infra/player/.../cdn/CdnTransferPolicy.kt`（`CDN_MAX_CONNECTIONS=4`）、`CdnPlaybackDataSource.kt` |
| 播放器内核 | DASH 分段预取（缓冲 ≥15s 且仅 Wi-Fi） | 缓冲变薄立即停止；seek/换源/收尾可取消 | `infra/player/.../cdn/CdnDashSegmentPrefetcher.kt`、`CdnDashPrefetchPlan.kt` |
| 播放器内核 | CDN 故障切换数据源 | 线路失败按候选序切换 | `infra/player/.../CdnFailoverDataSource.kt`、`CdnLineOutcomeListener.kt`、`CdnServingHostListener.kt` |
| 播放器内核 | 播放中途卡死的阶梯恢复 | 修"长视频播一段卡在 ~5s 缓冲再也不动"（无 error 回调的场景） | `infra/player/src/main/java/com/naaammme/bbspace/infra/player/StallRecoveryPolicy.kt`、`PlaybackWatchdogPolicy.kt` |
| 播放器内核 | 起播超时判定 | 超时后走恢复而不是无限等 | `infra/player/.../FirstFrameTimeoutPolicy.kt`、`NetworkReturnRetryPolicy.kt`、`PlayerErrorRecoveryPolicy.kt` |
| 播放器内核 | 首帧耗时解剖埋点 | 拆到 sourceReady / decoderInit / surfaceReady / firstFrame 分段输出 | `infra/player/.../FirstFrameTimeline.kt`（提交 `3a610cc4`） |
| 播放器内核 | 稳态观测埋点（丢帧/解码器重建/字节缓存命中率） | 一条可解析日志给性能轮用 | `infra/player/.../DecoderReuseReport.kt`、`PlaybackByteCacheMetrics.kt`（提交 `5b882c45`） |
| 播放器内核 | 单文件 DASH 的 MP4 盒解析 | 三形态盒头、任何输入不抛异常 | `infra/player/.../SingleFileDashIndexParser.kt`（提交 `e0ef3242`，批次 B1） |
| 播放器内核 | seek 按**意图**分档 | 用户拖拽走关键帧吸附（快），服务端跳段/自动片尾/续播保持精确（不污染历史） | `infra/player/.../SeekParametersPolicy.kt`、`core/playback/.../VideoPlaybackController.kt`、`feature/video/src/test/.../SeekIntentSeparationGuardTest.kt`（PB-02，`418f7131`） |
| 播放器内核 | 播放引擎所有权仲裁 | 只有当前占用者能 release/stopForReuse，修"一侧收尾拆掉另一侧正在播的 ExoPlayer" | `core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlayerEngineArbiter.kt`（ST-07，提交 `7edca3ea`） |
| 播放器内核 | 单视频循环播放 | 循环模式用枚举（`Off/One`），便于后续扩列表循环 | `core/model/.../player/PlayerSettingsModels.kt`（`PlayerRepeatMode`）、`core/playback/.../StreamPlaybackSessionImpl.kt:185-188`（提交 `6291dc6a`） |
| 播放器内核 | 播放进度周期落盘 | 修"看一半退出后续播丢失"（真机实测缺陷 L4-101） | `core/playback/.../PlaybackHeartbeatPolicy.kt`（30s 间隔）、`PlaybackHistoryReportPolicy.kt`（提交 `01e452e3`、`b82f9d42`） |
| 播放器内核 | 播放器错误分诊与恢复 | 错误类型 → 是否可恢复、恢复动作、重试退避 | `infra/player/.../PlayerErrorPolicy.kt`、`PlayerErrorRecoveryPolicy.kt` |

### 1.3 界面与动效

原版是标准 Material 3 观感；本版做了整套视觉体系重写（`:core:designsystem` 从 28 个源文件扩到 99 个 main + 53 个 test）。

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 界面与动效 | 双界面风格（Material / iOS） | `UiStyle` 档位；iOS 下用固定语义色板 + tint 覆盖 primary，动态取色/调色盘置灰 | `core/designsystem/.../theme/ThemeConfig.kt`（`UiStyle`）、`theme/iOSColor.kt`、`theme/iOSTypography.kt`、`Theme.kt:163-176` |
| 界面与动效 | iOS 控件语汇 | 动作面板 / 警告框 / sheet 抓手 / 返回按钮，7 处弹层统一收口 | `core/designsystem/.../component/IOSControls.kt`、`IOSActionSheet.kt`、`IOSAlertDialog.kt`、`BiliSheetGrabber.kt`（提交 `1ea4f723`、`ea82c30e`） |
| 界面与动效 | Miuix 控件渲染层 | 开关/分段控件/滑块/列表项改由 miuix 渲染（轨道形变 + 滑块弹簧） | `core/designsystem/.../component/miuix/`（`AppMiuixSwitch.kt` 等 8 个文件）、`IOSControls.kt` KDoc（2026-10-18 W2 批次） |
| 界面与动效 | 液态玻璃三档材质 + 强度倍率 | `OFF` 半透明细描边 / `FROSTED` 磨砂（默认）/ `LIQUID`（miuix 背板 blur + AGSL 边缘折射 + Fresnel 高光 + 饱和度补偿） | `core/designsystem/.../glass/GlassConfig.kt`、`GlassBudget.kt`、`LiquidBackdrop.kt`、`glass/liquid/`（提交 `3143614d`、`03bc3d8b`） |
| 界面与动效 | 玻璃预算与档位降级 | 按滚动/转场/省电/低端机降档，避免全屏离屏重录 | `core/designsystem/.../glass/GlassBudget.kt`、`GlassGuard.kt`、`ChromeBackdropSource.kt` + 测试 `core/perf/Glass*Test.kt` |
| 界面与动效 | 全局弹性按压（拖动形变） | 按下即鼓、拖拽跟随手指；关掉开关后**仍保留按压反馈**（形变两分量独立门控） | `core/designsystem/.../motion/AppElasticPress.kt`（Kyant Apache-2.0 移植）、`DampedDragAnimation.kt`；main 源码里 **231 处** `.appElasticPress()` 调用点（全仓含测试 329 处） |
| 界面与动效 | 粒子消散动效（ThanosEffect） | GPU 粒子 + 片元着色器；GLES 3.0 以下自动降级；用于下载列表/搜索历史/拉黑名单/胶囊 | `core/designsystem/.../motion/DissolvableSurface.kt`、`motion/gl/ThanosEffectView.kt`、`ParticleRenderer.kt`、`ThanosParticlePolicy.kt`、`res/raw/thanos_*.glsl` |
| 界面与动效 | 顶部非线性渐进消隐 | Telegram/Nagram 的 5 阶停靠点算法，防马赫带；设置项可关 | `core/designsystem/.../component/ProgressiveFade.kt`、`theme/TopProgressiveFade` 设置（`top_progressive_fade`） |
| 界面与动效 | 骨架屏微光 | 加载态从静止色块改为微光扫过（补齐覆盖 + provider 幂等） | `core/designsystem/.../component/Skeleton.kt`、测试 `core/perf/SkeletonShimmerStructureTest.kt`（提交 `1b6b28cd`、`1d7eb6dd`） |
| 界面与动效 | 卡片封面自适应取色（含系统壁纸色板 Y 轴插值） | 封面主色 → 卡片背景/文字/环境光；取色不再复制整张位图 | `core/designsystem/.../color/VideoCardCoverTint.kt`、`VideoCardCoverColorStore.kt`、`VideoCardAdaptiveTintPolicy.kt`（提交 `a000dffd`、`31f802cc`、`2e279836`） |
| 界面与动效 | 卡片/封面/播放器的统一大圆角语汇 | 卡片 20dp、播放器同值（结构性保证不跳变），Dock 32dp、弹层顶 28dp | `core/designsystem/.../theme/Radius.kt`（`BiliRadius.card / player / dock / dockChip`）、`BiliSheetShape.kt` |
| 界面与动效 | 视频详情页 Activity 化 + ColorOS 无缝动画 | 卡片封面 ⇄ 全屏 Activity 系统级过渡，支持跟手/阻断/回弹；非 ColorOS 16.1+ 降级应用内展开 | `app/src/main/java/com/naaammme/bbspace/video/VideoDetailActivity.kt`、`core/designsystem/.../seamless/OplusViewSeamlessCompat.kt`、`CoverExpand.kt`、`SeamlessVideoCardOpener.kt`、`app/src/main/res/anim/window_*.xml` |
| 界面与动效 | 悬浮播放胶囊 + 联动底栏三态 | 收起态从"应用内小窗"改成胶囊：滚动态与底栏合并进同一行，导航收敛为「首页图标 + 横条 + 搜索」 | `app/src/main/java/com/naaammme/bbspace/playback/capsule/CapsuleIndicator.kt`、`LinkedBottomDock.kt`、`LinkedDockPolicy.kt`、`CapsuleIndicatorState.kt`（提交 `183bf503`、`4f2cbb89`、`74a7ded9`） |
| 界面与动效 | 底栏滑动指示器（液态位移参数化） | spring 参数集中一处；速度越快越扁/越拉长（液态感） | `core/designsystem/.../motion/LiquidIndicatorMotion.kt`、`LiquidIndicatorVelocity.kt`、`MatchedLiquidIndicatorGeometry.kt` |
| 界面与动效 | 首页顶栏滚动状态机（偏移累加 + fling 吸附） | 修"顶栏卡住不回弹"；顶栏高度 144dp → 约 95dp | `feature/home/src/main/java/com/naaammme/bbspace/feature/home/HomeTopBarScroll.kt`、`HomeScreen.kt:144-170` |
| 界面与动效 | 动效 token 一律走设置（含 reduceMotion / 系统减弱动效） | 动画时长/曲线由 `LocalAnimations` 与 `SystemReduceMotion` 下发；三档设备动效分档 | `core/designsystem/.../theme/SystemReduceMotion.kt`、`motion/MotionTier.kt`（`DeviceMotionTier`）、`theme/Animation.kt`；守卫 `core/designsystem/src/test/.../AnimationTokenRatchetTest.kt` |
| 界面与动效 | 图片预览双指/双击缩放（锚点钉手指、越界夹取） | 数值决策全在纯函数里，手势层只做坐标换算 | `core/designsystem/.../component/PreviewZoomPolicy.kt`、测试 `PreviewZoomPolicyTest.kt` |
| 界面与动效 | 播放页控件显隐具名策略 | 进全屏即隐 / 点击或拖动唤出 / 2s 无操作再隐；弹层在场不隐 | `feature/video/.../player/PlayerChromePolicy.kt`（SX-05，`2817b5d0`） |
| 界面与动效 | 玻璃背板重录节流（30Hz） | 控制条可见时不再每帧把视频层重录进纹理；按绝对时间而非帧数 | `feature/video/.../player/VideoGlassTickPolicy.kt`（`TARGET_INTERVAL_NS = 33_333_333`） |
| 界面与动效 | 加载指示器（单枚彗尾弧） | 做减法后的克制描边风格 | `core/designsystem/.../component/BiliParticleLoader.kt`（提交 `bd5e0a34`、`3598b882`） |
| 界面与动效 | 列表增长守卫（只有列表真变长才允许下次预取） | 防"上拉越拉越多、endless 请求" | `core/designsystem/.../component/LoadMoreGrowthGuard.kt` |

### 1.4 内容与推荐

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 内容与推荐 | 排行榜页（首页新增「排行」Tab） | 首页 Tab 从 `FM/推荐/直播/专栏` 变 `FM/推荐/直播/**排行**/专栏` | `feature/rank/src/main/java/com/naaammme/bbspace/feature/rank/RankScreen.kt`、`RankViewModel.kt`、`core/rank/.../RankRepository.kt`、`HomeScreen.kt:78-79,264`（提交 `475d288e`） |
| 内容与推荐 | 排行内容类型全档 | 热门 / 14 个 UGC 分区（动画/音乐/舞蹈/游戏/知识/科技/运动/汽车/美食/动物/鬼畜/时尚/娱乐/影视）/ 6 个 PGC（番剧/国创/纪录片/电影/剧集/综艺）/ 每周必看 / 入站必刷 | `core/model/.../rank/RankModels.kt`（`RankType`、`RankKind`）、`RankRepository.kt` 的 7 个 Web 端点 |
| 内容与推荐 | 排行页与推荐页同源卡面 | 封面取色宿主提到 designsystem 共用，排行卡复用推荐卡视觉 | `feature/rank/.../RankScreen.kt`（import `rememberVideoCardCoverTint` / `BiliCardSurface` / `AdaptiveMediaGrid`）、提交 `b4abd2d9` |
| 内容与推荐 | 首页推荐来源三档（WEB / MOBILE / 双源合并） | 双源按索引交错合并去重（app 在前）、单边失败回退另一边 | `core/settings/.../FeedApiType.kt`、`core/feed/.../FeedMergePolicy.kt`、`WebFeedMapper.kt`、`feature/settings/.../feed/FeedSettingsScreen.kt`（PT-10，`2f55f95b`） |
| 内容与推荐 | story 竖屏短视频流 | `x/v2/feed/index/story`（App 签名通道），非视频卡在解析层丢弃；作起步兜底 | `core/feed/src/main/java/com/naaammme/bbspace/core/feed/StoryFeedRepository.kt`、`core/model/.../feed/StoryFeedItem.kt` |
| 内容与推荐 | 首页单列布局（单列大图 / 双列网格） | 顶栏可切换，预览图与预加载尺寸随档位切换 | `core/settings/.../AppSettings.kt`（`home_feed_single_column`）、`feature/home/.../video/HomeVideoPage.kt:125,155-161,314`、`FeedSettingsScreen.kt` 的「首页布局」 |
| 内容与推荐 | 详情页介绍区重设计 + 拆分 | 原 `VideoDetailPage.kt` 1221 行 → 现在主文件 667 行，拆出 4 个文件（摘要/相关/分P/投币收藏） | `feature/video/.../detail/VideoDetailSummary.kt`、`VideoDetailRelate.kt`、`VideoDetailSeasonPage.kt`、`VideoDetailSheetCoinFav.kt`（提交 `56d75163`、`eb709b8a`） |
| 内容与推荐 | 稿件信息补全（联合创作/合集/话题） | 详情与刷视频会话都能渲染 staff、合集入口、tag chip | `core/model/.../video/VideoDetail.kt`（+75 行）、`SwipeCoCreation.kt`、`SwipeSessionChrome.kt:59-72` |
| 内容与推荐 | 详情页取色光（上下都洒） | 详情区与刷视频会话都有封面主色氛围光，修三个真缺陷（缓存键/闪断/白闪） | 提交 `c071f046`、`2e279836`；`core/designsystem/.../color/VideoCardCoverColorStore.kt` |

### 1.5 设置项

设置页从 5 组（外观/性能/音视频/推荐/隐私）扩到 **7 组**，新增两个完整子页与多个开关。

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 设置项 | 空降助手设置页（新路由 `settings/sponsor_block`） | 总开关 / 11 个片段分类逐类跳策略 / 服务器地址（改 + 测试连接）/ 身份 ID 重置 / 进度条标记 / 跳过提示 / 数据说明 | `feature/settings/.../sponsorblock/SponsorBlockSettingsScreen.kt`、`SettingEntries.kt`（`sponsorBlockEntries`）、`navigation/SettingsNavigation.kt` |
| 设置项 | 刷视频诊断页（新路由 `settings/swipe_qoe`） | 只读 QoE 指标，可"导出文本"复制到剪贴板 | `feature/settings/.../qoe/SwipeDiagnosticsScreen.kt`、`SettingsScreen.kt:150` |
| 设置项 | 拉黑名单页（查看 / 撤销，新路由 `settings/blocked_up`） | 刷视频页拉黑的 UP 主可见可撤销（PT-06 只建了写入路径，入口在此补齐） | `feature/settings/.../privacy/BlockedUpScreen.kt`、`BlockedUpViewModel.kt`、`core/settings/.../blocked/BlockedUpStore.kt`（NX-08，`49d33067`） |
| 设置项 | 界面风格（Material / iOS） | 两套观感一键切换 | `feature/settings/.../appearance/AppearanceSettingsScreen.kt:337-357`、设置键 `ui_style` |
| 设置项 | 玻璃材质三档 + 强度倍率 | OFF / FROSTED / LIQUID + 0.5~2.0 统一强度 | `AppearanceSettingsScreen.kt` 的 `GlassSection`、设置键 `glass_material` / `glass_strength` |
| 设置项 | 顶部渐进消隐开关 | 内容滚入顶栏时非线性融进底色 | `AppearanceSettingsScreen.kt:250-251`、键 `top_progressive_fade` |
| 设置项 | 播放页控件模糊开关 | 控制条采样实时画面做玻璃，关则回落纯色遮罩 | `AppearanceSettingsScreen.kt:266-267`、键 `player_controls_blur` |
| 设置项 | 全局拖拽反馈开关 | 「统一控制按钮与胶囊的拖动形变，关闭后保留按压反馈」 | `AppearanceSettingsScreen.kt:1075`、键 `global_drag_feedback_enabled` |
| 设置项 | 现场诊断日志开关 | 29 处"release 也要可见"的取证日志统一由 `DiagnosticLog` 收口，默认关 | `feature/settings/.../performance/PerformanceSettingsScreen.kt`、键 `diagnostic_log_enabled`、`core/common/.../log/DiagnosticLog.kt`（Q-3） |
| 设置项 | 省电模式（视频用 SurfaceView） | 真机实测 −28% CPU；代价（直角、真模糊失效）如实写进副标题 | `PerformanceSettingsScreen.kt`、键 `player_surface_view` |
| 设置项 | 推荐来源 / 首页布局 / 内容偏好 | 见 §1.4 | `feature/settings/.../feed/FeedSettingsScreen.kt` |
| 设置项 | CDN 智能选线（实验）四栏 | 属地优选 / 严格只用自定义线路 / 目标 host / 自定义改写规则 | `feature/settings/.../audioVideo/CdnRegionSettingsSection.kt`、`CdnRuleTextFormat.kt` |
| 设置项 | 播放面板新增开关（7 项） | 并行分片下载 / 分段预取 / 播完自动下一个 / 跨会话已推去重 / 刷视频防烧屏 / 预加载下一个视频 / 单个视频循环 | `feature/video/.../player/VideoPlaybackSheet.kt:337-471` |

### 1.6 工程质量

| 分类 | 功能 | 一句话说明 | 关键证据路径 |
|---|---|---|---|
| 工程质量 | 自制 Lint 规则模块 `:ci-lint`（6 条） | 用 Lint 取代"扫源码 + 钉计数"的文本棘轮；机制照上游 BiliPai 的 `Lint-Registry-v2` + `lintChecks` | `ci-lint/src/main/java/com/naaammme/bbspace/lint/CiIssueRegistry.java`；6 条 id：`BbspaceNoPrintln`、`BbspaceAnimateAsState`、`BbspaceHardcodedAnimationDuration`、`BbspacePathOutlineAllocation`、`BbspaceUnboundedRunBlocking`、`BbspaceSpLiteral` |
| 工程质量 | Lint 接线与逐模块豁免基线 | 根 `build.gradle.kts` 挂 `lintChecks`；6 个模块用 `lint.xml` 逐处登记存量 | `build.gradle.kts:60-73`、`app/lint.xml`、`core/designsystem/lint.xml`、`feature/{home,video,dynamic,rank}/lint.xml` |
| 工程质量 | PR 门禁（GitHub Actions） | assembleDebug + **assembleRelease**（覆盖 R8/keep/lintVital）+ 全模块单测 + 逐模块 `--rerun` 且自校验"真的执行了" + 自定义 lint 零命中 | `.github/workflows/pr.yml`（新增），`.github/workflows/build.yml`（上游已有） |
| 工程质量 | 单测体系 | 上游仅 2 个示例测试（`ExampleUnitTest` / `ExampleInstrumentedTest`）；本版 **200 个单测文件** + 6 个仪器测试，大量"结构守卫/棘轮"型 | 例：`core/designsystem/src/test/java/com/naaammme/bbspace/core/perf/`（`DesignTokenLintTest.kt`、`FrameBudgetLintTest.kt`、`Glass*Test.kt`）、`app/src/test/.../hygiene/PackageDirectoryConsistencyTest.kt` |
| 工程质量 | 仪器测试 | 粒子消散、系统减弱动效、搜索历史消散、CDN 弹层等 6 个 | `core/designsystem/src/androidTest/.../motion/ParticleDissolveInstrumentedTest.kt`、`theme/SystemReduceMotionInstrumentedTest.kt`、`feature/video/src/androidTest/.../VideoCdnDiagnosticsDialogInstrumentedTest.kt` 等 |
| 工程质量 | 约定插件扩展 | 50 个模块的重复 Android 配置收进 `build-logic`，新增 Room schema 导出、compileSdk minor 等 | `build-logic/convention/src/main/kotlin/*.kt`（4 个文件改动，提交 `577046e3`、`cfea4616`） |
| 工程质量 | Room schema 导出 + 真实迁移 | 5 个库 `exportSchema` 打开并提交快照；`playback_history` 补 1→2、2→3 两步真实迁移，删掉破坏性回退 | `core/history/src/main/java/com/naaammme/bbspace/core/history/PlaybackHistoryMigrations.kt`、`core/*/schemas/**/*.json`（5 份）、守卫 `RoomSchemaGuardTest.kt` / `RoomMigrationCopyListTest.kt`（ST-01/ST-02，`1442c5b2`） |
| 工程质量 | 取消语义统一收口 | 裸 `runCatching` 不再把"取消"当"失败"（避免"用户自己关页面"弹「导入失败」） | `core/common/src/main/java/com/naaammme/bbspace/core/common/coroutines/RunCatchingCancellable.kt`、守卫 `CancellationSweepGuardTest.kt`、`RunCatchingCancellableTest.kt`（ST-06） |
| 工程质量 | 用户可见错误文案统一 | 异常 → 用户看得懂的文案，不再把 `Failed to connect to /...:443` 塞进界面 | `core/common/src/main/java/com/naaammme/bbspace/core/common/error/UserFacingError.kt`、提交 `b11fe432`、`aba99f07` |
| 工程质量 | 冷启动阶段耗时 / 帧掉帧 / 内存三条现场读数 | `ColdStartTiming` / `FrameJankMonitor` / `MemoryDiag`，三行合看能区分"卡在渲染/解码/GC" | `infra/coldstart/.../ColdStartTiming.kt`、`app/src/main/java/com/naaammme/bbspace/perf/FrameJankMonitor.kt`、`memory/MemoryDiag.kt` |
| 工程质量 | 内存分级释放（onTrimMemory） | 分档抽成纯函数；UI_HIDDEN 只 trimToSize 不清空图片缓存 | `app/src/main/java/com/naaammme/bbspace/memory/MemoryTrimPolicy.kt`、测试 `MemoryTrimPolicyTest.kt` / `MemoryTrimRegistrationTest.kt` |
| 工程质量 | 外链接管与回退 | deep link 解析失败回退系统其它应用，不再"点了应用装死" | `app/src/main/java/com/naaammme/bbspace/navigation/AppLink.kt`、`core/model/.../WebLinkParser.kt`（域名边界校验，B-1）、`WebLinkParserTest.kt` |
| 工程质量 | 文档与台账体系 | `docs/ai-loop/`（BACKLOG / PROGRESS / FINDINGS / L4 待真机清单）+ 每批次执行报告 + `docs/design/` 控件规范 | `docs/ai-loop/PROGRESS.md`（427KB）、`docs/ai-loop/FINDINGS.md`、`docs/ai-loop/L4-待真机清单.md`、`docs/design/控件体系规范.md` |
| 工程质量 | 设计/性能审计脚本 | 设计 token 审计、转场分析、性能基准、移植符号审计、消散效果真机验证 | `scripts/design-token-audit.py`、`scripts/analyze_transition.py`、`scripts/perf-baseline.ps1`、`tools/audit-ported-symbols.py`、`tools/verify-dissolve-on-device.ps1` |

---

## 2. 相对原版的改进

「原版是什么样 → 现在是什么样」，只列能从代码/提交里读出的事实。

### 2.1 播放与刷视频

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 播放 | 缓冲策略三档 | `maxBufferMs` 15s/20s/30s → **20s/25s/40s**（起播时间不受影响，只加厚抗抖动缓冲） | `core/model/.../player/PlayerSettingsModels.kt`（`minBufferMs=5_000`、`maxBufferMs` 三档） |
| 播放 | seek 语义 | 原版所有 seek 混用同一入口 → 按意图分四类，用户拖拽吸附关键帧、跳段/续播保持精确 | `infra/player/.../SeekParametersPolicy.kt`、`feature/video/src/test/.../SeekIntentSeparationGuardTest.kt`（PB-02） |
| 播放 | 起播速度 | 原版每次翻页重新拉 playurl → playurl LRU（90s）+ 相邻页预取 + 头部字节预热三件套 | `core/playback/.../repository/PlayUrlCache.kt`、`infra/player/.../PlaybackHeadPrewarmPolicy.kt`、`core/swipe/.../SwipePrefetchPolicy.kt` |
| 播放 | 蜂窝流量纪律 | 原版详情报文/取流不区分网络 → 蜂窝下不预热详情、不做头部预热、不做分段预取 | `core/swipe/.../SwipePrefetchPolicy.kt`（`shouldWarmDetail`）、`PlaybackHeadPrewarmPolicy.shouldPrewarm(isUnmetered)`、`SwipeSourceRegistry.available(isUnmetered)`（D4，`f10519a5`） |
| 播放 | 收起路径 | 原版有「应用内小窗」（`InAppMiniPlayer` + `DraggableMiniPlayerHost`，已删除）→ 改为**悬浮胶囊**，且与底栏联动合并 | 提交 `0e1d5c57`（−630 行）、`app/.../playback/capsule/CapsuleIndicator.kt` |
| 播放 | 返回键行为 | 原版后台回收后返回键被吞 → 收起小窗走 `collapseToCapsule` 单一状态机 | `app/.../playback/PlaybackHostState.kt`（提交 `cb0cb80b`） |
| 播放 | 首帧主题 | 原版首帧主题需要主线程 `runBlocking` 读设置 → 改读镜像快照，首帧不再阻塞 | `core/settings/.../AppSettings.kt`（`SETTINGS_MIRROR_PREFS` 镜像）、`SettingsMirrorThreadingTest.kt`（提交 `b38d0eba`、`ccee21cc`） |
| 刷视频 | 会话内互动 | 原版连播页只有画面 → 现在有点赞/收藏/评论/分享/长按三连/拉黑/不感兴趣，且状态读"互动状态"而非旧统计值 | `PortraitSwipePager.kt:830-860`、提交 `45f6733f`（VK-01 真机"点了没反应"根因） |
| 刷视频 | 互动栏尺寸与热区 | 整体缩小但热区保持 ≥48dp | 提交 `47cb00b7`（SX-02）、`core/designsystem/.../component/BiliTouchTargets.kt` |
| 刷视频 | 评论面板形态 | 原版评论是整屏弹层 → 会话内改 68% 高半透明悬浮卡（"不要占满屏"）+ 展开/收起缓动曲线 | `SwipeSessionChrome.kt:132-238`、提交 `97569862`（SX-10）、`7c2ab9af`（SX-07） |
| 刷视频 | 点赞视觉两态 | 原版点赞只有一种视觉 → 未赞（描边）/已赞（填充 + 主题色） | 提交 `3c193ee5`（SX-09） |
| 刷视频 | 换页吸附动画 | 原版裸写时长/曲线 → 走 token 并尊重 reduceMotion | 提交 `3705d18a`（SX-08） |
| 刷视频 | 手势分层仲裁 | 原版"评论页下滑"会被判成"想进刷视频" → 页面级手势分层仲裁 | 提交 `166be8c2`（SX-01）、`feature/video/.../detail/VideoDetailPage.kt:129-146` |

### 2.2 播放器内核与网络

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 内核 | 单文件 DASH 解析 | 原版解析函数私有且只认常规布局 → 抽出 `SingleFileDashIndexParser`，支持 largesize 盒头 / 零长度盒 / 混合头宽，任何输入不抛异常 | `infra/player/.../SingleFileDashIndexParser.kt` + 9 条单测（批次 B1） |
| 内核 | 卡死检测 | 原版只有 `onPlayerError` 驱动的恢复，无 error 的卡死永远转圈 → 增加"position 与 buffered 双不前进"的看门狗 + 阶梯恢复 | `infra/player/.../StallRecoveryPolicy.kt`、`PlaybackWatchdogPolicy.kt` |
| 内核 | 引擎所有权 | 原版两侧都会无条件 `release()` → 引入 `PlayerEngineArbiter`，只有占用者能释放 | `core/playback/.../PlayerEngineArbiter.kt` + `PlayerEngineOwnershipGuardTest.kt`（ST-07） |
| 内核 | CDN 选线 | 原版按固定模式（base / backup1 / backup2 / 指定 host）取第一条 → 新增 **智能档**：按本机实测健康度排序，越用越准 | `core/model/.../player/PlayerSettingsModels.kt`（`VideoCdnMode.Smart`）、`CdnHealthPolicy.kt` |
| 网络 | 超时纪律 | 原版共享 client 未显式设下超时、允许 callTimeout → 显式写下超时并禁 callTimeout，加 4 条守卫 | 提交 `fc3fe0f9`（PF-01）、`app/src/test/.../network/NetworkTimeoutGuardTest.kt` |
| 网络 | 同步 `execute()` 的 Response 释放 | 原版多处漏释放 → 成对释放 + 守卫 | 提交 `25416193`（B3，ST-05）、`HttpResponseReleaseGuardTest.kt` |
| 网络 | 短链解析 | 新增 `ShortLinkResolver`，b23.tv 302 有明确落点 | `infra/network-http/.../ShortLinkResolver.kt` |

### 2.3 界面与观感

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 观感 | 首页顶栏 | 原版 `CollapsingTopBarScaffold` + 简单折叠，滚动后偏移被 remember 闭包捕获成首帧 0（顶栏卡住）→ 抽出 `HomeTopBarScroll` 状态机（偏移累加 + fling 吸附），顶栏高度 144dp → 约 95dp | `feature/home/.../HomeTopBarScroll.kt`、`HomeScreen.kt:144-170,463-490` |
| 观感 | 圆角 | 原版默认 `STANDARD`；卡片 12dp 字面量散落 8 处 → 默认 `ROUNDED`，卡片/封面/播放器/无缝动画统一走 `BiliRadius`（20dp） | `core/designsystem/.../theme/ThemeConfig.kt`、`Radius.kt`、提交 `1b59fd7e`、`dec80e60`、`d7bbf677` |
| 观感 | 播放页控件 | 原版 M3 默认控制条 + 半透明黑遮罩 → 现代化控制行（Edge 边缘纯白版）+ 四套浮层底/圆角/白度全部收成 token | `feature/video/.../VideoPlayerPane.kt`（868 → 3512 行）、`docs/design/d-ios*-*.md` 规格表 |
| 观感 | 卡片取色 | 原版无封面取色 → 有，且修了三处根因（缓存键与 Coil 对齐 / 绘制期解析 / 未触发分支）；取色不再复制整张位图 | 提交 `5ff7bbe9`、`be73043f`、`727c4961`、`31f802cc` |
| 观感 | 骨架屏 | 原版静止色块 → 微光扫过；骨架封面圆角与真实卡片对齐 | `Skeleton.kt`、提交 `1b6b28cd`、`ad66e163` |
| 观感 | 列表/网格错误态 | 原版直接把 `e.message` 渲染进错误卡 → 统一 `StateMessageCard` + 就地重试 | `core/designsystem/.../component/StateMessageCard.kt`、提交 `b11fe432`、`7f7eed6f` |
| 观感 | 弹层一致性 | 原版各处弹层圆角/grabber 各写一套 → 7 处收口到 iOS 控件语汇 | 提交 `ea82c30e`、`549811eb`（T5 对比度审计） |
| 无障碍 | 图标按钮朗读标签 / 48dp 触控下限 | 原版缺 → 补齐并加结构断言 | 提交 `a436d012`（T6）、`BiliTouchTargets.kt`、`core/a11y/AccessibilityStructureTest.kt` |
| 字体 | 中文横排字距 / 数字等宽 | 原版未校准 → 按中文横排校准字距并开启数字等宽 | 提交 `65ddcdbe`、`theme/TextStyleVariants.kt`（`asBold` 等具名出口） |

### 2.4 交互闭环（原版明确未实现）

上游 README 的「正在补全」里写着 `点赞、投币、收藏`、`空降助手` 两项未做。本版两项都已落地。

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 互动 | 视频点赞 / 投币 / 收藏 / 一键三连 | 原版 `ActionChip("点赞", it.like)` 只是**计数展示**，`onClick` 传 null → 三个 chip 现在都接 `View>` 动作，带计数动效、失败回滚、未知态保护 | 上游侧：`git show upstream/main:.../VideoDetailPage.kt`（`ActionChip(label, value)` 无 onClick）；本版：`feature/video/.../detail/VideoDetailSummary.kt:460-530`、`VideoActionCountMotion.kt`、`core/video/.../VideoActionRepository.kt`（`setLike` / `addCoin` / `dealFav` / triple） |
| 互动 | 双击点赞 | 原版双击 = 快进/快退 10s（`DOUBLE_TAP_SEEK_MS`）→ 新增「双击画面点赞」开关：开 = 点赞 + 爱心动效，关 = 恢复暂停/播放 | `feature/video/.../player/VideoGestureController.kt:117,140-141,234-245`、`VideoPlaybackSheet.kt:463-464` |
| 互动 | 评论点赞 / 点踩 | 原版 `CommentReply` 只有 `likeCount` 展示，仓库无点赞方法 → 新增 `likeReply` / `hateReply`（Web 端点，点赞与点踩互斥） | `core/comment/.../CommentRepository.kt:89-104`、`feature/comment/.../CommentViewModel.kt:410-420` |
| 互动 | 动态点赞 / 转发 / 发布 | 原版 `DynamicRepository` 只有读取（`like = stat.like` 展示）→ 新增 `thumbDyn`（Web 端点）与发布/转发（scene 1/2/4/5） | `core/dynamic/.../DynamicRepository.kt:227-272` |
| 互动 | 空降助手（SponsorBlock） | 原版 README 列为未做，仓库无任何 sponsor 代码 → 完整接入：社区服务器（默认 `https://www.bsbsb.top`）+ 11 分类 × 4 档策略 + 自动/手动跳过 + 进度条标记 + 跳过提示 + 身份 ID | `core/sponsorblock/.../SponsorBlockClient.kt`、`SponsorBlockModels.kt`、`feature/video/.../VideoViewModel.kt:104-113,373-496`、`VideoPlayerPane.kt:2593,3581`（`SponsorBlockOverlay`） |
| 互动 | 空降跳段与 seek 吸附的关系 | 原版无此问题；本版刻意让跳段走 `forPreciseSeek`（吸附会重新落进片段内触发重入） | `SeekParametersPolicy.kt` 的对照表 + `VideoLogicGuardTest.sponsorBlockSkipTypesAreAllExplicit` |

### 2.5 稳定性与正确性（成批的 fix，非新功能）

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 正确性 | 点赞取反 | App 协议 `like=0` 才是点赞，原版传反 → 修正并写注释 | `core/video/.../VideoActionRepository.kt:32-36`（提交 `f146dfd2`） |
| 正确性 | 外链域名校验 | `host.startsWith("space")` 不校验域名，`https://spaceevil.example/2` 会进 UID 2 空间页 → 改为按域名边界校验 | `core/model/.../WebLinkParser.kt`（B-1）、`WebLinkParserTest.kt` |
| 正确性 | 详情遮罩状态键 | 原版按封面 URL 复用状态 → 改以视频 ids 为键 | 提交 `4c2825b7` |
| 正确性 | 单例回调泄漏 | 单例回调拖住已销毁 Activity → `onDestroy` 按身份比对清理 | 提交 `25416193`（ST-03）、`SingletonCallbackLeakGuardTest.kt` |
| 正确性 | Geetest WebView 未销毁 | 原版不摘桥不销毁 → `onRelease` 里摘桥并销毁 | 提交 `d2572791`（ST-04） |
| 正确性 | 极验/buvid3/wbi 缓存/下载竞态 | 五处协议与凭据正确性修复 | 提交 `f146dfd2`、`c024b68c` |
| 正确性 | 导航返回键退整个 App | nav 2.10.1 换 `navigationevent` 后接线缺失 → 补齐 | 提交 `37cad24f`、`NavBackHandlingGuardTest.kt` |
| 正确性 | 搜索旧结果回灌 | 返回键路径不推进 `searchGen` → 在途请求返回会把上一次结果写回已清空的页面 | `feature/search/.../SearchViewModel.kt:280-310`（提交 `afbd490c`） |
| 正确性 | 下载孤儿目录 / 入队竞态 | 启动清无主下载目录（查询失败一律不删）；入队去重放进同一写事务 | `core/download/.../DownloadOrphanSweep.kt`（ST-11/ST-13） |
| 正确性 | 听书进度条空值崩 | `ST-09` 修 | 提交 `95177246` |
| 正确性 | gRPC 坏帧 | 按自己写的长度域取载荷，坏帧抛 `BiliApiException` | 提交 `1ed1c98d`（ST-14）、`GrpcFrameCodecTest.kt` |
| 稳定性 | release 不再抓全量堆栈 | 收口静默失败与取消语义 | 提交 `186ccff1` |
| 稳定性 | 首页"滑一会就卡住" | 修 | 提交 `5e9e06c5` |
| 稳定性 | 无缝动画期详情骨架门控 / 打开动画抽搐 | 恒占位 + 封面遮罩淡出；`RECT`/`LIST_COVER` 兜底 | 提交 `2c0e4d4f`、`c7046d77` |
| 稳定性 | `alwaysRetainTaskState` | 防桌面图标回前台 singleTask 清栈销毁视频详情页 | 提交 `8a81437b`（A） |
| 稳定性 | player 释放前 `unbindAll` | 修小窗 × 崩溃（`Player is released`） | 提交 `ac01078d`（B） |

### 2.6 性能

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 性能 | 玻璃链路 | 原版无；本版在同一帧第二遍全屏离屏重录、控制条可见时每帧重录两处都收紧（后者 30Hz 节流） | 提交 `403ba19a`（T1）、`01a4f974`（R2）、`VideoGlassTickPolicy.kt` |
| 性能 | 信息流卡片/顶栏 | 消除每帧分配与每帧重组；封面预加载 URL 对齐（此前 100% 白做）+ 滚动中入队 | 提交 `e64a20de`（T3）、`d2ae316d`、`7ee1d17a` |
| 性能 | 组合期读动画值 | 棘轮 10 → 8；后由 Lint 规则 `BbspaceAnimateAsState` 接管 | 提交 `f67ba8d0`（T4）、`ci-lint/.../AnimateAsStateDetector.java` |
| 性能 | Dock 合并动画 | 消除合并期间的整树重组 | 提交 `51a5d26d`（T2） |
| 性能 | 取色与图片 | 取色不复制整张位图 / 图片宽度夹取 / 后台绝对内存预算 | 提交 `31f802cc` |
| 性能 | 组合期 Binder 往返 | `currentWindowAdaptiveInfo` → V2，消除组合期 ~181ms Binder 往返 | 提交 `9b5bcdad` |
| 性能 | ViewModel init 磁盘读 | 账户信息读取移出主线程（实测 DiskReadViolation 195ms） | 提交 `39e6fb0c` |
| 性能 | 内存回收 | `onTrimMemory` 分级释放；UI_HIDDEN 只 trimToSize | 提交 `cc319c4e`、`19cae687` |
| 性能 | 播放页每帧成本 | 玻璃重录节流 + 手势 HUD 绘制层化 | 提交 `01a4f974`（R2/U2） |
| 性能 | Baseline Profile | 新增 `app/src/main/baseline-prof.txt` + Compose 覆盖守卫 | `app/src/main/baseline-prof.txt`、`app/src/test/.../perf/BaselineProfileComposeCoverageTest.kt` |

### 2.7 构建与工具链

| 分类 | 项 | 原版 → 现在 | 证据 |
|---|---|---|---|
| 构建 | minSdk | **24 → 33**（提交 `57eea603` 的说明是"删除全部 12 处 API 功能性门控"；实测 `Build.VERSION.SDK_INT >=` 从上游 6 处降到 1 处，剩下的 1 处是 GPU 能力判断 `SDK_INT >= O`） | `build-logic/convention/.../AndroidApplicationConventionPlugin.kt:38`、`AndroidLibraryConventionPlugin.kt:48`；`git grep -n "SDK_INT >=" upstream/main`（6 处）vs `HEAD`（1 处，`VideoCardCoverColorStore.kt:212`）；提交 `57eea603` |
| 构建 | compileSdk / targetSdk | 36 → **37.2** / targetSdk 36 | `AndroidApplicationConventionPlugin.kt:30-39` |
| 构建 | AGP / Gradle | 8.13.2 → **9.4.0**；Gradle 9.3.1 → **9.6.0** | `gradle/libs.versions.toml:15`、`gradle/wrapper/gradle-wrapper.properties` |
| 构建 | Kotlin / KSP | 2.2.21 → **2.3.20** | `gradle/libs.versions.toml:38` |
| 构建 | Hilt / Room | 2.58 → **2.60.1**；Room 2.7.2 → **2.8.4** | `gradle/libs.versions.toml:42,54` |
| 构建 | Navigation | 2.8.5 → **2.10.1** | `gradle/libs.versions.toml:58` |
| 构建 | media3 / Coil | 1.10.1 → **1.11.0**；3.4.0 → **3.6.2** | `gradle/libs.versions.toml:75,69` |
| 构建 | 新增依赖族 | miuix-blur / miuix-ui / miuix-icons / miuix-squircle、haze 2.0.1 | `gradle/libs.versions.toml:21-27,99-106` |
| 构建 | release 优化 DSL | 切到 AGP 9.3 的 `optimization{}` DSL，移除 `getDefaultProguardFile` 依赖 | 提交 `1038c2de`、`13c78bd3` |
| 构建 | release 签名 | 由环境变量注入（`SIGNING_STORE_FILE` 等），CI 无凭据自动跳过 | `app/build.gradle.kts`、`.github/workflows/pr.yml` |

---

## 3. 上游已有、本版未改动的功能

这些是原版 README「当前功能」里列出、本版**主体逻辑未动**（改动仅为 build.gradle 收口 / 取消语义 / 界面 token 化）的功能：

- 账号：**扫码登录**、**短信登录**、**多账号管理**
  - 本版改动仅限于取消语义与 `AuthStore`/`AuthRepository` 的正确性修复（`core/auth` 3 个文件）
- 内容：**视频搜索**、**评论区**（发布/删除/翻译/排序/发评反诈）、**动态流**、**个人空间**、**稍后再看**
  - 搜索改动为代际作废 + 界面 token（`feature/search` 7 个文件）；评论改动为点赞/点踩 + 缓存键（`feature/comment` 6 个文件）
- 播放：**弹幕**（`DanmakuFlameMaster` 渲染链路未动，仅补分段窗口策略）、**听视频**、**直播**、**应用内缓存**（离线下载播放）、**视频音频一键下载导出**
  - 听视频/live 改动为 build.gradle 收口 + 少量 UI（`feature/listen` 3、`feature/live` 3 个文件）
- 社交：**即时消息（IM）**
  - 改动为账号移除时回收 IM 设备 id 缓存（ST-12）+ 取消语义
- 其他：**评论弹幕记录**、**评论历史查询**、**用户关系查询**、**禁止 Gaia 上报**

上游 README「正在补全」5 项的现状：

| 项 | 状态 | 说明 |
|---|---|---|
| 字幕 | **仍未实现** | 全仓无 `SubtitleTrack` / `Tracks.Group` 订阅，播放器无字幕轨选择入口；`Icons.Rounded.Subtitles` 只用作弹幕开关图标（`VideoPlayerPane.kt:53,1060`） |
| 分类搜索 | **仍未实现** | 全仓无 `tids` 参数使用；搜索页的筛选 sheet 结构上游已有（`SearchFiltersSheet.kt` 上游同路径存在） |
| 点赞、投币、收藏 | **已实现** | 见 §2.4 |
| 空降助手 | **已实现** | 见 §2.4 |
| 其他细节完善 | **持续进行** | 见 §2.5（稳定性/正确性）与 §2.1–2.3（体验） |

---

## 4. 说明

### 4.1 版本基线

- 上游对照点：`upstream/main` = `93eb904b`（2026-08-10，提交名 `tweaks`）。该提交同时也是 `merge-base`，即本分支是从这一点**线性领先**，没有跨分支合并。
- 当前工作点：分支 `build/sx-swipe-experience`，`HEAD = 87fefe6d`（2026-10-08，`docs(PV): 真机轮读数入台账…`）。
- 版本号：`app/build.gradle.kts` 仍是 `versionCode = 10804` / `versionName = "1.8.04"`（**未随开发推进**；README 的 v1.8.04 日志就是这个口径下写的）。

### 4.2 差异口径（重要）

1. **本文的差异 = 工作区（含未提交改动）对比 `upstream/main`**，不是只算 HEAD。
   - `git diff upstream/main HEAD`：742 files, +94299 / −4998。
   - `git diff upstream/main`（工作区口径，不含未跟踪）：741 files, +107008 / −6332。
   - 另有 **119 个未跟踪条目**，其中 111 个是可读源码/资源文件（约 10410 行），**不计入上面两个数字**。
2. **任务书给的初始读数与实际略有出入**（都已按实测更正）：
   - 提交数：任务书写 252，实测 **267**（`git rev-list --count upstream/main..HEAD`，这是确定值）。
   - 工作区：任务书写 168 已修改 + 119 未跟踪，实测写作时为 **169 + 119 = 288 条**（快照，会随并行改动变动）。
3. **`:ci-lint` 整个模块目前未纳入 git**（`git ls-files ci-lint` 为空），但 `settings.gradle.kts:45` 已 `include(":ci-lint")`、根 `build.gradle.kts:62,73` 已挂 `lintChecks`、`app/lint.xml` 等 6 份豁免基线也未跟踪。也就是说：**该功能在当前工作区是生效的，但一提交就会丢**。
4. **文档类改动量极大但不算功能**：`docs/` 下新增 38 + 32 + 32 + 6 个文件（`ai-loop/`、根目录执行报告、`evidence-sx/`、`upstream-bilipai-port/`），`PROGRESS.md` 单文件 427KB。本文只把 `docs/` 当作线索，**没有把文档里的结论当作证据**——每条功能都回到源码或提交验证过。
5. **明确写"未见明确证据"的两项**：字幕、分类搜索。见 §3 表格。
6. **这些数字是快照**：本仓库同一时刻可能有其它会话/代理在改工作区（本文写作期间 `git status` 的计数就在 169+119 → 195+112 之间变动过，HEAD 未动、也没有新提交）。因此 `git status` 类计数只能作量级参考，**结构性结论（模块、文件、提交）不受影响**——那两类都指向确定的对象。

### 4.3 如何复核

```powershell
cd D:\111j\c\_ref\bbspace

# 基线
git log -1 upstream/main                                   # 93eb904b
git merge-base upstream/main HEAD                          # 93eb904b
git rev-list --count upstream/main..HEAD                   # 267
git log -1 --format='%h %s' HEAD                           # 87fefe6d

# 规模
git diff --stat upstream/main HEAD | Select-Object -Last 1                # 742 files
git diff --stat upstream/main HEAD -- <module>/                           # 单模块
git diff --name-status upstream/main HEAD --diff-filter=A -- core/swipe   # 新增文件清单

# 新增模块（上游没有）
git ls-tree -r --name-only upstream/main | Select-String 'swipe|rank|sponsor|ci-lint'   # 空
git diff --name-status upstream/main HEAD --diff-filter=A -- core/swipe core/rank feature/rank core/sponsorblock

# 模块清单变化
git diff upstream/main HEAD -- settings.gradle.kts

# 上游某项到底有没有（负证据：以下三条预期都输出空）
git show upstream/main:core/comment/src/main/java/com/naaammme/bbspace/core/comment/CommentRepository.kt | Select-String 'likeReply|hateReply'
git show upstream/main:core/dynamic/src/main/java/com/naaammme/bbspace/core/dynamic/DynamicRepository.kt | Select-String 'thumbDyn'
git ls-tree -r --name-only upstream/main | Select-String 'sponsor|Sponsor'

# 工作区口径
git status --porcelain | Measure-Object -Line                                # 288
git diff --stat upstream/main | Select-Object -Last 1                        # 741 files（不含未跟踪）
git ls-files ci-lint                                                        # 空（未跟踪）

# 单模块文件量与测试
(Get-ChildItem core/swipe/src/main -Recurse -Filter *.kt).Count             # 27
(Get-ChildItem core/swipe/src/test -Recurse -Filter *.kt).Count             # 23
(Get-ChildItem core/designsystem/src/main -Recurse -Filter *.kt).Count      # 99
```

**未做的事**（避免误读本文）：没有跑 Gradle（另有人在构建，会冲突），因此文中不含"编译通过/测试全绿"的断言；所有测试数字都是**文件数**，不是用例数。
