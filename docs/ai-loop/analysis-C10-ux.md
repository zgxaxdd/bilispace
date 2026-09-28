# C10 · BiliPai vs bbspace「竖屏刷视频」交互与观感差距分析（只读）

> 对比双方：BiliPai `_ref/BiliPai`（上游参照）· bbspace `_ref/bbspace`（待优化方，2026-09-28 三轮修复后状态）。
> 本文为**只读**分析，未改动任何源码、未编译、未联网。所有结论均带 `路径:行号` 证据。
> 路径前缀约定：`BP/…` = `_ref/BiliPai/app/src/main/java/com/android/purebilibili/…`；`BB/…` = `_ref/bbspace/…`。

---

## 〇、结论速览

1. **架构上 bbspace 不落后、反而更优**：播放器唯一 + `isCurrentVideo` 页身份（`BB/feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt:50-60`）比 BiliPai 每页持 PlayerView、`key(bvid)` 换视频即重建 surface（`BP/feature/video/ui/pager/PortraitVideoPager.kt:2621-2627`）更轻。
2. **最大观感差距在「切页交接的中段」**：bbspace 是「封面 →（硬切）深色加载层 → 首帧硬切」，BiliPai 是「黑底 + 系统转圈 → 首帧硬切」。两边都没有「封面 → 首帧交叉淡出」，bbspace 已有 veil 淡出机制，补齐交叉淡出的成本最低（Top 1）。
3. **最大功能差距在信息层**：BiliPai 竖屏页默认常驻「互动栏 + 头像/关注 + 标题 + chips + 细进度条 + 弹幕输入栏」（`BP/feature/video/ui/overlay/PortraitFullscreenOverlay.kt:194-354`）；bbspace 会话内默认只剩视频，一切控件藏在 `showCtrl` 初值 false 的浮层里（`BB/feature/video/.../player/VideoPlayerPane.kt:431`），且**会话内没有点赞/评论/收藏/分享入口**。
4. **加载与空态**：bbspace 粒子加载器观感优于 BiliPai 的 `AdaptiveLoadingIndicator`，但缺「最短展示时长」与「失败重试态」；BiliPai 有 story 入口的 loading/error/empty 三态（`BP/feature/story/StoryScreen.kt:98-119`）。
5. **手势两边基本对齐**，bbspace 多做了「会话内垂直拖动让位」（`BB/feature/video/.../player/VideoGestureController.kt:257-261`）；bbspace 缺 BiliPai 的 seek 视频帧预览（但 bbspace 无 videoshot 数据，见「四-1」）。
6. **可配置项 bbspace 缺 4 项**：仅真竖屏过滤、播完行为、预取开关、启动/上滑直达（对照 `BP/feature/settings/screen/PlaybackSettingsScreen.kt:2073-2146`）。

---

## 一、总览表

