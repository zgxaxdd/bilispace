# U1 · BiliPai v0.2.4 增量考古与 bbspace 同步评估

> **范围**：`34d189620..6e6bdce36`（8 提交 / 395 文件 / +20328 −2950）。基线 `34d189620`
> 已用 `git merge-base --is-ancestor 34d189620 6e6bdce36`（退出码 0）复核为祖先，与 bbspace
> 同步记录里的上一基线**同一提交**。
>
> **仓库/分支**：上游旧 `_ref/BiliPai`@`main`=`34d189620`；上游新 `_ref/BiliPai-024`@`origin/main`=`6e6bdce36`；
> 下游 `_ref/bbspace`@`build/agp9-migration`（**104 项未提交改动，本次全程只读，未执行任何写操作**）。
>
> **纪律**：所有行号已由我本人读源码/`git show` 复核。`【事实】`＝可从仓库直接验证；
> `【需决策】`＝需要人拍板。**无真机**（本机 `adb` 不在 PATH，与任务书前提一致）：
> 凡涉及"能不能用/快不快/省多少"的行为验证，一律标注**待真机**，本报告不写任何帧率/内存/耗时数字。
>
> **断言边界**：`git show --stat` 输出在本机曾出现路径段被省略、以及把**已有**路径
> `app/src/main/.../AppHingeSafeContent.kt` 显示成 `new file` 的情况。故凡关键的"新增/已有"
> 判定，我用 `Test-Path` + `git log -- <路径>` 二次复核，未复核的一律写"需更细读"。

---

## ① CHANGELOG v0.2.4 用户可见变化逐条

来源：`BiliPai-024\CHANGELOG.md:1-39`（`## v0.2.4 (2026-10-01)` 段，由 `2fc35a71d` 与 `6cac80f45` 改写而成）。

| # | 原文（节选） | 中文说明 | 上游落点（提交） |
|---|---|---|---|
| 1 | `[更改] 版本号更新为 0.2.4，versionCode 更新为 422` | 版本号；随后 3 个提交把 versionCode 推到 423/424/425 | `6cac80f45`、`a83598622`、`c1ff890df`、`483099f98` |
| 2 | `[添加] 新增扫码授权…支持新旧二维码格式与相册识码` | 用本机已登录账号，直接确认 B 站**网页端 / TV 端**的登录请求；授权前显示账号、登录地点、异地风险提示 | `6cac80f45` |
| 3 | `[添加] BiliPai 设备间传输全面升级…可迁移应用设置、JSON 规则插件、JS 插件、皮肤包及外部 Kotlin 插件` | 设备间迁移从"只搬登录会话"扩到"设置 + 插件 + 皮肤"，大包自动分块多码连传，显示进度与逐项导入结果 | `6cac80f45`、`4a79d46bb` |
| 4 | `[添加] 新增用户协议与隐私政策…设置、登录页与扫码授权页均提供查看入口` | 首次启动强制阅读并确认的用户协议 / 隐私政策门槛 | `6cac80f45` |
| 5 | `[添加] 竖屏全屏播放器支持展示联合创作信息…可展开查看完整成员、创作分工并进入对应主页或关注` | 竖屏全屏播放器头部显示"等 N 人联合创作"，点头像展开成员 Sheet（分工 / 进主页 / 关注） | `6cac80f45` |
| 6 | `[适配] 完善折叠屏与铰链设备布局…减少内容跨折痕、遮挡或布局异常` | 折叠屏/铰链整体适配：安全区几何、弹窗与 Sheet、直播分屏、首页/搜索/动态流、番剧与普通视频窗格 | `6cac80f45`（F1–F6）+ `a83598622` |
| 7 | `[更改] 首页双指缩放网格改为围绕手指实际捏合位置缩放…不再固定以屏幕中心作为缩放中心` | 首页信息流网格捏合缩放改为**围绕手指捏合中心**，列数切换与松手回弹更连续 | `6cac80f45` |
| 8 | `[更改] 听视频悬浮播放条进一步融入沉浸背景，根据当前封面氛围色调整外观；补充长按隐藏交互` | 听视频悬浮播放条按封面氛围色取色、支持长按隐藏、进出播放器过渡衔接 | `6cac80f45`、`4a79d46bb`（`AudioNowPlayingBar.kt` +93、`AudioNowPlayingSession.kt`） |
| 9 | `[修复] 图片预览的缩放与返回动画重新梳理…尺寸跳变、位置偏移和动画不连贯` | 图片预览（动态图片）缩放/退出/拖拽关闭的动画重排 | `6cac80f45`、`4a79d46bb` |
| 10 | `[修复] 视频卡片预测性返回进一步完善手势跟随、页面快照和返回时间线` | 视频卡片预测性返回：手势跟随状态、原生快照、返回时间线 | `6cac80f45`、`483099f98` |
| 11 | `[修复] 播放器控制栏的弹幕开关、弹幕设置及输入区域重新整理` | 播放器底栏弹幕三件套的布局与交互一致性 | `6cac80f45`（`BottomControlBar.kt` 638 行改动） |
| 12 | `[修复] BGM 详情卡片改为根据内容自适应高度` | BGM 详情卡不再因长标题/歌手名挤压底部操作入口 | `6cac80f45` |
| 13 | `[更改] 公开视频笔记中的用户等级改为显示对应等级徽章，并支持硬核会员等级样式` | 视频笔记里的等级由数字改为徽章，含硬核会员样式 | `6cac80f45`（`VideoNoteResponse.kt` 增 `is_senior_member`） |
| 14 | `[修复] 通知栏媒体控制、播放状态与音频会话同步逻辑` | 听视频场景下通知栏/音频会话的播放态一致化 | `6cac80f45`、`4a79d46bb` |
| 15 | `[修复] 弹幕滚动、顶部与底部弹幕统一使用稳定的渲染时钟` | 弹幕三类渲染线统一到暂停感知的 `RenderClock`，消除系统时间跳变导致的弹幕跳动 | `6cac80f45` |
| 16 | `[更改] 相册二维码识别与扫码授权、设备传输共用统一识别流程` | 相机权限被拒时也能从相册选图识码（授权页与传输共用） | `6cac80f45`、`4a79d46bb` |
| 17 | `[更改] 设置分享支持直接接收设备传输中的配置数据；关于页面官方渠道区域重新排版` | 设置分享服务支持内存内导入会话；"关于"页官方渠道按钮改两行避免文字截断 | `6cac80f45`、`4a79d46bb` |

**最高信噪比结论**：v0.2.4 的用户可见内容里，**只有第 5（联合创作）、第 6（折叠屏）、第 7（捏合中心）、
第 15（弹幕时钟）四项在 bbspace 有对应物或明确缺口**；第 2/3/4/16/17 依赖 BiliPai 独有的
扫码授权 / 设备迁移 / 协议门槛设施，bbspace 无对应物（见 ⑤）。

### CHANGELOG 里 v0.2.4 段之后的三次 versionCode 递增（CHANGELOG 未覆盖）

【事实】`6cac80f45` 之后的三个提交只 bump versionCode，**没有再写 CHANGELOG 条目**：

| 提交 | versionCode | 用户可见变化（由 commit message + diff 得出） |
|---|---|---|
| `a83598622` | 423 | 折叠屏软折痕不再把信息流钳进单侧 pane；视频详情 tab 条溢出时可横向滚动并自动滚入选中项 |
| `c1ff890df` | 424 | 修上一条 tab 条的裁切副作用；后台播放恢复（换实例后的优化状态串味）；大型播放器组件拆成本地 composable lambda 规避 DEX 调用寄存器溢出 |
| `483099f98` | 425 | 悬浮视频卡"松手即落定"的返回完成判据；`miuix-navigation` 由 Maven 依赖改为源码模块 |

---

## ② 架构变更性质判定：纯搬运 vs 行为变化

对象：`30daebd0b Extract shared data, playback and design modules; add TV app`（164 文件 / +2810 −527）。

### 2.1 【事实】模块划分结论

对 `30daebd0b` 逐文件做 `git show --numstat -M`，**`0 0` 行 = 纯搬运（仅 import/包名改写）**，
非 `0 0` 行 = 该提交对该文件有真实内容改动。同时用 `git log --follow -- <新路径>` 复核搬运来源。

