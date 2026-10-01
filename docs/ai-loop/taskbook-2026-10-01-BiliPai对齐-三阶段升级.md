# 任务书 · 2026-10-01 · 对齐 BiliPai v0.2.3.5 的三阶段升级（刷视频 / 性能 / UI）

> 上游同步：`_ref/BiliPai` `5e0be26f4` → **`5b93372fe`**（v0.2.3 + v0.2.3.5，**310 个提交**，
> 新标签 v0.2.3 / v0.2.3-alpha.8~10）。上一轮差距分析（`analysis-C10-*.md`）基于旧基线，
> 本文件只登记**新增/变化**与**经我复核后仍成立**的项。
> 本文所有 file:line 由我本人 `Read` 复核过；子代理报告的其它条目按同一纪律边做边核。

## 0. 开工前置：上一会话的在制品已归档

工作树原有 781 行未提交变更（2026-09-29 19:00~21:10，即交接书之后又做的一轮）。
已按主题分成三个本地提交归档（**未推送**）：

| 提交 | 内容 |
|---|---|
| `17f0f55c` | 刷视频入口改为「简介区边缘下滑」+ 全屏按钮不连带开连播 + 还原退出会话露详情页 + 比例过渡改临界阻尼弹簧 |
| `2fd276e4` | 控制玻璃帧时钟加 `showCtrl` 门控；底部信息层 v2；互动栏升会话级单实例；评论改实底悬浮卡（去大面模糊）；侧栏折行修复 |
| `b4abd2d9` | 排行页卡面与推荐页同源（`rememberVideoCardCoverTint` 提到 designsystem 共用） |

归档验证：`:app:assembleDebug` 通过；`:feature:video:testDebugUnitTest --rerun`、
`:core:designsystem:testDebugUnitTest --rerun` **真跑**全绿（351 单测 / 0 失败）。

> **本仓新增工具性事实**：`--rerun` 只在命令行**只有一个任务**时生效。
> `./gradlew testDebugUnitTest --rerun`（多任务/含生命周期任务）会被**静默忽略**，
> 整轮 1352 个任务全 `UP-TO-DATE`、构建 13 秒"成功"。逐模块单任务调用才真跑。

## 1. 阶段一 · 刷视频模式（D1~D3）

### D1 滑动切换的流畅度

| 项 | 证据 | 做法 |
|---|---|---|
| 邻居页零预组合 | `PortraitSwipePager.kt:302` 的 `VerticalPager` **没传** `beyondViewportPageCount`（对照 `VideoDetailPage.kt:214` 显式传 0） | 设 1：起滑前邻居页已组合、封面请求已发出，手指拖动不再"边拖边建树" |
| 封面→首帧交接的**亮度凹陷** | `VideoPlayerPane.kt:989~1037`：整层（封面 + 暗遮罩 + 粒子）共用一个 `alpha` 一起淡出。淡到一半时是「半张暗遮罩压在半条画面上」，比两端都暗 | 分两段：遮罩+粒子先快退 → 封面**保持不透明**一小拍 → 再与画面交叉淡化（干净封面直接 dissolve 到视频），并加 1.02→1.0 落定收缩。时长取 `LocalAnimations` 档位，`reduceMotion` 走 snap；值全在 `graphicsLayer` 里读，零重组。时序做成纯函数便于单测 |
| 轴仲裁无宽限 | `VideoGestureController.kt`（`Phase.Press` 分支）：位移一过 slop 就用 `abs(x) > abs(y)` **立即锁轴**，且每帧无条件 `change.consume()` | 抄上游 `core/ui/common/VerticalPriorityPagerGesture.kt:39-78` 的意图宽限（横向主导比 1.5 / 歧义宽限 1.5×slop）。bbspace 侧做成纯策略函数 + 单测；**未决期不消费事件**（否则会话内上下滑会被吞，比现状更糟） |