| # | 维度 | BiliPai（file:line） | bbspace（file:line） | 差距 | 体验影响 | 成本 | 建议 |
|---|---|---|---|---|---|---|---|
| 1 | 切页视觉交接 | 封面仅非当前页（`BP/…/pager/PortraitPagerSwitchPolicy.kt:80-89`）；当前页黑底 + `SHOW_BUFFERING_ALWAYS` 系统转圈（`BP/…/pager/PortraitVideoPager.kt:2658-2660, 2776-2781`）；拖动 25% offset 预取、跨 snap 中点即起播（`BP/…/pager/PortraitVideoLoadPolicy.kt:331-366`） | 封面页拖动中可见（`BB/…/player/PortraitSwipePager.kt:234-300`）；切播放器页后深色 veil + 粒子盖住旧帧，首帧后 200ms 淡出（`BB/…/player/VideoPlayerPane.kt:840-878` + `BB/…/player/PortraitSwipePager.kt:168-193`） | 两边都是「加载态 → 首帧硬切」；bbspace 多一次「封面 → 暗屏」硬切；均无交叉淡出；BiliPai 有 25% offset 预热，bbspace 只在跨中点才取流 | **高** | 低（veil 机制已在） | 封面 → 首帧交叉淡出（T1）+ 下一段预热（T4） |
| 2 | 信息层 | 默认常驻：顶栏(返回/首页/播放量/搜索) + 右互动栏(赞/币/评/藏/分享+长按三连 900ms 进度弧) + 头像/关注/标题/合集 + chips(时间/音质/画质/比例/倍速) + 细进度条 + 弹幕输入栏；控件隐藏仍留常驻细进度条（`BP/…/overlay/PortraitFullscreenOverlay.kt:194-394, 436-499, 546-748`；`BP/…/overlay/PortraitInteractionBar.kt:57-215`；`BP/…/overlay/PortraitBottomInputBar.kt:38-102`；`BP/…/overlay/PortraitProgressBar.kt:45-149`） | 会话内默认全隐：`showCtrl` 初值 false、2s 自动隐藏（`BB/…/player/VideoPlayerPane.kt:431, 989-1010`）；显示时才有顶栏(返回/首页/标题/弹幕/横屏/更多)与底部播放控制条（`BB/…/player/VideoPlayerPane.kt:626-743, 1163-1187, 1281-1392`）；封面页只有标题+@作者（`BB/…/player/PortraitSwipePager.kt:281-297`） | bbspace 会话内**无点赞/评论/收藏/分享/关注/播放量/搜索入口**，无常驻进度条；标题/UP 只在控件弹出时出现 | **高** | 中（互动栏可复用详情页既有能力） | 默认可见 + 2s 自动隐藏 + 右侧互动栏 + 常驻细进度条（T2） |
| 3 | 手势 | 竖屏 pager 垂直轴向完全让给 pager（无亮度/音量）；水平拖动 seek 带**视频帧预览**（`BP/…/pager/PortraitVideoPager.kt:2519-2554, 2805-2882`）；双击左右 30% 快进/后退、中间暂停（`BP/…/overlay/FullscreenDoubleTapPolicy.kt:18-48`）；长按倍速带循环箭头提示（`BP/…/pager/PortraitVideoPager.kt:2489-2507, 2916-2929`）；捏合 1–5x + 平移且放大时禁用 pager（`BP/…/pager/PortraitVideoPager.kt:2349-2402` + `BP/…/pager/PortraitPagerSwitchPolicy.kt:134-142`） | 会话内垂直拖动整体让位（`BB/…/player/VideoGestureController.kt:257-261`）；水平 seek 仅文字气泡 ±60s 上限（`BB/…/player/VideoGestureController.kt:94-99, 282-290, 360-371`）；双击=点赞爱心或暂停（`BB/…/player/VideoGestureController.kt:204-220` + `BB/…/player/VideoPlayerPane.kt:889-921`）；长按倍速徽标（`BB/…/player/VideoGestureController.kt:185-187, 405-417`）；捏合 1–5x、放大态单指平移并消费事件（`BB/…/player/PlayerZoomState.kt:78-163`） | 门控与冲突处理两边都对；bbspace 少 seek 帧预览；bbspace 双击语义更贴近 B 站官方（点赞） | 中 | 高（帧预览需数据）/ 低（其余） | 保留双击点赞；seek 预览不做（见四-1）；补放大态 pager 显式门控（T2 附带） |
| 4 | 沉浸与系统栏 | 进场 edge-to-edge + 隐藏双栏 + 瞬态滑动显示，dispose 精确恢复（`BP/…/pager/PortraitVideoPager.kt:304-340`）；顶栏 statusBarsPadding（`BP/…/overlay/PortraitFullscreenOverlay.kt:559`）；长按提示避让状态栏（`BP/…/pager/PortraitVideoPager.kt:2926-2927`）；Story 是应用内导航目的地，走系统预测性返回（`BP/navigation3/BiliPaiNavKey.kt:315` + `BP/AppNavigation.kt:3658-3664`） | 全屏即隐藏双栏 + dispose 恢复（`BB/…/VideoScreen.kt:349-375`）；会话锁竖屏 + 手动横屏胶囊 + dispose 复位（`BB/…/VideoScreen.kt:377-425`）；进场走 OPPO 无缝（跟手/阻断/回弹），退出 `finishWithHostState` 等返回动画（`BB/…/seamless/OplusViewSeamlessCompat.kt:11-30, 100-129` + `BB/app/.../video/VideoDetailActivity.kt:243-269`） | 系统栏管理两边等价；bbspace 多一层厂商无缝动画（非 OPPO 降级）；**详情页 → 全屏刷视频是瞬时状态切换、无过渡动画**（`BB/…/VideoScreen.kt:458-500`），BiliPai 卡片 → 详情有 morph（`BP/navigation/PortraitStoryNavigationPolicy.kt:14-16`） | 中 | 低 | 详情进全屏加 200–280ms 淡入/缩放过渡（并入 T2 或单独小项） |
| 5 | 加载与空态 | 当前页黑底 + media3 转圈 + `AdaptiveLoadingIndicator`（`BP/…/pager/PortraitVideoPager.kt:2776-2781`）；story 入口 loading/error(带重试)/empty(带重试) 三态（`BP/feature/story/StoryScreen.kt:98-119, 166-182`） | 封面页粒子（屏宽 26%）+ 等首帧 veil 粒子（屏宽 34%、深色渐变、绘制期读相位零重组）（`BB/…/player/PortraitSwipePager.kt:254-265` + `BB/…/player/VideoPlayerPane.kt:840-878` + `BB/core/designsystem/.../component/BiliParticleLoader.kt:45-63`） | bbspace 加载器形态更高级；但**无最短展示时长**（快网闪烁）、**失败即裸退**（错误仅躺在设置面板里 `BB/…/player/VideoPlaybackSheet.kt:412-425`）、队列耗尽无「到底」反馈（`BB/…/swipe/PortraitSwipeQueue.kt:234-241`） | 高 | 低 | veil 最短 400ms + 失败重试态（T3） |
| 6 | 无障碍与性能 | 图标均有 contentDescription（`BP/…/pager/PortraitVideoPager.kt:2796-2797`；`BP/…/overlay/PortraitFullscreenOverlay.kt:576-625`）；封面 `contentDescription = null`（`BP/…/pager/PortraitVideoPager.kt:2762, 2770`）；baselineprofile 体系（`BP/baselineprofile/.../BiliPaiBottomPagerFrameTimingBenchmark.kt:15-31` 等 9 个）；pager UI 回归测试（`BP/app/src/androidTest/.../PortraitVideoPagerUiRegressionTest.kt:23-60`）；stable lists 只追加不重建（`BP/…/pager/PortraitVideoPager.kt:479-486`）；代际守卫（`BP/…/pager/PortraitPagerSwitchPolicy.kt:44-53`） | 封面 `contentDescription = title`（优于 BiliPai，`BB/…/player/PortraitSwipePager.kt:243`）；玻璃背板逐帧重录有消费门控（`BB/…/player/VideoPlayerPane.kt:525-541`）；粒子零重组（`BB/core/designsystem/.../component/BiliParticleLoader.kt:34-41`）；源码不变式守卫 + 纯逻辑单测（`BB/feature/video/src/test/.../VideoLogicGuardTest.kt:367-710`；`BB/feature/video/src/test/.../swipe/PortraitSwipeQueueTest.kt`）；无 baseline profile | 两侧自绘进度条都无 Slider 语义（`BB/…/player/VideoPlayerPane.kt:1417-1461`；`BP/…/overlay/PortraitProgressBar.kt:106-149`）；bbspace 缺 macrobenchmark，但有 lint 级帧预算守卫 | 中 | 低（语义）/ 高（benchmark） | 补进度条/互动栏 semantics；**不**引入全套 baselineprofile（四-6） |
| 7 | 可配置项 | 竖屏上滑进全屏、竖屏直达、仅真竖屏(Beta)、启动进竖屏流、中部滑动切全屏（`BP/feature/settings/screen/PlaybackSettingsScreen.kt:2073-2165`）；自动连播 + 播完行为枚举（`BP/core/store/SettingsManager.kt:380, 1060-1065`）；预取开关（消费于 `BP/…/pager/PortraitVideoPager.kt:412-413`）；画质/音质/长按倍速及其提示样式（`BP/…/pager/PortraitVideoPager.kt:422-454, 1779-1802`） | 仅有：自动横屏、长按倍速、双击点赞、无缝开关、单曲循环、缓冲档、CDN、后台播放、软解等（`BB/core/model/.../player/PlayerSettingsModels.kt:113-128` + `BB/feature/video/.../player/VideoPlaybackSheet.kt:323-400`） | 缺：仅真竖屏过滤、播完行为（写死连播且与 repeatMode 互斥只在注释里 `BB/…/player/PortraitSwipePager.kt:145-147`）、预取开关、直达入口；自动连播无 `lastAutoAdvancedBvid` 式去重（对照 `BP/…/pager/PortraitVideoPager.kt:902-905`） | 中 | 低 | 加 2–3 个开关 + 连播去重守卫（T5） |