| 模块 | 本提交内的性质 | 证据 |
|---|---|---|
| `core-data/` | **新建** | `core-data/build.gradle.kts` 新增 30 行；`ApiClient.kt`、`data/model/response/*`（0/0 计数）、`data/repository/*` 大批 `{app => core-data}` 搬运 |
| `core-player/` | **新建** | `core-player/build.gradle.kts` 新增 26 行；`PlaybackProgressManager.kt`、`dash/AdaptiveDashPlaybackSource.kt`、`dash/LocalDashManifestBuilder.kt`、`policy/PlaybackQualityMode.kt` 均为 `{app/... => core-player/...}` 且 0/0 |
| `design-tokens/` | **新建** | `design-tokens/build.gradle.kts` 新增 21 行；`Color.kt` 0/0 搬入 |
| `design-system/` | **已存在，本提交只加依赖** | `design-system/build.gradle.kts` 仅 +1 行；其源文件不在本提交改动清单 |
| `danmaku-engine/` | **已存在，本提交无实质改动** | 源文件只在 `6cac80f45` 出现（9 文件） |
| `miuix-navigation/` | **本提交尚无；由 `483099f98` 建立** | `483099f98` 新增 4502 行、38 文件，并把 `app/build.gradle.kts` 的 `libs.miuix.navigation` 换成 `project(":miuix-navigation")` |
| `app-tv/` | **新建应用** | 22 个新文件、build.gradle.kts 60 行、`settings.gradle.kts` +4 |

`settings.gradle.kts` 现含：`app / app-tv / core-data / core-player / baselineprofile /
settings-core / network-core / plugin-sdk / design-system / design-tokens / dolby-ffmpeg-decoder /
danmaku-engine / miuix-navigation`。

### 2.2 【事实】纯搬运清单（bbspace 应跳过）

以下文件在 `30daebd0b` 中 `numstat` 为 `0 0`，即**只换目录、只改 import，无行为变化**：

`core-data`：`AppSignUtils.kt`、`NetworkProxyStore.kt`、`SessionStorageCipher.kt`、`HtmlEntityUtils.kt`、
`IdUtils.kt`、`SelectionUtils.kt`、`VideoQuality.kt`、`data/model/response/` 下约 40 个模型
（`AiSummaryResponse` `AicuModels` `ArticleModels` `AudioModels` `BangumiIndexFilterPolicy` `BangumiModels`
`BangumiReviewModels` `CaptchaModels` `DanmakuThumbupResponse` `DynamicModels` `EmoteModels` `FavoriteModels`
`HistoryModels` `InteractVideoResponse` `IpLocationResponse` `LikedVideosModels` `LiveDanmuInfoResponse`
`LiveEmoticonModels` `LiveFeedModels` `LiveModels` `LoginModels` `MessageResponse` `OnlineResponse` `PbpModels`
`PersonalFavoriteModels` `PlayerInfoResponse` `RelatedResponse` `ResponseModels` `SendDanmakuResponse`
`SpaceModels` `SplashModels` `SponsorBlockModels` `StoryModels` `UnescapedStringSerializer` `UserModels`
`VideoDetailResponse` `VideoNoteResponse` `VideoshotResponse`）。

`core-player`：`PlaybackProgressManager.kt`、`dash/AdaptiveDashPlaybackSource.kt`、
`dash/LocalDashManifestBuilder.kt`、`policy/PlaybackQualityMode.kt`。

`design-tokens`：`core/theme/Color.kt`。

→ **结论：以上全部跳过**（bbspace 已有 `:core:model` `:core:playback` `:core:designsystem`，
搬运这类文件只产生 import 迁移成本、零行为收益；且 bbspace 的"设计令牌棘轮"测试按路径登记，
搬动会连带改一批结构守卫）。

### 2.3 【事实】伴随行为变化的清单（**这才是可同步的部分**）

**A. 从 `app` 抽成新 API 的"共享出口"（新文件，非搬运）**

| 新文件 | 行数 | 职责 |
|---|---|---|
| `core-data/.../core/network/CoreNetworkRuntime.kt` | 48 | 网络层运行时配置注入（debug / 硬编码 DNS 兜底开关 / 日志 / 错误上报 / 隐私模式回调） |
| `core-data/.../data/repository/SessionRepository.kt` | 73 | 会话持久化 |
| `core-data/.../data/repository/SharedContentRepository.kt` | 63 | 共享内容读取（详情 / 推荐流） |
| `core-data/.../data/repository/PlaybackStreamDataSource.kt` | 35 | playurl 取流参数与签名（把 `VideoRepository` 内联的一段搬出来） |
| `core-data/.../data/session/BiliPaiSessionBundle.kt` | 15 | 会话包 |
| `core-data/.../data/repository/ArticleHistoryReportFields.kt` | 15 | 文章历史上报字段 |
| `core-player/.../core/player/SharedPlaybackRepository.kt` | 72 | 共享播放仓库 |
| `core-player/.../core/player/SharedPlaybackSession.kt` | 253 | **共享播放会话**（TV 与手机共用） |
| `core-player/.../core/player/PlaybackResumePolicy.kt` | 9 | 纯函数续播决策（见 ④ R3） |

**B. 既有文件的真实内容改动**

| 文件 | numstat | 性质 |
|---|---|---|
| `app/.../data/repository/VideoRepository.kt` | +35 −114 | 主体改为**委托**：`getVideoInfo` → `SharedContentRepository.detail(...)`；心跳上报 → `HistoryRepository.reportPlayback(...)`；app playurl 参数拼装 → `PlaybackStreamDataSource.appPlayUrl(...)`；另加 8 处 `requireNotNull(response.data)` |
| `core-data/.../core/network/ApiClient.kt` | +52 −50 | 抽 `CoreNetworkConfig`、`buildAppProxySelector`、`buildPlaybackOkHttpClient`（播放客户端强制 `Proxy.NO_PROXY`）；其余为 import 改写 |
| `core-data/.../core/network/WbiKeyManager.kt`、`core/store/TokenManager.kt` | +11 −9 / +7 −6 | 依赖注入化 |
| `app/.../data/repository/{Bangumi,BlockedUp,Comment,Danmaku,Live,Message,Story,Favorite,History,Search,WatchLater}Repository.kt` | 各 +2…+35 | 多为 `requireNotNull` 化 / 改为委托新共享出口 |
| `app/.../{BangumiPlayerContent,BangumiDetailScreen,SpaceScreen,VideoPlaybackViewModel,…}.kt` | 各 +1…+28 | import 路径改写 + 少量编译修正 |
| `app/.../core/util/ScreenUtils.kt` | −33 | 删除，能力移入 design-tokens/自适应工具 |
| `app/.../feature/video/playback/policy/AdaptiveDashTrackPolicy.kt` | +2 −0 | 仅新增一行 import（`core.player.policy.PlaybackQualityMode`） |

→ **结论（回答问题 2）**：`30daebd0b` **不是纯搬运，是"搬运 + 抽共享层"的混合体**。
但它的"行为变化"几乎全是**架构性副作用**（委托化、`requireNotNull`、配置注入），**没有一条是
面向用户的功能修复**。对 bbspace 而言：**模块重构整体跳过**；只有 2.3-A 里那 3 个能脱离上游
架构独立成立的纯函数/工具值得摘出来（见 ④ R3/R4 与 ⑤ S1）。

---

## ③ 8 提交逐提交分类表

约定：**bbspace 对应实现**列写实际文件路径（已 `Read`/`grep` 复核）；
"无对应物"＝我按语义检索确认不存在；"无对应 UI"＝数据存在但无渲染点。

### 3.1 `6cac80f45` Release 0.2.4（118 文件 / +5730 −1631）