上游对照结论：**story 线自身这 310 个提交零改动**（`feature/story/`、`StoryRepository`、
`PortraitPagerSwitchPolicy` 逐路径 `git log` 计数为 0）→ 旧分析的差距总表未被推翻。
上游在垂直 pager 上本轮只改了：加载预热加 Wi-Fi 闸门（`PortraitVideoLoadPolicy.kt:416`）、
默认画质移出组合路径（`9e7da235b`）、退出全屏方向四连修（`4ab425157`→`5b93372fe`）。

### D2 会话内功能补全

- **不感兴趣**：上游把竖屏播放器菜单换成「定时关闭 + 不感兴趣」（`e864d9590`）。
  bbspace 首页已有完整通道（`core/feed/FeedDislikeRepository.kt:20-27`、
  `HomeViewModel.kt:290-333` 含撤回），但**连播会话里没有任何入口**，
  且 `SwipeEntry` 目前不带 `dislikeContext`（`PortraitSwipeQueueTest.kt:317` 恒 null）。
  做法：队列条目携带 dislike 上下文 → 会话内一个动作 = 上报 + 从队列摘掉 + 停在当前页语义不变。
- **蜂窝闸门**：预取已按 `isUnmetered`（`VideoViewModel.kt:657`），补上游新增的那一层
  （蜂窝下连"下一页头部预热"都跳过）。
- 明确**不做**：定时关闭（bbspace 全仓无此设施，属新功能不是补洞，另立项）。

### D3 不要照抄的上游做法

- `PortraitPagerSwitchPolicy.kt:80-89`：当前页一律不铺封面 = 黑底硬切。bbspace 的封面铺底
  是**用户真机反馈后定稿的**，比它先进，不为"对齐"而退。
- 方向策略：`resolvePhoneVideoRequestedOrientation` 已堆到 15+ 入参并连环打补丁
  （`VideoDetailPlatformPolicy.kt:711-741`）→ bbspace 遇到同类问题时不要再加参数。
- 「画质移出组合路径」用 0 占位 + 4 处守卫 + 1 处补发换来（5 个时序耦合点）→ 不做。
- 上游多份提交自陈"未编译、未跑测试"（`8cfc4246b`、`5b93372fe`）→ 数值一律自己复核。

## 2. 阶段二 · 内存 / CPU / 卡顿（P1~P3）

| # | 上游修复（提交） | bbspace 现状（我复核过的 file:line） | 判定 |
|---|---|---|---|
| P1 | `8cfc4246b` 取色改 96×96 独立软件采样 + `finally recycle` | `VideoCardCoverColorStore.kt:156~162`：HARDWARE 位图 `bitmap.copy(ARGB_8888)` **且不回收**。640×480 一份 ≈1.2MB、960×540 ≈2.1MB，每张染色卡片一次 | **同类问题原样存在** → 改成画进小尺寸软件位图（96×96 ≈36KB）并 recycle；比上游更省（不额外发一次 Coil 请求，位图已在手） |
| P1 | 同上：主线程分发用 `Main.immediate` | `:78 / :103 / :138` 三处非 immediate；调用点在 `LaunchedEffect`（`VideoCardCoverTint.kt:48-60`，已在主线程、不在组合期）→ 白排一次分发 = 染色晚一帧 | 改 |
| P2 | `8cfc4246b`/`3d5c296cb` 解码尺寸按屏/按卡宽推导 | 档位是**写死的图床后缀**（`BiliImageUrl.kt:14~33`），预加载尺寸也是写死常量（`HomeVideoPage.kt:1062~1065`），全链路无一处从 `screenWidthDp`/卡片实测宽推解码尺寸 | 窄屏双列稳定超采；改按屏宽选档，**必须同批改预加载与显示两条路**（`CoverPreloadUrlTest.kt:138` 钉住两条路同源） |
| P2 | `a4b8c17f8` 图片磁盘缓存 150→100MB、分片缓存 512→128MB | 磁盘 256MB、内存 `maxSizePercent(0.25)`、后台只有**比例**收敛无绝对预算（`BiliApplication.kt:181~188/217/225`） | 收敛到绝对预算 + 宽限期；注意该文件注释禁止再加分支 |
| P3 | `e5587f761` 静止时把整机锁在 120Hz 导致耗电；`c267d119` 面板实际刷新率诊断 | `MainActivity.kt:328~356`：AUTO = 同分辨率取**最高**刷新率并 `preferredDisplayModeId` 钉死，全应用常驻、无静止降档 | 方向相反的功耗风险，**先补诊断口径再谈改策略**；无设备不下结论 |
| — | `cd1c6434d` 瀑布流 cache-window 预取有正确性 bug → 全局关掉 | `HomeVideoPage.kt:147` 用的正是 `rememberLazyStaggeredGridState`；全仓无 `ComposeFoundationFlags` | 无复现证据前**不开**这个全局开关，转 L4 |