---

## 二、逐维度详解

### 2.1 切页视觉交接

**BiliPai：黑底起播 + 系统转圈，封面只属于非当前页。**

封面显隐策略明确「当前播放页一律不显示静态封面」（`BP/…/pager/PortraitPagerSwitchPolicy.kt:80-89`）：

```kotlin
internal fun shouldShowPortraitCover(
    isLoading: Boolean,
    isCurrentPage: Boolean,
    isPlayerReadyForThisVideo: Boolean,
    hasRenderedFirstFrame: Boolean
): Boolean {
    if (!isCurrentPage) return true
    // 当前播放页一律不显示静态封面，保持黑屏底色平滑过渡起播，避免进入时闪烁封面
    return false
}
```

播放器节点只在「当前页且播放器已就绪于此视频」时组合，且用 media3 三件套兜缓冲观感（`BP/…/pager/PortraitVideoPager.kt:2621, 2652-2661`）：

```kotlin
if (isCurrentPage && isPlayerReadyForThisVideo) { … key(currentPlayingBvid, bvid) { … AndroidView(factory = { ctx -> … basePlayerView.apply {
    playerViewRef = this
    player = exoPlayer
    useController = false
    keepScreenOn = shouldKeepPortraitPagerItemAwake
    resizeMode = portraitPagerResizeMode
    setKeepContentOnPlayerReset(true)
    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
} } …
```

当前页取流中再叠一个应用内指示器（`BP/…/pager/PortraitVideoPager.kt:2776-2781`）：

```kotlin
if (isLoading && isCurrentPage) {
    AdaptiveLoadingIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
}
```

**bbspace：封面页（拖动中）→ 深色 veil（等首帧）→ 首帧淡出。**

页身份按内容比，播放器页切换发生在「新流 ids 到达」瞬间（`BB/…/player/PortraitSwipePager.kt:50-60, 200-225`）；`setKeepContentOnPlayerReset(true)` 会让这一瞬间画面仍是上一支视频的最后一帧，因此用**粘滞**等待态 + 全屏 veil 盖住（`BB/…/player/PortraitSwipePager.kt:168-193`）：

```kotlin
var waitingKey by remember { mutableStateOf<String?>(null) }
LaunchedEffect(currentKey) {
    // 首次组合同样置位：锚点视频还没出帧时，一样该看到加载层而不是黑屏
    waitingKey = currentKey
}
LaunchedEffect(firstFrameRendered, playbackFailed) {
    if (firstFrameRendered || playbackFailed) waitingKey = null
}
val waitingFirstFrame = waitingKey != null
```

veil 是播放器 Box 的最后一个子节点、不带 pointerInput、深色渐变 + 34% 屏宽粒子、`Animatable` 淡出零重组（`BB/…/player/VideoPlayerPane.kt:756-766, 840-878`；时长 = `LocalAnimations.current.medium` = 200ms，`BB/core/designsystem/.../theme/Animation.kt:32-43`）。

**逐段对比（上滑一支新视频）：**

| 阶段 | BiliPai | bbspace |
|---|---|---|
| 拖动中 | 新页封面（Fit 居中）+ 当前页视频滑走 | 新页封面（PlayerPreview+Fit）+ 标题/UP 渐变条 + 当前页视频滑走（bbspace 信息更多） |
| 跨 snap 中点 | `resolvePortraitEarlyPlaybackPage` 立即绑页起播（`BP/…/pager/PortraitVideoLoadPolicy.kt:354-366`） | `currentPage` 变化即 `onSwipeTo` 起播（`BB/…/player/PortraitSwipePager.kt:106-125`，KDoc 自述同款思路） |
| 停稳、取流中 | 黑底 + media3 转圈（旧帧被 shutter 盖）+ AdaptiveLoadingIndicator | 封面卸载 → **硬切**深色 veil + 粒子（旧帧被 veil 盖） |
| 首帧到达 | 硬切出画 | veil 200ms 淡出后硬切出画 |

**「先出旧帧」问题两边都存在**（都开了 `setKeepContentOnPlayerReset(true)`：`BP/…/pager/PortraitVideoPager.kt:2658` / `BB/…/player/VideoPlayerPane.kt:474`），区别只在遮法：BiliPai 用系统转圈叠在旧帧上，bbspace 用设计过的 veil 全遮。**bbspace 多出来的一次「封面 → 暗屏」硬切是纯亏损**——用户刚在拖动中看过这张封面，它却突然消失换成一块深色（C9 修复记录在 `BB/…/player/VideoPlayerPane.kt:824-828`：当年正是因为 veil 里「另铺一张封面」造成二次闪烁才撤掉，但撤成纯深色又把连续性丢了）。正解是**交叉淡出**（见 T1）：封面不卸载，首帧到达时封面与 veil 同步淡出，视频「接住」封面。

### 2.2 信息层

**BiliPai 竖屏页信息架构**（`PortraitFullscreenOverlay`，默认可见——`portraitOverlayVisible` 初值 true，`BP/…/pager/PortraitVideoPager.kt:724`；点一下才整体收起，`BP/…/pager/PortraitVideoPager.kt:2414-2424`）：