| 用户可见变化 | 上游文件 | bbspace 是否有对应实现 | 结论 |
|---|---|---|---|
| 扫码授权（网页/TV） | `core/network/ApiClient.kt`、`QrAuthorizationClient.kt`、`AppSignUtils.kt`、`feature/login/OfficialQrAuthorization*`（3 个新文件）、`BiliPaiQrDecoder.kt`、`BilibiliLoginQr.kt`、`TvQrConfirmationPolicy.kt` | **无**（bbspace 只有常规扫码登录，无"确认他人登录请求"概念） | **跳过** |
| 设备间传输（设置/插件/皮肤 + 分块） | `feature/login/BiliPaiTransfer*`（Session 107/Content 229/Crypto 59/Scanner 211）、`QrTransferModels.kt`、`feature/settings/share/SettingsShareService.kt` | **无设备迁移功能** | **跳过** |
| 用户协议与隐私政策门槛 | `feature/agreement/UserAgreementGate.kt:184`、`UserAgreementStore.kt:25`、`UserAgreementText.kt:77` | **无**（全仓无 agreement 相关文件） | **需决策**（见 ⑤ S4） |
| 竖屏全屏联合创作 | `feature/video/ui/overlay/PortraitFullscreenOverlay.kt`（+179）、`VideoInfoSection.kt` | **数据有、UI 无**：`VideoDetail.kt:14` 与 `VideoDetailRepository.kt:162-221` 已解析 `staffs`，但全仓仅此 2 处引用，**无任何渲染** | **可同步**（见 ④ R1） |
| 折叠屏/铰链安全区 F1–F6 | `core/util/HingeLayoutPolicy.kt`(+53)、`core/ui/adaptive/AppHingeSafeContent.kt`(+52)、`AppHingePaneLayout.kt`(+31)、`HingeSafeOverlayRegionsPolicy.kt`(+45)、`core/ui/WindowRegionLayout.kt`(+60)、`HingeSafeOverlayHost.kt`(+38)、`core/ui/SplitLayout.kt`(−77 重写)、`core/ui/AdaptiveDialogComponents.kt`、`AppSheetComponents.kt`、`LiveRoomLayoutPolicy.kt`、`TabletVideoLayout.kt`、`BangumiPlayerScreen.kt` | **零对应物**：全仓 `FoldingFeature` / `isSeparating` / `layoutHinges` / `HingeLayoutPolicy` **均无命中**（bbspace 用了 `currentWindowAdaptiveInfoV2`，但只读宽度/尺寸等级，从不读 `foldingFeature`） | **需决策**（大件，见 ④ R2 / ⑤ S5） |
| 首页捏合围绕手指中心 | `feature/home/HomeFeedPinchZoomPolicy.kt`(+102)、`HomeFeedGridPolicy.kt`(−93)、`HomeScreen.kt`、`HomeCategoryPage.kt` | **无信息流捏合缩放功能**（`PlayerZoomState.kt:50` 的 `applyTransform(centroid)` 是**视频播放器**的双指缩放，不是信息流网格） | **跳过**（无对应物） |
| 听视频悬浮条氛围色 / 长按隐藏 | `feature/audio/screen/AudioNowPlayingBar.kt`(+93)、`AudioNowPlayingSession.kt` | **已复核：无对应物**。`:feature:listen` 只有 `ListenNavigation.kt`(`navigateToListenDetail`/`listenDetailScreen`)、`ListenDetailScreen.kt`(`:48`，含私有 `ListenProgress:202`/`formatTime:253`)、`ListenDetailViewModel.kt`(`:68 load` / `:130 togglePlayPause` / `:138 seekTo`) —— **没有"悬浮播放条"这一层** | **跳过**（无对应物） |
| 图片预览缩放/返回动画 | `feature/dynamic/components/ImagePreviewDialog.kt`(±269)、`ZoomableImage.kt`(84)、`ZoomableImageScalePolicy.kt`(24 新)、`ImagePreviewTransitionPolicy.kt` | `feature/dynamic` 有图片预览；bbspace 另有 `core:designsystem/component/PreviewImageGrid.kt:262` 走 `transformable` | **需决策**（有对应物，但属观感类，无设备不可判；见 ④ R8） |
| 视频卡片预测性返回 | `navigation3/predictiveback/VideoCardGestureFollowState.kt`(206 新)、`MiuixVideoCardNavTransition.kt`(±250)、`core/ui/transition/VideoCardSnapshotSourceExclusion.kt`、`VideoCardReturnTimeline.kt`、`VideoCardNativeSnapshot.kt` | **无原生卡片快照设施**：全仓 `CardPositionManager` / `captureNativeVideoCard*` / `NativeSnapshot` 零命中 | **跳过**（依赖 miuix 导航 + 原生快照层，见 ⑤ S2） |
| 播放器控制栏弹幕三件套 | `feature/video/ui/overlay/BottomControlBar.kt`(±638) | 有控制栏（`VideoPlayerPane.kt` 内 `PlayerCtrlBar.kt:2178`）但结构完全不同 | **需更细读** |
| BGM 详情卡自适应高度 | `feature/video/ui/section/*`（配 `BgmDetailCardUiRegressionTest.kt`） | **无 BGM 详情卡**（全仓 `Bgm` 零命中；bbspace 只显示"背景音乐"文本） | **跳过**（无对应物） |
| 视频笔记等级徽章 / 硬核会员 | `data/model/response/VideoNoteResponse.kt`(+4)、`VideoNoteSection.kt`(+31) | **已复核：无对应物（且同名不同义）**。bbspace 的 `note` 是**空间备注**（`feature/space/note/SpaceNoteSection.kt:35 SpaceNoteTitleButton`、`SpaceNoteDb.kt:20` 表 `space_note`），与"视频笔记"无关；全仓无 `VideoNote*` | **跳过**（无对应物） |
| 通知栏媒体控制与音频会话同步 | `AudioNowPlayingSession.kt`、`BottomControlBar.kt`、`MusicPlayerContent.kt`、`AudioNowPlayingBar.kt` | **设施在**：`app/playback/PlaybackService.kt:46 mediaSession`、`:140 MediaSession.Builder`、`:149 setMediaSessionToken`、`:324` 注释提 `MediaStyle` 大图标密度档；另有 `app/playback/capsule/LinkedNowPlayingRow`(`CapsuleIndicator.kt:85`)、`LinkedBottomDock.kt:150-173` | **需更细读**（设施存在，但需逐分支比对上/下游的播放态同步语义才谈得上同步） |
| 弹幕渲染时钟统一 | 9 文件：`RenderClock.kt`(28 新)、`DanmakuController.kt`(+8)、`DrawItem.kt`(+3)、`BaseRenderLine.kt`(+12)、`ScrollLine.kt`、`TopCenterLine.kt`、`BottomCenterLine.kt`、`ReverseScrollLayer.kt` | bbspace 弹幕走**另一个仓库/另一套代码** `master.flame.danmaku`（你的 fork `_ref/DanmakuFlameMaster`，Java）；上游是 `com.bytedance.danmaku.render.engine`（Kotlin） | **跳过（同缺陷存在但补丁不能直接用）**，见 ④ R6 / ⑤ S3 |
| 相册识码共用流程 | `BiliPaiQrDecoder.kt` | 与扫码授权同命 | **跳过** |
| 应用内 API 客户端抽 `CoreNetworkConfig` / 播放强制 NO_PROXY | `core/network/ApiClient.kt`、`app/PureApplication.kt` | bbspace 网络栈是 `:infra:network-http` + 自有 OkHttp 构建 | **跳过**（架构不同） |

### 3.2 `2fc35a71d` Update CHANGELOG.md（1 文件）

| 用户可见变化 | 上游文件 | bbspace | 结论 |
|---|---|---|---|
| 仅重写 CHANGELOG 文案为面向用户的版本 | `CHANGELOG.md`(+27 −9) | — | **跳过**（文档） |

【事实】该提交把技术性条目改写为面向用户表述（例如把"弹幕引擎渲染时钟抽象（RenderClock）与
滚动/顶部/底部渲染线的时钟统一"改写为"弹幕滚动、顶部与底部弹幕统一使用稳定的渲染时钟"）。
**同一提交区间内 CHANGELOG 与代码不一致的情况本次未见**（逐条都能在 `6cac80f45` 找到落点）。

### 3.3 `a83598622` Foldable feed crosses soft crease; scrollable video tab bar（3 文件 / +45 −3）

| 用户可见变化 | 上游文件 | bbspace 是否有对应实现 | 结论 |
|---|---|---|---|
| 软折痕（FOLD，`isOccluding=false`）不再强制避让，信息流可整体跨折痕 | `core/ui/adaptive/AppHingeSafeContent.kt`(+12，含一行临时 `Log.d` 诊断) | **无铰链设施** | **需决策**（参见 R2） |
| 视频详情 tab 条溢出时可横向滚动 + 选中项自动滚入 | `feature/video/screen/VideoContentSection.kt`(+34)：`rememberScrollState` + `LaunchedEffect(selectedTabIndex, …)` 计算 `target` 并 `animateScrollTo` | **无对应 UI**：bbspace 视频详情是 `HorizontalPager(pageCount = { 2 })`（`VideoDetailPage.kt:142`）只有 2 页，没有 tab 条 | **跳过** |

【事实·辩驳一条可能的误解】bbspace 的 `FilledTabRow.kt:101-130` 用 `Modifier.weight(1f)`
**等分**剩余宽度，天然不会溢出 → 上游这条"溢出可滚"的修复在 bbspace 的对应组件上**没有触发条件**。

### 3.4 `c1ff890df` Fix video tab clipping and background playback recovery（6 文件 / +126 −40）

| 用户可见变化 | 上游文件 | bbspace 是否有对应实现 | 结论 |
|---|---|---|---|
| 上一条 tab 条的裁切副作用（`weight(1f, fill=false)` → `weight(1f)`，并删掉与 tab 抢宽度的 `Spacer(weight(1f))`） | `VideoContentSection.kt`(−40/+31 重排) | 无对应 UI | **跳过** |
| 后台播放恢复：优化状态必须绑定"进入后台的那个 player 实例" | `feature/video/player/MiniPlayerManager.kt`(+59)：新增 `backgroundOptimizationPlayer`，`resetBackgroundOptimizationState()` 在 7 处交接/关闭点调用，3 处入口加 `player !== currentPlayer` 身份守卫 | bbspace 用 `app/playback/PlaybackHostViewModel.kt:65` 的单个 `shouldResumeAfterBackgroundPause` 布尔 + `:core:playback` 的 `PlayerEngineArbiter`（CAS 所有权） | **可同步（仅判据，不搬实现）**，见 ④ R5 |
| 崩溃快照只认"本进程最近一次退出"，不得回退到更早的崩溃或别的进程 | `core/performance/Android17Diagnostics.kt`(+28)：新增纯函数 `selectLatestAbnormalMainProcessExitIndex(exits, mainProcessName)` | **无该设施**：全仓 `ApplicationExitInfo` / `getHistoricalProcessExitReasons` 零命中 | **跳过（依赖不存在的设施）**，见 ⑤ S1 |
| 大组件规避 DEX 调用寄存器溢出：把函数内 `@Composable fun` 改为 composable lambda | `feature/video/ui/section/VideoPlayerSection.kt`（注释明确写"VerifyError on Android"） | **不适用**：bbspace 最大播放器文件 `VideoPlayerPane.kt` 2580 行（上游对应文件 6000+ 行），且全文件 `@Composable` 全部是**顶层或私有顶层函数**（`VideoPlayerPane.kt:344/431/993/…/2633`），无函数内局部 `@Composable fun` | **跳过（bbspace 不存在该风险）** |