已核实**不必再搬**的：骨架脉冲已是"返回 `State` + 绘制期读"（`Skeleton.kt:85~132`）、
顶部指示器已 `offset{}` 读值（`FilledTabRow.kt:164`）、玻璃 tick 门控与静态玻璃画已在
`2fd276e4` 那批落地、常驻控件不许逐帧采样的真模糊（`VideoPlayerPane.kt:1228~1239` 注释）。

## 3. 阶段三 · UI 大幅重绘（U1~U3）

上游 v0.2.3.5 整版在做**密度收紧**，且给出了"收紧但不牺牲触控"的口径
（只压可见容器、不动 Compose 命中区：`SubReplyDetailComponents.kt:1418~1470`）。

- **U1 密度与 token**：bbspace 偏松（`AdaptiveMediaGrid.kt:78~90` 列 10/行 12 是 2026-09-22
  主动改松的；卡信息区 `HomeVideoPage.kt:536` 与 `RankScreen.kt:446/610` 同值字面量双写；
  全站 `vertical = 6.dp` 19 处；`BiliSpacing` 缺 2dp 档）。做法：把卡内边距/标题-meta 间距/
  网格行距收进 token，棘轮**下降**而非上升；页边 10dp 在 `HomeVideoPage.kt:294~297` 与
  `RankScreen.kt:144~149` 双写必须同批改。
- **U2 布局层次**：全仓 `alignByBaseline` **零命中**，meta 全靠 CenterVertically；
  没有共享的 stat-row 原子（只有 `PrivacySettingsScreen.kt:292` 一个私有 InfoRow）。
  上游手法：简介块首行标签+正文 `alignByBaseline`、次行 meta 用 `labelLarge` 粗体
  （`b47643af7`→`d6fd297df`→`3c912210f` 三定），分集统计删自造组件复用共享行（`965223855`）。
- **U3 交互细节**：撤销/定位胶囊堆叠避让 + 各自可手动关闭（`HomeScreen.kt:2865~2868`）、
  回顶改 `scrollToItem(0)` 瞬时直达消两段式顿挫（`580bac0fc`）、
  图片预览退出携带松手速度 + 双轴跟手（`3a36cd900`/`a62a87c1c`）。
- **不要跟**：侧栏渐进模糊六修后整版回滚（`3b14942dd`，-145 行）、tab 透明度自相矛盾
  （`74eceb100`→`988fbc1cb`）、女仆图标 91 文件 revert（`462ba1b32`）、
  弹幕字号 25→15→20 反复且 CHANGELOG 与代码不一致、"按钮统一 48dp 下限"已被自己回退
  （`cea46ad23`）。

## 4. 验收口径（每批）

1. `:app:assembleDebug` + 受影响模块 `testDebugUnitTest`（**单任务 `--rerun` 真跑**，先删
   `build/test-results`）；新增守卫必须配"故意改坏会红"的证据。
2. 设计令牌棘轮只许**下降**；`animate*AsState` 上限现 9、`rememberInfiniteTransition` 上限 2，
   动画值在 draw/graphicsLayer 里读。
3. 无设备 → **任何帧率/内存/手感数字一律不声称**，需真机确认的写进 `L4-待真机清单.md`。
4. 每批一个主题提交，只本地、不推送。