- 顶部：返回 + 主界面 + 「N播放」+ 搜索（`BP/…/overlay/PortraitFullscreenOverlay.kt:546-630`）
- 右侧互动栏：点赞（**长按三连**，900ms 线性进度弧 + 震动，`BP/…/overlay/PortraitInteractionBar.kt:57-68, 105-169`）、投币、评论、收藏、分享，全部带计数（`BP/…/overlay/PortraitInteractionBar.kt:76-213`）
- 底部：头像 + @UP + 关注按钮 + 3 行标题 + 合集入口（`BP/…/overlay/PortraitFullscreenOverlay.kt:635-748`）；chips 行：时间 + 音质(Hi-Res/杜比徽章) + 画质 + 比例 + 倍速（`BP/…/overlay/PortraitFullscreenOverlay.kt:436-499`）；抖音式细进度条（2dp → 拖动 8dp，带缓冲进度与视频帧预览，`BP/…/overlay/PortraitProgressBar.kt:45-149`）；弹幕输入栏「发弹幕...」+ 弹幕开关 + 横屏旋转（`BP/…/overlay/PortraitBottomInputBar.kt:38-102`）
- 控件收起时**仍保留常驻细进度条**（`BP/…/overlay/PortraitFullscreenOverlay.kt:387-394`）
- 文字可读性靠顶部/底部渐变 scrim（`BP/…/overlay/PortraitFullscreenOverlay.kt:398-434`）

**bbspace 会话内信息架构**：

- 非当前页（封面页）：标题 2 行 + @作者，底部渐变条（`BB/…/player/PortraitSwipePager.kt:266-298`）——比 BiliPai 的裸封面多，是优势
- 当前页（播放器页）：`showCtrl` 初值 **false**（`BB/…/player/VideoPlayerPane.kt:431`），2s 自动隐藏（`BB/…/player/VideoPlayerPane.kt:989-1010`）。弹出后才有：顶栏（返回/首页/标题+时间元信息/弹幕胶囊/横屏胶囊/更多，`BB/…/player/VideoPlayerPane.kt:626-743`）与底部控制条（自绘进度条 + 播放/音频/画质/倍速/全屏，`BB/…/player/VideoPlayerPane.kt:1163-1187, 1281-1392`）
- **缺失**：点赞/投币/评论/收藏/分享入口、头像与关注按钮、播放量、搜索、弹幕输入栏、常驻进度条。互动能力在会话内完全不可达（`VideoPlaybackSheet` 只有播放设置与错误信息，`BB/…/player/VideoPlaybackSheet.kt:85, 273-400, 412-425`）

结论：bbspace 选择「纯沉浸」值得保留为核心体验，但**连互动的入口都没有**超出「沉浸」的合理范围——B 站官方竖屏连播本身就有右侧互动栏。建议走「默认可见 + 2s 自动隐藏」的中间态（T2）。

### 2.3 手势

**BiliPai 门控与实现**（均在 `BP/…/pager/PortraitVideoPager.kt` VideoPageItem 内）：

| 手势 | 实现 | 门控 |
|---|---|---|
| 单指上下滑 | VerticalPager 原生 | `userScrollEnabled = scale≤1.01 && !评论面板 && !UP预览`（`:1521-1527` + `BP/…/pager/PortraitPagerSwitchPolicy.kt:134-153`） |
| 左右滑 seek | `detectHorizontalDragGestures`，全屏宽 = 75% 时长（`:2547-2552`），拖动静止时显示**视频帧缩略图**（CompactSeekPreview，`:2805-2847`）或时间气泡（`:2848-2881`） | `scale≤1.01 && duration>0`（`:2525-2536`） |
| 双击 | 左 30% 后退 / 右 30% 前进 / 中间 暂停，反馈为 ±Ns 圆角块（`:2884-2914`；`BP/…/overlay/FullscreenDoubleTapPolicy.kt:18-48`） | `doubleTapSeekEnabled` 设置 + 播放中 |
| 长按 | 临时倍速（设置值，默认 2.0），循环箭头提示（`:2489-2507, 2916-2929`） | `scale≤1.01 && isCurrentPage`；离开页面必须恢复（`BP/…/pager/PortraitPagerSwitchPolicy.kt:155-164`） |
| 捏合 | 1–5x + 平移，放大即隐藏 overlay（`:2349-2402`） | 双指接管时单指状态机整体退出（`:2362-2374`） |
| 亮度/音量 | **竖屏 pager 没有**（垂直轴全让给 pager） | — |

**bbspace 门控与实现**（`BB/…/player/VideoGestureController.kt` 单指状态机 + `BB/…/player/PlayerZoomState.kt`）：

| 手势 | 实现 | 门控 |
|---|---|---|
| 单指上下滑 | pager 原生 | 会话内 `verticalDragPassthrough` 整体让位、全程不消费（`:257-261`，注释自述修复点）；非会话时左侧 20% 亮度 / 右侧 20% 音量（`:96-97, 263-269`） |
| 左右滑 seek | 灵敏度 0.6、±60s 上限（`:94-95, 282-290`），仅文字气泡「+30s」（`:360-371`） | 水平判定优先；拖动中不消费垂直 |
| 双击 | 开设置=点赞 + 爱心动效（`BB/…/player/VideoPlayerPane.kt:1074-1078, 889-921`）；否则暂停/播放 + 圆形图标提示（`:204-223, 373-403`） | `settingsState.playback.doubleTapLike` |
| 长按 | 临时倍速徽标（`:185-187, 405-417`） | 超时未动即触发；双指接入立即恢复（`:191-198`） |
| 捏合 | 1–5x + 锚点平移，回 1x 时 offsets 归零（`BB/…/player/PlayerZoomState.kt:60-81, 91-163`） | 放大态单指平移并 consume（`:138-155`），pager 因此不会误翻 |
| 亮度/音量 | 非会话路径有；会话内禁用 | `swipePagingActive`（`BB/…/player/VideoPlayerPane.kt:1096`） |

**结论**：冲突处理两边都做对了（bbspace 的「双指守卫退出单指状态机」与 BiliPai 同构；放大态都能阻止误翻页）。差异只在 seek 反馈的信息密度（帧预览 vs 文字）。另注意 bbspace 的 `VerticalPager` **没有传 `userScrollEnabled`**（`BB/…/player/PortraitSwipePager.kt:200`）——当前靠 pinchZoom consume 事件兜底，若未来会话内加入任何浮层（评论/UP 预览），需补上门控（BiliPai 在 `PortraitPagerSwitchPolicy.kt:144-153` 有现成判据）。