### 3.5 `483099f98` Make floating video cards land on release（38 文件 / +4502 −2）

| 用户可见变化 | 上游文件 | bbspace 是否有对应实现 | 结论 |
|---|---|---|---|
| 悬浮视频卡"松手即落定"的返回完成判据 | `navigation3/predictiveback/VideoCardBackCompletionPolicy.kt`(20 行新) + 测试(52 行)；接线在 `BiliPaiNavDisplayHost.kt`(+2)：`backCompletionPolicy = if (cardMorphAvailable) VideoCardBackCompletionPolicy else null` | **无对应物**（bbspace 的卡片返回是自研 `:core:swipe` + `VideoScreen` 形变，不是 miuix 导航的 back completion） | **跳过**（依赖 `miuix-navigation`），见 ⑤ S2 |
| `miuix-navigation` 由 Maven 依赖改为本地源码模块 | 38 个文件（`NavDisplay.kt` 1090 行、`NavSwipeDismiss.kt` 433、`NavDriver.kt` 322、`PredictiveBackHandler.kt` 240 …），`settings.gradle.kts` +1 | bbspace 不用 miuix 导航 | **跳过** |

【事实·该提交的判据本身】`VideoCardBackCompletionPolicy`：`peakProgress >= 0.65f` 才算进入悬浮卡态；
进入后只有 `progress > 0.35f` 才落定（反向速度/距离都**不能**取消）；`shouldCommitOnCancel` 用同一门槛，
保证新手势不继承上一次的悬浮态，且所有 `Float.NaN` 输入返回 `false`。**这是纯策略、无外部依赖**
（除了接口 `NavBackCompletionPolicy`），因此若 bbspace 哪天要做同类判据，**这 20 行可原样借用**。

### 3.6 `4a79d46bb` Improve adaptive playback, draft restoration and plugin tooling UI（102 文件 / +6090 −810）

| 用户可见变化 | 上游文件 | bbspace 是否有对应实现 | 结论 |
|---|---|---|---|
| 后台预缓存可取消（不再让切歌/退出后继续读写缓存） | `core/player/PlaybackMediaCache.kt`：`prefetchRange` 由普通函数改 **`suspend`**，`coroutineScope { async(IO){writer.cache()} … finally { writer.cancel(); activeUpstream.get()?.cancelPendingRequests() } }`；且 `getOrCreateCache` 为 null 时**改返回被监控的 factory**而非裸 factory | bbspace 有 `PlaybackMediaCache` 等价物吗需细读；`core/playback/repository/PlayUrlCache.kt` 是另一回事 | **需更细读** |
| 播放器氛围光（ambient light） | `feature/video/ambient/` 6 个新文件（`AmbientEnvironment` 56 / `AmbientFrameController` 204 / `AmbientFramePolicy` 122 / `AmbientPlayerBinding` 129 / `PlayerAmbientGlow` 254 / `PlayerAmbientLayout` 58）+ `README.md` 60 行 + `SettingsManager` 3 个开关 | **无对应物** | **跳过（默认关的新功能，且上游自陈未编译未运行）**，见 ⑤ S6 |
| 播放页输入弹层宽屏限宽 | `feature/video/ui/components/InputOverlayLayoutPolicy.kt`(+44)：`resolveBottomInputOverlayMaxWidthDp(windowWidthDp) = if (<600) 同宽 else 640` | 有评论/弹幕输入弹层 | **可同步（小件纯函数）**，见 ④ R7 |
| 视频列表列数改按宽度推导（≥960dp 可得 3 列，且每列 ≥320dp） | `core/ui/components/VideoListLayoutControl.kt`：`resolveVideoListColumns` 由 `if (singleColumn‖<320) 1 else 2` 改为 `(w/320).toInt().coerceIn(2,3)` | 有列表列数策略 | **可同步（小件纯函数）**，见 ④ R7 |
| 抽屉/侧栏铰链安全区、文本选择底栏、下载列表、消息收件箱、动态评论 Sheet、屏蔽列表、设置搜索等 | `core/ui/adaptive/AppHingeSafeSidePanel.kt`(62 新)、`TextSelectionBottomSheet.kt`、`DownloadListScreen.kt`、`InboxScreen.kt`、`DynamicCommentSheet.kt`、`BlockedListScreen.kt`、`SettingsSearchPolicy.kt` … | 多为铰链相关（无对应物）或上游特有页面 | **跳过**（铰链见 R2；其余无对应物） |
| CDN 取流新数据源与搬运面板（371 行 `CdnPlaybackDataSource`、`CdnRangeRequest`、`CdnTransferPolicy/Runtime/Panel`） | `feature/plugin/*` | bbspace 无插件系统 | **跳过**（见 ⑤ S3） |
| JS 插件运行时 / 布局预设 / 草稿恢复 | `core/plugin/js/*`（`BiliPaiJsRuntime` 244、`BiliPaiJsPluginModels` 116、`BiliPaiJsModuleResultCache` 112 新）、`feature/plugin/js/*`、`SettingsManager` | **无插件系统** | **跳过** |
| 历史条目时间格式（今天/昨天/同年/跨年） | `core/util/FormatUtils.kt`(+24)：`formatHistoryViewTime(timestampSeconds, zoneId, locale)` | **已有等价实现**：`core/comment/CommentRepository.kt:637-645`（`X小时前` / `昨天 HH:mm` / `X天前`）与 `feature/video/detail/VideoDetailSummary.kt:438-440` | **可同步（但收益低，见 ④ R4 说明）** |
| `Context.findHostActivity()`（解包 `ContextWrapper` 链拿到 Activity） | `core/ui/SystemBarCompat.kt`(+12) | 无 | **可同步（9 行工具函数）**，见 ④ R4 |

### 3.7 `30daebd0b` Extract shared data, playback and design modules; add TV app（164 文件）

见 ②。分类：**纯搬运 = 跳过**；**共享层抽取 = 跳过（架构不同）**；**个别纯函数 = 已并入 ④ R3**。

### 3.8 `6e6bdce36` Document plugin and TV workflows（12 文件 / +1071 −1）

| 内容 | 上游文件 | 结论 |
|---|---|---|
| 插件开发文档 + 示例插件 | `docs/PLUGIN_DEVELOPMENT.md`(195)、`examples/plugins/*`(149) | **跳过**（无插件系统） |
| TV 适配文档 + TV 脚手架脚本 + 架构检查脚本 | `docs/TV_ADAPTATION.md`(118)、`scripts/check_tv_architecture.py`(74)、`scripts/launch_tv.sh`(67)、`scripts/tv_smoke.py`(252)、`plugins/tools/create-plugin.js`(155) | **跳过**（无 TV 应用） |
| `miuix-navigation` 升级复盘 | `miuix-navigation/README.md`(+10)、`UPGRADE_REVIEW.md`(48 新) | **跳过**；`UPGRADE_REVIEW.md` 可作**阅读参考** |
| `gradle/libs.versions.toml` +1/−1 | — | 需更细读才能给出具体版本语义 |

**纯文档/脚本提交**：本提交对 bbspace 运行时零影响。

---

## ④ 可同步清单（按收益/成本排序）

> 排序口径：**收益 = 消除一个已证实的用户可见缺陷 / 换来实现自由度的确定性提升**；
> **成本 = 落点文件的改动面 + 是否引入新抽象 + 静态可判性**。
> 每条都注明**静态验收**：bbspace 的既有纪律是"编译 + 单任务 `testDebugUnitTest --rerun` 真跑 +
> 结构守卫断言"，且棘轮只许下降。**凡涉及观感的条目，静态验收只能判"结构存在"，判不了"好不好看"。**

### R1 · 竖屏全屏联合创作信息（收益高 / 成本中）