### 2.4 沉浸与系统栏

- **系统栏**：BiliPai 进场 `ensureEdgeToEdge` + 隐藏双栏（瞬态滑动显示），dispose 时按 spec 精确恢复（`BP/…/pager/PortraitVideoPager.kt:304-340`）；bbspace 全屏隐藏、dispose 恢复，并额外管理亮/暗图标（`BB/…/VideoScreen.kt:349-375`）。两边等价，bbspace 更细（ appearance 复位）。
- **方向**：BiliPai 竖屏 pager **从不写 requestedOrientation**（bbspace 注释自证，`BB/…/VideoScreen.kt:378-380`），横屏出口是 `onRotateToLandscape` → 带当前页封面跳横屏详情（`BP/feature/story/StoryScreen.kt:151-159`）。bbspace 会话锁 PORTRAIT + 手动横屏胶囊只对当前视频生效、ids 变化即收回（`BB/…/VideoScreen.kt:390-425`），语义与 BiliPai 一致且修复了「每次翻页转两次屏」的真机问题。
- **进出场动画**：bbspace 首页顶栏 → story 走 OPPO `OplusViewSeamless`（跟手、阻断、回弹；非 ColorOS 静默降级，`BB/…/seamless/OplusViewSeamlessCompat.kt:11-30, 100-133`；入口接线 `BB/app/.../navigation/AppNavHost.kt:295-306`），退出等系统返回动画再 finish（`BB/app/.../video/VideoDetailActivity.kt:243-269`）。BiliPai 的 Story 是应用内 Navigation3 目的地（`BP/navigation3/BiliPaiNavKey.kt:315`、`BP/AppNavigation.kt:3658-3664`），吃系统预测性返回；「竖屏直达」明确**不走** sharedBounds（全屏几何对不上，`BP/navigation/PortraitStoryNavigationPolicy.kt:14-16`）。
- **差距**：bbspace 的「详情页内联 → 全屏刷视频」是**瞬时状态切换**（`BB/…/VideoScreen.kt:458-500` 两个分支直接互换），BiliPai 卡片 → 详情至少有 CARD_SHELL morph。建议给这条路径补一个 200–280ms 的淡入 + 轻微缩放（复用 `LocalAnimations.morph` 280ms / iOS easeInOut，`BB/core/designsystem/.../theme/Animation.kt:17-22`）。

### 2.5 加载与空态

- **形态**：BiliPai = media3 `SHOW_BUFFERING_ALWAYS` 系统转圈 + Material `AdaptiveLoadingIndicator`（`BP/…/pager/PortraitVideoPager.kt:2660, 2776-2781`）；bbspace = 自研「光尘轨道」粒子（双层 22 颗粒子 + 扫光弧 + 光核，2.4s 周期，相位只在绘制期读、`withInfiniteAnimationFrameMillis` 联动系统动画缩放，`BB/core/designsystem/.../component/BiliParticleLoader.kt:22-63, 180-221`）。**bbspace 观感明显更好**，且封面页/播放器页两处同组件（`BB/…/player/PortraitSwipePager.kt:254-265`）。
- **阈值**：两边都是「立即显示、无最短时长」。bbspace veil 有 200ms 淡出但无最短展示期，快网下会「闪一下就没」。
- **失败/空态**：BiliPai story 入口有完整三态 + 重试（`BP/feature/story/StoryScreen.kt:98-119`）；bbspace 的失败路径是「veil 直接退」（`BB/…/player/PortraitSwipePager.kt:184, 190-192`），用户面对 keepContentOnPlayerReset 的**旧帧**且无重试入口，错误文本只在设置面板（`BB/…/player/VideoPlaybackSheet.kt:412-425, 747`）。队列耗尽时自然到头、无提示（`BB/…/player/PortraitSwipePager.kt:69-70`；`BB/…/swipe/PortraitSwipeQueue.kt:234-241`）。

### 2.6 无障碍与性能

- **内容描述**：bbspace 封面图 `contentDescription = entry.title`（`BB/…/player/PortraitSwipePager.kt:243`）优于 BiliPai 的 null（`BP/…/pager/PortraitVideoPager.kt:2762, 2770`）；BiliPai 图标描述覆盖更全（返回/主界面/搜索/菜单/暂停，`BP/…/overlay/PortraitFullscreenOverlay.kt:576-625`、`BP/…/pager/PortraitVideoPager.kt:2796-2797`），bbspace 主要图标也有（返回 `BB/…/player/VideoPlayerPane.kt:657`、播放 `:1139`、更多 `:737`、点赞 `:909`）。**共同缺口**：两侧自绘进度条都没有 Slider 语义（无 `progressBarRangeInfo`/状态描述），TalkBack 用户无法获知进度（`BB/…/player/VideoPlayerPane.kt:1417-1461`；`BP/…/overlay/PortraitProgressBar.kt:106-149`）。
- **触摸目标**：bbspace 中央播放键 72dp、进度条触摸区 32dp（`BB/…/player/VideoPlayerPane.kt:1126, 1436-1439`）；BiliPai 进度条触摸区由 layout policy 给足（`BP/…/overlay/PortraitProgressBar.kt:67-76`）。新增互动栏时须保 48dp 下限。
- **每帧成本**：bbspace 玻璃背板 `withFrameNanos` 逐帧重录**有消费者门控**（模糊关/材质 OFF/暂停时不 tick，`BB/…/player/VideoPlayerPane.kt:525-541`），粒子相位只在绘制期读（`BB/core/designsystem/.../component/BiliParticleLoader.kt:34-41`）——这两点是模范实现。BiliPai 侧 letterbox 环境光是**定时采样**而非逐帧（`BP/…/pager/PortraitVideoPager.kt:2320-2339`）。
- **稳定性措施**：BiliPai 有 stable lists 只追加（`BP/…/pager/PortraitVideoPager.kt:479-486`）、代际守卫（`BP/…/pager/PortraitPagerSwitchPolicy.kt:44-53`）、连播去重（`BP/…/pager/PortraitVideoPager.kt:902-905`）、baselineprofile 帧计时基准（`BP/baselineprofile/.../BiliPaiBottomPagerFrameTimingBenchmark.kt:15-31`）与 pager 视口回归测试（`BP/app/src/androidTest/.../PortraitVideoPagerUiRegressionTest.kt:23-60`）。bbspace 有源码不变式守卫 + 纯逻辑单测（`BB/feature/video/src/test/.../VideoLogicGuardTest.kt:367-710`、`BB/feature/video/src/test/.../swipe/PortraitSwipeQueueTest.kt`），缺 macrobenchmark（不建议补，见四-6）。