| 项 | 内容 |
|---|---|
| **上游关键点** | `feature/video/ui/overlay/PortraitFullscreenOverlay.kt`（+179，`6cac80f45`）：`coCreators = staff.filter { it.mid != ownerMid && (it.mid > 0L || it.name.isNotBlank()) }`；`isCoCreation = staff.size > 1 && coCreators.isNotEmpty()`；`coCreators.take(2)` 叠放头像（第二个 `offset(x = -(avatarSizeDp*0.28f))`）；文案 `"等 ${staff.size} 人联合创作 ˅"`；点头像开 `PortraitStaffSheet`（`AppModalBottomSheet`，逐成员头像/昵称/分工 + `ActionRepository.checkFollowStatus` + `followStateChanges` 同步） |
| **bbspace 落点** | 新增渲染点挂在 `feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt` 的 `SwipeSessionInfoLine`（`:1557`）附近；数据源已是现成的 `core/model/.../video/VideoDetail.kt:14` `VideoDetail.staffs`（`VideoDetailRepository.kt:186-221` 已填充） |
| **依赖上游独有抽象？** | **无**。只用 `AsyncImage` + 现有底部 Sheet 组件。成员关注状态若要对齐上游，需要 `ActionRepository.checkFollowStatus` 的 bbspace 等价物 + 一个"关注状态变更流"（bbspace **需先确认**是否存在，若有则等价） |
| **静态验收** | ✅ 可判死：① `:app:assembleDebug`；② `:feature:video:testDebugUnitTest --rerun`；③ 新增纯策略 `resolveCoCreators(staff, ownerMid)` 单测（owner 自己必须被排除、`staff.size<=1` 不得出 UI、`mid<=0 && name` 空必须被排除）；④ 结构守卫断言 `VideoPlayerPane.kt` 内确实调用了该策略（防"策略写了没人用"） |
| **成本** | 中。约 150–250 行 + 1 个纯策略 + 3~5 条单测。**风险点**：`dp` 字面量棘轮已满（交接书 §6.8 记 `0.dp` 也算一处）——叠放偏移 `avatarSizeDp*0.28f` 必须写成"常量 × density"形式 |
| **待真机** | 头像叠放观感、Sheet 在竖屏全屏下的高度、大字号换行 |

### R2 · 折叠屏铰链安全区（收益高 / 成本高 → 建议只做最小子集）

| 项 | 内容 |
|---|---|
| **上游关键点（几何核心，纯函数，最可摘）** | `core/util/HingeLayoutPolicy.kt`（`6cac80f45` 后版本）：`resolveHingeSafeContentRegions(containerWidthPx, containerHeightPx, hinges, containerOrigin: IntOffset, clearancePx: Int): List<IntRect>`——按朝向生成观察区、与容器求交集、**完全被遮挡时返回空列表而不是回退到整窗**；`AppFoldingFeatureInfo.layoutHinges()`：`hinges.ifEmpty { hingeBounds?.let { listOf(AppHingeFeature(orientation, bounds, isSeparating, isOccluding, isFlat = posture in (Flat, None))) } }` |
| **上游关键点（软折痕放行）** | `core/ui/adaptive/AppHingeSafeContent.kt`（`a83598622`）：`if (hinges.all { !it.isOccluding }) { BoxWithConstraints(...); return }`，理由写在代码注释里——"国内折叠机全是软折痕（FOLD、无物理缝隙），跨折痕滚动可接受；仅 HINGE 类遮挡铰链仍走分窗避让" |
| **上游关键点（信息流列数收敛）** | `resolveHingeSafeFeedColumns(requestedColumns, availableWidthDp, minCardWidthDp=180)`；`HomeCategoryPage.kt` 里 `effectiveGridColumns = if (shouldAvoidHinge) resolveHingeSafeFeedColumns(...) else requested`，同时**把旧的 `resolveHomeFeedBookHingeGridSpec` 铰链列间距特例整块删掉**，改用统一的 `Arrangement.spacedBy(...)` |
| **bbspace 落点** | ① 纯几何：新建 `core/designsystem/.../adaptive/HingeLayoutPolicy.kt`（或 `:core:common`），并在 `core/designsystem/.../component/AdaptiveMediaGrid.kt`（`HomeVideoPage.kt:302` 消费）加列数收敛；② 视口/弹层：bbspace 的 `AdaptiveMediaGrid` 消费点有 5 处（`HomeVideoPage.kt:302`、`HomeLivePage.kt:61`、`ListenHomePage.kt:44`、`RankScreen.kt:127`、`HomeArticlePage.kt:51`），**必须同批覆盖**否则只有首页对齐 |
| **依赖上游独有抽象？** | 几何层**不依赖**（只需 `androidx.window` 的 `FoldingFeature`，属 Jetpack 标准 API）。但上游的 `WindowRegionLayout` / `HingeSafeOverlayHost` / `LocalAppWindowAdaptiveInfo` 是它自建的 3 层设施，**若要连弹窗/Sheet/直播分屏一起对齐，等于把整套设施在 bbspace 重建**。另：上游 `HomeFeedPinchZoomPolicy`/`resolveHomeFeedCardLayout` 与铰链列数耦合，bbspace **无捏合缩放**，这部分不能照抄 |
| **静态验收** | ✅ 几何部分**完全可判死**：`resolveHingeSafeContentRegions` 与 `resolveHingeSafeFeedColumns` 都是纯函数 → JVM 单测可覆盖（零宽/零高折痕、折痕在容器外、Clearance 夹取、完全遮挡返回空、横向/纵向/None 朝向、`isFlat` 判定）。上游自带 `HingeLayoutPolicyTest`(+40)、`AppHingeSplitGeometryTest`(±60)、`HingePaneUiRegressionTest`(103) 可作对照 |
| **成本** | **高**。几何 + 信息流收敛（最小子集）约 200–300 行 + 10~15 条单测，**且无法用静态手段验证"跨折痕观感"**；弹窗/Sheet/直播分屏对齐是另一个数量级。bbspace 的 `dp` 棘轮与结构守卫会连带改动 |
| **建议** | **分两步**：先只做"几何纯函数 + 单测"入库（零接线、零风险、为将来备好可判定的地基），再决定要不要接线。**不要**一次性照搬 F1–F6 全部 |
| **待真机** | 折痕姿态下的实际观感、`isSeparating` 在国产折叠机上的真实上报值 |

### R3 · 续播决策纯函数（收益中 / 成本极低）

| 项 | 内容 |
|---|---|
| **上游关键点** | `core-player/src/main/java/com/android/purebilibili/core/player/PlaybackResumePolicy.kt`（9 行，`30daebd0b`）：`resolvePlaybackResumePosition(explicitMs: Long?, localMs: Long, serverMs: Long, durationMs: Long): Long`——显式 seek 优先 → 本地进度（>0）→ 服务器进度；`durationMs<=0` 时只夹到 `>=0`；**无显式 seek 且进度 ≥95% 时长时归零**；否则夹到 `[0, durationMs-1000]`。配套 `PlaybackResumePolicyTest.kt`(21 行) |
| **bbspace 落点** | `core/playback/.../StreamPlaybackSessionImpl.kt:807` 的 `private fun canResume(...)` 及 `:730-740` 的使用点——**该判据当前是私有的、埋在播放会话实现里，不可单测** |
| **依赖上游独有抽象？** | **无**。纯 Kotlin 纯函数 |
| **静态验收** | ✅ 完全可判死：抽成 `internal fun resolvePlaybackResumePosition(...)` + 4~6 条 JVM 单测（显式 seek 胜出、95% 归零边界 0.949/0.951、`durationMs` 未知、负值夹取、`durationMs-1000` 下溢） |
| **成本** | **极低**，半天内。价值不在算法本身（bbspace 已有等价语义），而在于**把不可测的私有分支变成可测纯函数**，且顺带统一"本地/服务器/显式"三源的优先级 |
| **待真机** | 无（纯逻辑）。是否与 bbspace 现有语义**完全**一致需逐分支比对后确认，不一致处属决策而非缺陷 |

### R4 · 三个独立小工具（收益低-中 / 成本极低）

| 子项 | 上游 | bbspace 落点 | 依赖 | 静态验收 |
|---|---|---|---|---|
| R4a `formatHistoryViewTime` | `core/util/FormatUtils.kt`(+24，`4a79d46bb`)：`今天 HH:mm` / `昨天 HH:mm` / 同年 `MM-dd HH:mm` / 跨年 `yyyy-MM-dd HH:mm`，**注入 `zoneId` 与 `locale`**，`timestamp<=0` 返回空串 | **已有等价**：`core/comment/.../CommentRepository.kt:637-645`、`feature/video/.../VideoDetailSummary.kt:438-440` | 无 | ✅ 可判死（注入 ZoneId/locale 的确定性单测）。**但收益低**：bbspace 已有两套实现，搬入等于**第三套**。**建议**：只在"要统一这三处口径"时才做，且产物应是"把现有两处收敛到一处"，不是新增 |
| R4b `Context.findHostActivity()` | `core/ui/SystemBarCompat.kt`(+12)：`while (context is ContextWrapper) { if (context is Activity) return context; context = context.baseContext }` | 全仓零命中 | 无 | ✅ 可判死（需要 `Activity` 实例的 JVM 测试在本仓不可行——无 Robolectric。可退为**结构守卫**：断言全仓只有一处解包实现）。成本 9 行 |
| R4c `resolveVideoListColumns` 3 列支持 | `core/ui/components/VideoListLayoutControl.kt`：`(availableWidthDp/320f).toInt().coerceIn(2,3)`（原为 `if (<320) 1 else 2`） | 需更细读找 bbspace 对应处 | 无 | ✅ 完全可判死（纯函数边界：319.9→1、320→2、640→2、960→3、1280→3） |
| R4d `resolveBottomInputOverlayMaxWidthDp` | `feature/video/ui/components/InputOverlayLayoutPolicy.kt`(+44)：`<600 → 同宽；否则 640` | 评论/弹幕输入弹层 | 无 | ✅ 完全可判死（599/600/601 三点） |