### 2.7 可配置项

| 能力 | BiliPai | bbspace |
|---|---|---|
| 仅推荐真竖屏 | 有（Beta，`BP/feature/settings/screen/PlaybackSettingsScreen.kt:2110-2127`，Story 消费 `BP/feature/story/StoryScreen.kt:56-58`） | 无（横竖屏混刷，`BB/…/swipe/PortraitSwipeQueue.kt:84-100` 不过滤） |
| 播完行为 | `PlaybackCompletionBehavior` 枚举 + 自动连播开关 + 外部列表连播（`BP/core/store/SettingsManager.kt:380, 1060-1065`；消费 `BP/…/pager/PortraitVideoPager.kt:861-939`） | 写死连播，与 repeatMode=One 的互斥仅存在于注释（`BB/…/player/PortraitSwipePager.kt:145-161`） |
| 预取开关 | `prefetchVideo` 决定预热 0–3 条（`BP/…/pager/PortraitVideoPager.kt:412-413` + `BP/…/pager/PortraitVideoLoadPolicy.kt:368-400`） | 无任何播放地址预热 |
| 直达入口 | 竖屏上滑进全屏 / 竖屏直达 / 启动进竖屏流（`BP/feature/settings/screen/PlaybackSettingsScreen.kt:2073-2146`） | 仅首页顶栏按钮（`BB/feature/home/.../HomeScreen.kt:336-390`） |
| 画质/倍速/双击 | 竖屏独立画质记忆 + 长按倍速及其提示样式 + 双击 seek 秒数（`BP/…/pager/PortraitVideoPager.kt:422-454, 1779-1811`） | 画质/倍速有，双击仅「点赞/暂停」二选一（`BB/core/model/.../player/PlayerSettingsModels.kt:113-128`） |

---

## 三、Top 5 体验优化项（按用户可感知收益排序）

### T1. 封面 → 首帧交叉淡出（替换「加载层 → 硬切」）

- **改哪**：`BB/feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt`（给 `VideoPlayerPane` 增传 `swipeCoverUrl: String?`，取 `entry.cover`，调用点 `:200-218`）；`BB/feature/video/.../player/VideoPlayerPane.kt` 的 `SwipeWaitVeil`（`:840-878`，扩展为「封面垫底 + 粒子 + 遮罩」三段式）。
- **交互细节与时长曲线**：
  1. 等首帧期间：AndroidView 之上铺**当页封面**（`BiliImageVariant.PlayerPreview` + `ContentScale.Fit`，与封面页同源同构图——`BB/…/player/PortraitSwipePager.kt:241-252` 已保证取景一致），封面上叠 0.35 黑遮罩 + 34% 屏宽粒子（遮罩保证浅色封面上的白粒子可读，参照 `:258` 的 0.45 既有做法）；
  2. 首帧到达（`waitingKey` 清空）：封面 180ms 淡出至 0、veil/粒子 200ms 淡出（沿用 `:848` 的 `LocalAnimations.current.medium`），两条动画同一起点、粒子稍早 40ms 结束，视频「接住」封面；
  3. 总过渡 ≤ 240ms；`LocalAnimations.current.reduceMotion` 时两条都退化为 120ms 纯 fade（`BB/core/designsystem/.../theme/Animation.kt:25-26, 48-62`）。
- **为什么不是回到 C9 前的旧版**：C9 修掉的是「veil 里另铺一张封面」导致的二次闪烁（`BB/…/player/VideoPlayerPane.kt:824-828`）。本方案里封面从拖动中起从未卸载、构图不变，用户感知是同一张图「渐渐变成视频」，不存在「又闪一下」。
- **风险**：① 旧帧从封面四边黑边透出——封面容器自身 `background(Color.Black)` 且 PlayerView 在其下层，可完全遮住；② 淡出期间弹幕/控件抢戏——封面层置于控件层**之下**、且不带 pointerInput（延续 `:756-764` 的既定契约）；③ 横屏视频 letterbox 与封面 Fit 构图一致性——已由 PlayerPreview 不裁切保证（`BB/core/common/.../media/BiliImageUrl.kt:25-29`）。
- **守卫/单测**：新增纯函数 `SwipeFirstFrameStagePolicy(waiting, firstFrameArrived, failed, reduceMotion) -> (veilAlpha, coverAlpha)`；`VideoLogicGuardTest` 加源码不变式：封面层必须在 AndroidView 之上、控件层之下，且不含 pointerInput/clickable（沿用 `:630` 对 veil 的断言风格）。
- **真机验收**：story 入口与详情全屏两条路径各上滑 10 次录屏逐帧检查：无「封面→暗屏」硬切、无旧帧闪现；限速 200kbps 下封面停留 >1s 时过渡仍连续。

### T2. 刷视频会话信息层补齐（互动入口 + 常驻进度 + 默认可见）

- **改哪**：`BB/feature/video/.../player/VideoPlayerPane.kt` 的 `VideoPlayerOverlay`（`:954-1189`）与顶部 `AnimatedVisibility`（`:628-743`）；建议新增 `PortraitSwipeChrome.kt` 放互动栏，避免 pane 继续膨胀。
- **交互细节**（按成本排序，可分批上）：
  1. **常驻细进度条**：`showCtrl` 收起时底部画 2dp 已播轨（复用 `PlayerProgressBar` 绘制与平滑外推 `:1464-1497`，值走 `viewModel.playbackProgress`）——对齐 `BP/…/overlay/PortraitFullscreenOverlay.kt:387-394`；
  2. **默认可见 + 自动隐藏**：`showCtrl` 初值改 true 或拆 `chromeVisible`，2s 无操作自动收起（复用 `:989-1010` 的 LaunchedEffect，把按键/滑动也作为重置信号）；
  3. **右侧互动栏**：点赞/投币/评论/收藏/分享 + 计数；点赞直接接既有 `viewModel.toggleLike()`（`:1074-1078`），评论入口打开既有评论区（半屏 sheet 或替换栈顶，二选一需产品定）；
  4. **头像 + @UP + 关注**：封面页已有标题/作者数据（`BB/…/player/PortraitSwipePager.kt:281-297`），播放页顶部标题位（`:670-690`）扩一行即可；关注 API 详情页已有实现可复用。
  显隐节奏沿用 A5 规格：fadeIn 200ms + 4dp 位移、fadeOut 150ms（`:631-633, 1168-1170`）。
- **风险**：① 信息层盖视频主体——用 BiliPai 的渐变 scrim + 信息宽度分数方案（`BP/…/overlay/PortraitFullscreenOverlay.kt:398-434, 277`）；② 等首帧期间不得画 chrome（与 veil/封面叠层冲突）——chrome 可见性再与 `swipeWaitingFirstFrame` 相与；③ 互动栏误触翻页——互动栏容器消费事件，或给 `VerticalPager` 补 `userScrollEnabled` 门控（bbspace 目前没传，`BB/…/player/PortraitSwipePager.kt:200`；判据可移植 `BP/…/pager/PortraitPagerSwitchPolicy.kt:134-153`）。
- **守卫/单测**：chrome 显隐状态机单测（默认可见 → 2s 收起 → 交互重置）；互动按钮触摸目标 ≥48dp 的源码/lint 断言。
- **真机验收**：进 session 即见标题/UP/进度；2s 后仅剩进度条；点右侧点赞即时变色；上滑时 chrome 不遮挡新页封面；TalkBack 能读到标题与按钮。

### T3. 等首帧 veil：最短展示时长 + 失败重试态

- **改哪**：`BB/…/player/PortraitSwipePager.kt:168-193`（`waitingKey` 粘滞判据）+ `BB/…/player/VideoPlayerPane.kt:840-878`（veil 内容）。
- **交互细节与时长曲线**：① veil 最短展示 400ms（首帧早到也补足再淡出，淡出仍 200ms；只补足、不新增等待）；② 失败不再直接清 `waitingKey`——改为 veil 内容切换为「错误图标 + 一句文案 + 重试钮」，重试 = 对当前页 entry 重发 `onSwipeTo`（`:86`）；重试钮是 veil 内唯一可点区域，其余区域继续保持不消费触摸（`:764` 注释的契约）。
- **风险**：① 错误态要能退出——返回手势必须放行（veil 无 pointerInput，`BackHandler` 在 `BB/…/VideoScreen.kt:337-341`）；② minVisible 让快网多等 ~200–400ms——用「已等待时长」判据缓解；③ 重试连点——按钮去抖。
- **守卫/单测**：`SwipeWaitPolicy(firstFrameAtMs, failedAtMs, minVisibleMs) -> veil 状态序列` 单测；`VideoLogicGuardTest` 加不变式：重试钮存在、veil 其余部分无 pointerInput（续 `:630` 风格）。
- **真机验收**：飞行模式上滑 → 看到错误态 + 重试；恢复网络点重试 → 正常起播；WiFi 下 veil 不短于 400ms；重试失败 3 次后按钮仍可点。

### T4. 下一段预热预取（playurl + 媒体头）

- **改哪**：`BB/…/player/PortraitSwipePager.kt:106-125` 的 snapshotFlow（增读 `currentPageOffsetFraction`）+ `BB/feature/video/.../VideoViewModel.kt`（在 `loadMoreSwipeQueue` `:496-521` 旁加 `prefetchSwipeEntry(entry)`：取流 + 写缓存，不切播放）。
- **交互细节**：`|offset| ≥ 0.25` 且目标页未预热过 → 预热下一条（判据移植 `BP/…/pager/PortraitVideoLoadPolicy.kt:331-352` 的 0.25 阈值；只预 1 条，不学 BiliPai 的 3 条——bbspace 队列来自 relate，命中率低）；WiFi 默认开、蜂窝默认关（设置项见 T5）。
- **收益曲线**：把「停稳 → 首帧」从一次完整取流（约 300–800ms）压到缓存命中（<200ms），直接缩短 veil/封面停留——这是 T1/T3 之外「等待感」的总闸门。
- **风险**：① 预热浪费（用户回滑）——`Set` 去重 + 只预一条；② 接口频控——复用 `loadingMoreSwipe` 同款防抖 flag（`:497`）。
- **守卫/单测**：`shouldPrefetchSwipeEntry(offsetFraction, alreadyPrefetched, isWifi, enabled)` 纯函数单测；ViewModel 用 fake repository 验幂等与失败回滚（失败要从 set 中移除，对照 `BP/…/pager/PortraitVideoPager.kt:1269-1272`）。
- **真机验收**：限速 300kbps 连续上滑 5 次，对比改前/改后 logcat `swipe:`/`bind:` 行的时间差与 veil 停留。

### T5. 设置项补全 + 自动连播守卫