**说明**：R4c/R4d 是"上游顺手改的口径"，不是缺陷修复。bbspace 若无对应症状（平板输入面板横贯全屏 / 大平板只用 2 列），**跟与不跟都不算错**，属决策项。

### R5 · 后台播放恢复的"实例身份"判据（收益中 / 成本中）

| 项 | 内容 |
|---|---|
| **上游关键点** | `feature/video/player/MiniPlayerManager.kt`(+59，`c1ff890df`)：① 新增字段 `backgroundOptimizationPlayer: Player?`（"进入后台的是哪个 player"）；② `resetBackgroundOptimizationState()`：取消 job、**只在 `owner === player` 时**恢复 `savedTrackParams`（注释："Restore the departing player's own parameters while it is still managed/alive. Never apply these parameters to the replacement player."），随后把 `isLowMemoryMode`/`enteredBackgroundAtMs`/`didApplyHeavyBackgroundVideoOptimization`/`didApplyIdlePlaybackRelease`/`pending*`/`foregroundResumeIntent` 全部归零；③ 在 7 处交接/关闭点调用（`clearExternalPlayerIfMatches`、`resetExternalPlayer`、`setExternalPlayer` 两处、`dismiss`、失效分支、`player !== externalPlayer` 时）；④ 3 处入口加身份守卫：重优化 job 唤醒时 `if (player !== currentPlayer \|\| backgroundOptimizationPlayer !== currentPlayer) return@launch`（注释："The timeout belongs to the player that entered background, not whichever player happens to be current when the coroutine wakes up."）、`onTrimMemory` 加 `backgroundOptimizationPlayer !== activePlayer` 返回、`applyIdlePlaybackRelease` 同款；⑤ `onEnterForeground` 在 `backgroundOptimizationPlayer !== player` 时**只重置换状态不恢复参数**；⑥ 新增 `optimizeReplacementPlayerIfInBackground()`：若在后台且换了 player，对新 player 重新走一遍 `onEnterBackground()` |
| **bbspace 落点** | `app/src/main/java/com/naaammme/bbspace/playback/PlaybackHostViewModel.kt:65`（`shouldResumeAfterBackgroundPause`，在 `:92/101/104/109/110/117` 使用）；所有权仲裁在 `core/playback/.../PlayerEngineArbiter.kt` |
| **依赖上游独有抽象？** | **无**。但**不能照搬**：bbspace 已有 `PlayerEngineArbiter`（CAS 所有权，记在 B13），两边是**两套不同的所有权模型**。照抄会与既有仲裁重叠 → **只借判据，不借实现** |
| **静态验收** | ✅ **部分可判死**：① 若沿用 bbspace 的 arbiter 模式，可写纯 JVM 状态测（"实例 A 进入后台 → 换成实例 B → 实例 A 的延迟优化不得作用到 B"）；② 结构守卫：断言所有 player 交接点都调用了重置（按文件+调用点数登记，沿用 `CancellationSweepGuardTest` 的"逐文件下限+上限"范式） |
| **成本** | 中。需要先读懂 bbspace 的 arbiter 语义，再决定是"在 arbiter 上加代际号"还是"加一份 MiniPlayer 式状态"。**这一步没有静态手段可以替代读码** |
| **待真机** | 真正的"听视频切后台再回来音频/画面状态是否一致"——**必须真机**，bbspace 现有提交史里这条是反复出问题的地方（`ST-07`、`ST-03` 都是同类） |

### R6 · 弹幕渲染时钟（**判定：缺陷同源存在但补丁不可直接用**）

| 项 | 内容 |
|---|---|
| **上游事实** | 上游把 `System.currentTimeMillis()` 差分的 `STEPPER_TIME`/`HIGH_REFRESH_MAX_TIME` 启发式，换成 `SystemClock.uptimeMillis()` 驱动的、**暂停感知**的 `RenderClock`：`resume/advance/pause/reset`，`advance` 对负差 `coerceAtLeast(0)`；`BaseRenderLine.beginItemTiming/itemElapsedTimeMs` 让**逐项暂停**的弹幕不把自己的暂停区间补回来；`ScrollLine`/`TopCenterLine`/`BottomCenterLine`/`ReverseScrollLayer` 四条线全部改用它（`6cac80f45`，9 文件） |
| **bbspace 事实** | 【事实】bbspace **不使用该引擎**：`gradle/libs.versions.toml:66` `danmakuFlameMaster = "1.0.6"`、`:107` 依赖 `com.github.naaammme:DanmakuFlameMaster`；代码命名空间是 `master.flame.danmaku.*`（`infra/player/.../danmaku/DanmakuRenderSupport.kt:7-18`、`DanmakuOverlay.kt:15-17`、`DanmakuOverlayState.kt:12-15`）。这是一个**用 Java 写的、独立仓库**（`_ref/DanmakuFlameMaster`，`git log` 仅 `a1ce877 tweaks`），上游是 Kotlin 的 `com.bytedance.danmaku.render.engine` |
| **bbspace 自身代码** | 【事实】bbspace 全仓 **零命中** `STEPPER_TIME`、`HIGH_REFRESH_MAX_TIME`、`lastTypesettingTime`、`stepperTime` → **缺陷不在 bbspace 的源码里，而在那个 JVM 依赖的内部** |
| **依赖上游独有抽象？** | **是，而且是"换了整个引擎"级别的依赖**。补丁的 9 个文件里有 8 个属于上游引擎；bbspace 对应物在另一个仓库、另一种语言、另一套类结构里 |
| **静态验收** | ❌ **不能**。上游补丁的"能不能用"在 bbspace 侧**无从静态判定**——需要先在 `_ref/DanmakuFlameMaster` 里找到等价的 `typesetting` 时钟路径。**需更细读**（我这次在该 fork 里用 `currentTimeMillis` / stepper 关键词扫过 `renderer/android` 等目录，**零命中**，说明它的时钟路径与我预期不同，属"需更细读"而非"不存在"） |
| **成本** | 改的是**另一个仓库的依赖** → 超出"同步上游增量"的范围。若确认缺陷存在，应作为独立任务：在 fork 里补时钟 + 发包 + bbspace 升版本 |
| **建议** | **本批不做**。登记为一个待办，附上"先在 fork 里定位时钟路径"的前置条件 |

### R7 · `PlaybackMediaCache.prefetchRange` 改可取消（**需更细读**）

| 项 | 内容 |
|---|---|
| **上游关键点** | `core/player/PlaybackMediaCache.kt`（`4a79d46bb`）：普通函数 → `suspend fun`；`coroutineScope { val reader = async(Dispatchers.IO) { writer.cache() }; try { reader.await() } finally { writer.cancel(); activeUpstream.get()?.cancelPendingRequests() } }`（注释："CacheWriter owns CacheDataSource.close(); only interrupt its active transport."）；**并修了一处静默降级 bug**：`getOrCreateCache(...) ?: return upstreamFactory` 改为"先包好 `monitoredUpstreamFactory`（含 `CdnPlaybackDataSource` + transferListener）再判空并返回它"，即"缓存拿不到"时**仍要保留监控与 CDN 包装**，不能裸退 |
| **bbspace 落点** | 需先在 bbspace 定位等价的"后台预缓存"入口（`core/playback/repository/PlayUrlCache.kt` 是 TTL 缓存，语义不同；真正候选在 `infra/player`）→ **需更细读** |
| **依赖上游独有抽象？** | `prefetchRange` 的签名与取消语义**不依赖**；但它包了 `CdnPlaybackDataSource`（上游插件体系），bbspace 无 → 移植时只取"取消与 finally 收尾"那一半 |
| **静态验收** | 🔶 半可判死：`suspend` 化 + `writer.cancel()` 落点在结构守卫里可断言；"取消后真的不再写缓存"需真机/集成 |
| **成本** | 低-中（取决于 bbspace 是否已有可取消语义）。**这是本批里唯一一条"上游明确承认修的是一个静默降级 bug"的候选**，值得优先细读 |

### R8 · 图片预览缩放/返回动画（**需决策**，收益低 / 成本中）

上游在 `6cac80f45` 重排了 `ImagePreviewDialog`(±269) / `ZoomableImage`(84) / 新增
`ZoomableImageScalePolicy`(24) 与 `ImagePreviewTransitionPolicy`(±44)，并配 3 个 UI 回归测试
（`ImagePreviewDialogUiRegressionTest` / `ZoomableImageUiRegressionTest` / 以及策略单测）。
bbspace 有对应物（动态图片预览 + `PreviewImageGrid.kt:262` 的 `transformable`），但**这条是纯观感修复**
（尺寸跳变/位置偏移/不连贯），**静态验收判不了"是否还跳变"**，且上游的 `ImagePreviewTransitionPolicy`
与 `VideoCardReturnTimeline`/原生快照层耦合。→ **需更细读 + 真机**；本轮不建议排期。

### 可同步清单排序汇总

| 排名 | 条目 | 收益 | 成本 | 静态可判 | 依赖上游独有抽象 |
|---|---|---|---|---|---|
| 1 | **R3** 续播决策抽纯函数 | 中 | 极低 | ✅ 完全 | 否 |
| 2 | **R1** 竖屏全屏联合创作 | 高 | 中 | ✅ 完全 | 否（关注流需自备） |
| 3 | **R4** 三个小工具（R4b/R4c/R4d） | 低-中 | 极低 | ✅ 完全 | 否 |
| 4 | **R7** 预缓存可取消 | 中 | 低-中 | 🔶 半 | 部分（只取一半） |
| 5 | **R5** 后台播放实例身份判据 | 中 | 中 | ✅ 部分 | 否（但须与 arbiter 融合） |
| 6 | **R2** 铰链几何（只做纯函数层） | 高 | 高 | ✅ 几何部分完全 | 几何否 / 全套是 |
| 7 | **R8** 图片预览动画 | 低 | 中 | ❌ | 部分 |
| 8 | **R6** 弹幕渲染时钟 | 中 | 极高（跨仓库） | ❌ | **是（换引擎级）** |

---

## ⑤ 不可同步清单 + 理由

| # | 条目 | 上游范围 | 不该跟的理由 |
|---|---|---|---|
| **S1** | `core-data/` `core-player/` `design-tokens/` 三个新模块与整套搬运 | `30daebd0b` 主流（约 100+ 文件纯搬运） | ⓐ **纯搬运零行为收益**（`2.2` 已逐文件给出 `0 0` 证据）；ⓑ bbspace 模块划分是 `infra:* / core:* / feature:*`（51 模块），与上游"单 app + 若干共享模块"结构**不同源**；ⓒ bbspace 的设计令牌棘轮 / 结构守卫测试**按文件路径登记**（如 `CardDensitySingleSourceTest.kt:53/84` 写死 `listOf("component","AdaptiveMediaGrid.kt")`），搬动会连带改一批守卫，纯成本 |
| **S1b** | 崩溃快照"只认本进程最近一次异常退出" | `Android17Diagnostics.kt`(+28) | 【事实】bbspace **没有** `ApplicationExitInfo` / `getHistoricalProcessExitReasons` / `Logger.hasPendingCrashSnapshot` 这套设施（全仓零命中）。上游那个纯函数 `selectLatestAbnormalMainProcessExitIndex` 本身写得很干净、可单测，**但它的调用点不存在** → 先有设施才谈这条 |
| **S2** | `miuix-navigation` 源码模块 + `VideoCardBackCompletionPolicy` + 视频卡原生快照/预测性返回全套 | `483099f98`（38 文件 / +4502）+ `6cac80f45` 的 `navigation3/*`、`core/ui/transition/*` | ⓐ 上游为此把 Maven 依赖**换成 4502 行本地源码模块**（`settings.gradle.kts` + `project(":miuix-navigation")`），bbspace 根本不用 miuix 导航；ⓑ bbspace **无原生卡片快照设施**（`CardPositionManager` / `captureNativeVideoCard*` / `NativeSnapshot` 零命中），而该提交的核心价值恰在"快照来源排除 + 返回时间线 + 手势跟随状态"；ⓒ `VideoCardBackCompletionPolicy` 只有 20 行纯策略，**将来若需要可单独借那 20 行**（见 ③.3.5），但**不要**引入整个导航库 |
| **S3** | 插件系统（`core/plugin/js/*`、`feature/plugin/*`、`plugin-sdk`、`plugins/tools`、`examples/plugins`、CDN 取流 `CdnPlaybackDataSource` 371 行 / `CdnTransferPanel` 183 / `CdnTransferRuntime` 239、JS 布局预设、草稿恢复） | `4a79d46bb` 主体 + `6e6bdce36` 文档 | bbspace **没有插件系统**。这些是"运行用户提供的 JS/Kotlin 插件"的宿主侧设施（含独立 CDN 取流数据源、二维码搬运面板、模块结果缓存、布局预设存储）。没有插件系统时，`CdnPlaybackDataSource` 的 371 行**没有任何消费者**；照搬等于凭空引入一条无人调用的取流路径 |
| **S4** | 扫码授权 + 设备间迁移 + 用户协议门槛 | `6cac80f45`：`OfficialQrAuthorization*`(3 文件) / `BiliPaiTransfer*`(4 文件) / `UserAgreement*`(3 文件) / `QrTransferModels` / `SettingsShareService` | ⓐ 三者都是**产品级新功能**，不是"上游修了一个 bbspace 也有的 bug"；ⓑ 扫码授权与设备迁移的语言是"BiliPai 账号 / BiliPai 插件 / BiliPai 皮肤"，对 bbspace **语义不成立**；ⓒ 用户协议门槛尤甚：上游正文明确写"GPL v3 开源性质、Firebase Crashlytics/Analytics 披露"，**bbspace 的许可、隐私披露、统计链路都不同**，照抄等于把错误的法律文本塞进另一个应用。**属产品决策，不是同步项** |
| **S5** | 折叠屏铰链全套（F1–F6 的弹窗/Sheet/直播分屏/番剧窗格 + `WindowRegionLayout` + `HingeSafeOverlayHost` + `LocalAppWindowAdaptiveInfo`） | `6cac80f45` 的 `core/ui/*`、`core/ui/adaptive/AppHingePaneLayout.kt`、`AdaptiveDialogComponents.kt`、`AppSheetComponents.kt`、`feature/bangumi/BangumiPlayerScreen.kt`（±145）、`TabletVideoLayout.kt`、`LiveRoomLayoutPolicy.kt` | 上游是**自建三层设施**（`LocalAppWindowAdaptiveInfo` → `WindowRegionLayout` → `HingeSafeOverlayHost`）再让 40+ 处消费。bbspace 零基础，**要跟就得重建整套**且改动面横跨 app/designsystem/feature 三层。→ **只取几何纯函数（R2）**，其余需要独立立项与真机验收 |
| **S6** | 播放器氛围光（ambient light） | `4a79d46bb`：`feature/video/ambient/` 6 文件约 820 行 + `README.md` 60 行 + `SettingsManager` 3 开关 | ⓐ **默认关（`enabled ?: false`）的新功能**，不是同步；ⓑ 上游自己在 `README.md:50-53` 写明"**测试未执行**：用户要求编译与执行的显式授权……8fps 性能目标与驱动相关渲染**不通过静态检查验证**"，`README.md:55-60` 要求真机 30 分钟对比丢帧/内存平台期 —— 本任务**无真机**，照搬既无法验证也无收益；ⓒ 上游对平板/影院/全屏/角落各写一套几何，改动面极大 |
| **S7** | 上游特有页面/组件的连带修补：`BgmDetailCard` 自适应高度、`VideoContentTabBar` 可滚 tab 条与裁切修复、`HomeFeed*` 捏合缩放与 3 列、`InboxScreen`/`DownloadListScreen`/`DynamicCommentSheet`/`BlockedListScreen`/`SettingsSearchPolicy`/`TextSelectionBottomSheet`/`TabletCinemaLayout` 等 | `a83598622`、`c1ff890df`、`6cac80f45`、`4a79d46bb` 中的多数 UI 改动 | 【事实】逐条复核后**无对应物**或**无触发条件**：`Bgm` 全仓零命中；`FilledTabRow.kt:101-130` 用 `weight(1f)` 等分**不会溢出**（上游那条修的是它自己 `width(tabItemWidth*tabs.size)` 的可滚条）；bbspace 视频详情只有 `HorizontalPager(pageCount={2})`（`VideoDetailPage.kt:142`）没有 tab 条；bbspace **没有**信息流捏合缩放。**没有触发条件的修复不能算"可同步"** |
| **S8** | 上游 DEX 寄存器溢出重构（函数内 `@Composable fun` → composable lambda） | `c1ff890df` 的 `VideoPlayerSection.kt` | 【事实】bbspace 不存在该形状：`VideoPlayerPane.kt` 2580 行，`@Composable` 全部是顶层/私有顶层函数（`:344 :431 :993 :1164 :1177 :1204 :1333 :1421 :1459 :1557 :1668 :1736 :1812 :1839 :2088 :2178 :2313 :2524 :2566 :2633`），无局部 `@Composable fun` → 无风险可修 |
| **S9** | 上游 TV 应用与配套脚手架 | `30daebd0b` 的 `app-tv/`(22 文件)、`6e6bdce36` 的 `docs/TV_ADAPTATION.md`、`scripts/check_tv_architecture.py`、`scripts/launch_tv.sh`、`scripts/tv_smoke.py` | 独立应用，与 bbspace 手机端同步无关。**唯一可取的**是 `SharePlaybackSession`(253)/`SharedPlaybackRepository`(72) 这套"手机与 TV 共用播放会话"的设计思路，作为**阅读参考** |