- **改哪**：`BB/core/model/.../player/PlayerSettingsModels.kt:113-128`、`BB/core/settings/.../AppSettings.kt:607-609, 732-740`、`BB/feature/video/.../player/VideoPlaybackSheet.kt:323-400`（加两行开关）+ `BB/…/player/PortraitSwipePager.kt:145-161`。
- **加什么**：① 「刷视频仅真竖屏」过滤（消费点 `swipeEntriesFromRelates`，`BB/…/swipe/PortraitSwipeQueue.kt:84-100`；判据可参考 bbspace 自己的 `isPortraitVideo` 计算，`BB/…/player/PortraitSwipePager.kt:196-199`）；② 播完行为（停/连播，显性化与 `repeatMode=One` 的互斥）；③ 预取开关（T4 的消费端）。参照 `BP/feature/settings/screen/PlaybackSettingsScreen.kt:2073-2146` 的文案粒度。
- **连播守卫**：bbspace `STATE_ENDED → animateScrollToPage(next)`（`:152-156`）缺 BiliPai 的 `lastAutoAdvancedBvid` 去重（`BP/…/pager/PortraitVideoPager.kt:902-905`）——下一尾视频加载失败、播放器停在 ENDED 时监听器可能重复触发连跳两页。补 `lastAdvancedKey`。
- **风险**：设置膨胀——只加 2–3 个开关，预取开关并入既有「省流」语义区。
- **守卫/单测**：AppSettings 读写往返测试；`shouldAutoAdvanceSwipe(repeatMode, ended, lastAdvancedKey, currentKey)` 单测；过滤判据单测（横屏/未知尺寸/竖屏三态）。
- **真机验收**：设置页出现新开关；repeatMode=One 播完不连播；制造「下一页加载失败」验证只跳一页并停住。

---

## 四、明确不建议做的项

1. **不做 seek 视频帧缩略图（videoshot）**。BiliPai 有 `CompactSeekPreview` + videoshot 数据（`BP/…/pager/PortraitVideoPager.kt:2806-2847`），但 bbspace 全仓无 videoshot 字段/模型（检索 0 命中），补齐需服务端字段 + 雪碧图按帧解码，成本高；竖屏连播以短内容为主、seek 频率低。保留现有文字气泡（`BB/…/player/VideoGestureController.kt:360-371`）+ 进度条拖动气泡（`BB/…/player/VideoPlayerPane.kt:1597-1609`）即可。
2. **不给竖屏连播会话加亮度/音量手势**。BiliPai 竖屏 pager 根本没有垂直亮度/音量（垂直轴全让给 pager）；bbspace 已在会话内禁用（`BB/…/player/VideoGestureController.kt:257-261`）。再引入只会和翻页抢同一根手指。
3. **不做双击左右分区 seek**。与 `doubleTapLike` 设置位冲突（`BB/…/player/VideoPlaybackSheet.kt:391-393`），官方竖屏语义是双击点赞；三个候选动作挤同一交互位，收益不抵复杂度。
4. **不加页码/队列进度指示器**。连播流是滚动追加、无总边界（`BB/…/swipe/PortraitSwipeQueue.kt:191-227`），页码没有信息量；BiliPai 也没有。真需要「到底反馈」的话，一个轻量「刷到底啦」 toast 比页码便宜得多（可并入 T5  backlog）。
5. **不回退「播放器唯一 + isCurrentVideo」架构**。bbspace 当前实现是相对上游的改进（BiliPai 每页持 PlayerView 且 `key(currentPlayingBvid, bvid)` 换视频即重建 surface，`BP/…/pager/PortraitVideoPager.kt:2627`；bbspace 只有一份播放器节点、销毁/重建都发生在用户看不到的页上，`BB/…/player/PortraitSwipePager.kt:50-55`）。任何「对齐上游」式的重构都不要碰这里。
6. **不引入 baselineprofile / macrobenchmark 全套**。BiliPai 的 `baselineprofile` 是体系化工程（`BP/baselineprofile/.../BiliPaiBottomPagerFrameTimingBenchmark.kt:15-31` 等 9 个基准），需要连设备采集 compilation 曲线；bbspace 已有 `FrameBudgetLintTest` 口径的源码守卫 + 纯逻辑单测 + `docs/performance-baseline.md`，边际收益低于成本。至多补一个轻量 Compose UI 回归测试（参照 `BP/app/src/androidTest/.../PortraitVideoPagerUiRegressionTest.kt:23-60` 的 60 行规模）。

---

## 附录 · 主要证据文件索引

**BiliPai**（`_ref/BiliPai/app/src/main/java/com/android/purebilibili/`）
- `feature/video/ui/pager/PortraitVideoPager.kt`（4087 行，pager 主体： immersive/预取/手势/封面/播放器）
- `feature/video/ui/pager/PortraitPagerSwitchPolicy.kt`（533 行，封面/手势门控纯策略）
- `feature/video/ui/pager/PortraitVideoLoadPolicy.kt`（448 行，预热与画质策略）
- `feature/video/ui/overlay/PortraitFullscreenOverlay.kt`（748 行，信息层总装）
- `feature/video/ui/overlay/PortraitInteractionBar.kt` / `PortraitProgressBar.kt` / `PortraitBottomInputBar.kt` / `FullscreenDoubleTapPolicy.kt`
- `feature/story/StoryScreen.kt`（188 行，story 入口三态）
- `feature/settings/screen/PlaybackSettingsScreen.kt:2073-2146`（竖屏连播设置）
- `baselineprofile/src/main/kotlin/.../BiliPaiBottomPagerFrameTimingBenchmark.kt:15-31`
- `app/src/androidTest/.../PortraitVideoPagerUiRegressionTest.kt:23-60`

**bbspace**（`_ref/bbspace/`）
- `feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt`（300 行，pager 主体）
- `feature/video/.../player/VideoPlayerPane.kt`（1797 行，播放器浮层/veil/手势接线）
- `feature/video/.../player/VideoGestureController.kt`（448 行，单指手势状态机）
- `feature/video/.../player/PlayerZoomState.kt`（163 行，捏合缩放）
- `feature/video/.../swipe/PortraitSwipeQueue.kt`（245 行，队列纯逻辑）
- `feature/video/.../VideoScreen.kt`（会话/系统栏/方向接线）
- `feature/video/.../VideoViewModel.kt:127-133, 413-546`（会话与补货）
- `core/designsystem/.../component/BiliParticleLoader.kt`（221 行）
- `core/designsystem/.../seamless/OplusViewSeamlessCompat.kt`（167 行）
- `core/model/.../player/PlayerSettingsModels.kt:113-128` + `core/settings/.../AppSettings.kt:607-609`
- `feature/video/src/test/.../VideoLogicGuardTest.kt:367-710` + `feature/video/src/test/.../swipe/PortraitSwipeQueueTest.kt`