---

## ⑥ 与 bbspace 已有同步记录的重叠核对

已读：`docs/ai-loop/taskbook-2026-10-01-BiliPai对齐-三阶段升级.md`（127 行）、
`docs/ai-loop/report-2026-10-01-上游对齐三阶段.md`（125 行）、
`docs/2026-09-28-播放页三项优化与BiliPai上游同步-工作交接书.md`（201 行）。

### 6.1 【事实】基线衔接：本次没有需要重做的重叠

bbspace 的同步记录以 `34d189620` 为**终点**（任务书 `:98` "第二次同步（2026-10-01 当日）：
`5b93372fe` → **`34d189620`**"）。本次区间正是 `34d189620..6e6bdce36`，
**起点接上、无覆盖、无空隙** → **本报告不含任何"其实已经同步过"的条目**。

### 6.2 【事实】已被明确判"不搬"且本次结论一致（**避免重复劳动**）

任务书 `:105-108` 与报告 `:95-96` 已把 6 项判为不搬，本次独立复核**结论一致**，**不要再讨论**：

| 已判项（前期记录） | 前期理由 | 本次独立复核 |
|---|---|---|
| 全屏播放器截图（`34d189620` 的"分享/保留相册"） | 真缺口，但需写相册 + `PlayerView.getBitmap()` + 分享面板，无设备不可验 → 记 BACKLOG **AF-01** | 属**上一基线**（不在本次 8 提交内），本次无需处理；**AF-01 仍然有效** |
| 液态玻璃命名预设（`LiquidGlassSavedPresets.kt` 186 行） | 给 2 个可调参数做预设系统 = 无收益抽象 | 本次区间内**零改动**，结论不变 |
| 直播发弹幕面板横屏可滚 / 多模式换行 | bbspace 无模式选择 | 本次区间内**零改动**，结论不变 |
| RSS 订阅页 / `MessageAppScaffold` / 设置分享服务 | bbspace 无 RSS | 设置分享服务在本次 `6cac80f45`/`4a79d46bb` 有改动，但**为设备迁移服务**（S4），结论不变 |
| 图片磁盘缓存 256MB **不动** | `BiliApplication` 注释里的真机结论与上游不同前提 | 本次区间内**零改动**（上游 v0.2.4 未碰缓存预算），无需重新评估 |
| `ComposeFoundationFlags` 全局瀑布流预取开关 | 无复现证据不开 | 本次区间内**零改动**，结论不变 |
| 刷新率常驻 120Hz（`e5587f761`/`c267d119`） | 方向相反的功耗风险，先补诊断口径 | 本次区间内**零改动**，结论不变 |

### 6.3 【事实】前期"未做并写明理由"的项，本次区间也没有新的依据

报告 `:81-88` 把 U-2（`alignByBaseline` / 共享 meta row）、U-3（视觉语言扩展）、整体调密
判为"无设备做就是赌"。本次 8 提交**没有**新增任何可用静态手段判定的基线/密度改造
（上游这轮的重心在折叠屏/扫码/迁移/氛围光）→ **U-2/U-3 维持原判，不必重新论证**。

### 6.4 【需决策】本次新增、前期记录未覆盖的条目

以下 4 条在前期三份记录里**没有出现过**，是本轮真正的新增工作量（对应 ④ 的 R1/R2/R5/R7）：

| 新条目 | 为什么前期不会覆盖 |
|---|---|
| R1 竖屏全屏联合创作 | 上游 v0.2.4 才加；bbspace 已有 `staffs` 数据但无渲染点 —— **最小的"数据已就绪、只差 UI"缺口** |
| R2 折叠屏铰链几何 | 上游 v0.2.4 才做 F1–F6；bbspace 零基础 |
| R5 后台播放实例身份判据 | 上游 `c1ff890df` 才补；与 bbspace 的 `PlayerEngineArbiter`（B13 已完成）是两条独立的所有权线，**需要考虑融合而非照搬** |
| R7 预缓存可取消 | 上游 `4a79d46bb` 才改；bbspace 是否有对应入口 **需更细读** |

### 6.5 【口径提示】不要复用的前期结论

- 任务书 `:22-24` 记的"`--rerun` 只在命令行只有一个任务时生效" —— **仍然有效**，本次若要验证
  R1/R3 的新增守卫，仍须**逐模块单任务** `--rerun` 并先删 `build/test-results`。
- 交接书 `:171-174` 记的"dp 字面量棘轮已满（980/980）/ `0.dp` 也算一处"与"miuix 玻璃链必须 `remember`"
  —— **仍然有效**，R1 的头像叠放偏移会直接撞上第一条。
- 交接书 `:168-170` 记的"不要用按行 splice 改这些文件（PowerShell 对含中文/CRLF 文件的行切分不一致）"
  —— **仍然有效**。

---

## 附录 A · 本次复核用到的关键命令（可复现）

```powershell
# 提交列表与规模
cd D:\111j\c\_ref\BiliPai-024
git log --oneline 34d189620..6e6bdce36
git diff --shortstat 34d189620..6e6bdce36

# 纯搬运 vs 有内容改动（0 0 = 纯搬运）
git show 30daebd0b --numstat -M | Where-Object { $_ -match '^\d+\s+\d+\s' }

# 逐提交改动清单
git show --stat --oneline <sha>
git show <sha> --name-only --format="" | Where-Object { $_ -like '*/main/*' }

# 关键判据
git show a83598622 -- "*/AppHingeSafeContent.kt" "*/VideoContentSection.kt"
git show c1ff890df -- "*/MiniPlayerManager.kt" "*/VideoPlayerSection.kt"
git show 483099f98 -- "*/VideoCardBackCompletionPolicy.kt"
git show 4a79d46bb -- "*/PlaybackMediaCache.kt" "*/InputOverlayLayoutPolicy.kt"
git show 6cac80f45 -- "*RenderClock.kt" "*ScrollLine.kt" "*/HomeFeedPinchZoomPolicy.kt"

# 基线完整性
git merge-base --is-ancestor 34d189620 6e6bdce36   # 退出码 0
```

## 附录 B · 本报告的已知不确定项（不许当作结论使用）

| 项 | 状态 |
|---|---|
| `6e6bdce36` 的 `gradle/libs.versions.toml` +1/−1 具体改了哪个版本 | **需更细读** |
| ~~听视频悬浮条（`AudioNowPlayingBar` +93）在 bbspace `:feature:listen` 的对应实现~~ | **已闭环**：bbspace 无悬浮播放条层，只有 `ListenDetailScreen.kt:48`；结论"跳过（无对应物）" |
| 通知栏媒体控制 / 音频会话同步（`AudioNowPlayingSession`）在 bbspace 的对应实现 | **设施已定位**（`PlaybackService.kt:46/140/149/324`、`CapsuleIndicator.kt:85`、`LinkedBottomDock.kt:150-173`）；**同步语义的逐分支比对**仍需更细读 |
| 播放器控制栏弹幕三件套（`BottomControlBar` ±638）与 bbspace `PlayerCtrlBar.kt:2178` 的差异 | **需更细读** |
| ~~视频笔记等级徽章（`VideoNoteResponse.is_senior_member`）在 bbspace 是否有渲染点~~ | **已闭环**：bbspace 的 `note` 是空间备注（`SpaceNoteSection.kt`/`SpaceNoteDb.kt`），与视频笔记无关；结论"跳过（无对应物）" |
| `PlaybackMediaCache.prefetchRange` 在 bbspace 的等价入口 | **需更细读** |
| `_ref/DanmakuFlameMaster` 内部的帧步进时钟路径（我用 `currentTimeMillis`/stepper 关键词扫过若干目录**零命中**，说明路径与预期不同，不是"不存在"） | **需更细读** |
| `git show --stat` 在本机出现过的路径段省略 / 误报 `new file` 现象 | 已在报告开头声明；凡关键判定均已 `Test-Path` + `git log -- <path>` 二次复核，未复核的写"需更细读" |
| 所有"能不能用 / 快不快 / 观感"结论 | **待真机**（本机 `adb` 不在 PATH。另：`D:\env\android-sdk\platform-tools\adb.exe` 见交接书 `:93`，但**本次未调用任何设备命令**） |
