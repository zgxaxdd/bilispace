# BBSpace 性能 / UI 优化 · 执行报告（2026-09-22）

> 对应任务书 `2026-09-22-BBSpace性能UI优化-长期任务书.md`。
>
> **数字口径已变化，读之前先看这段**：本报告初稿写于无设备时，当时全文零帧率数字。
> 随后接入了 USB（PLC110），补做了设备实测 —— 结果与限制记录在 **§5-A**。
> 三条硬约束决定了这些数字**不能**用来判断"是否达标"：
> ① 本机没有签名配置，只能测 **debug 变体**，与任务书里 release 基线不可比；
> ② 面板实际跑在 **120/144Hz**，不是任务书假设的"锁 60Hz"，帧预算完全不同；
> ③ **对照实验（B 组）未完成**，所以没有任何"改善了多少"的结论可下。
> 本轮所有"已改善"的证据仍然只有静态断言；性能改善幅度**未证明**。

---

## 1. 概览

| 项 | 值 |
|---|---|
| 分支 / 起点 | `build/agp9-migration`，起点 HEAD `d7bbf677` |
| 前置工作分组提交 | 11 个（`0f0152b4`…`4f2cbb89`，把原本 75 条未提交变更按主题拆开） |
| 本轮任务提交 | 8 个（`e64a20de`…`73fc938e`，T3/T5/T6/T4/T1/T2/T7/T8 各一） |
| 本轮改动量 | 48 文件 / +3594 −462 |
| 单测数量 | **71 → 116**（新增 45 条，全部 JVM、可离线跑） |
| 最终状态 | `:app:assembleDebug` BUILD SUCCESSFUL；`testDebugUnitTest` 116 通过 / 0 失败 / 0 错误 |
| 棘轮上限变更 | 3 处（1 降、1 降、1 升），见 §3 |
| 推送 | **未推送**，全部只在本地 |
| 设备接入 | 后半程接入 PLC110（同任务书设备），补做 debug 变体实测，见 §5-A |
| 性能改善幅度 | **未证明**（B 组未测；且 debug/120Hz 与 release/60Hz 基线不可比） |

构建命令（两个环境变量必须带）：
```powershell
cd D:\111j\c\_ref\bbspace
$env:GRADLE_USER_HOME="D:\env\gradle-home"; $env:JAVA_HOME="D:\env\jdk17"
.\gradlew.bat :app:assembleDebug testDebugUnitTest --console=plain
```

---

## 2. 逐条做了什么、为什么、踩了什么坑

### 2.0 前置工作分组提交（开工前的必要动作）

任务书 §9 自己指出"当前 74 条未提交变更已经太大，混在一起无法回滚"。落地为 11 个主题提交：
toolchain / docs / adaptive-V2 / 取消语义 / ViewModel 两个独立缺陷 / glass-LIQUID /
设计 token / onTrimMemory / seamless / LinkedBottomDock / 详情重设计 / playback。

**约束说明**：无法做 hunk 级拆分（不能用交互式 `git add -p`），所以分组是**文件级**的。
两个文件因此承载了多主题（`libs.versions.toml` = 工具链 + 新依赖 + 移除 Retrofit；
`AppNavHost.kt` = Dock + 玻璃接线），已在各自提交信息里写明。
中间提交**未逐个验证可编译**（每个 commit 都跑全量构建代价过高），只保证最终状态绿；
这是为可回滚性接受的取舍。

### T3 消除信息流卡片与顶栏的每帧分配/重组 → `e64a20de`

六处，都是"分配/读取发生在哪个阶段"这一类：

1. **`FeedItem.actionKey` 改为构造期赋值的 body 属性**。作为函数时 key lambda 与 dislike
   查表每张卡每次重组各拼一个新 String；`by lazy` 更糟（多一个委托对象 + SYNCHRONIZED 取锁）。
   **放 body 而非主构造参数是刻意的**：data class 只有主构造属性才进 equals/hashCode/copy，
   写成参数会让"键"参与内容比较，改变列表 diff 与 `distinctBy` 语义。
   顺带删掉 `HomeViewModel` 与 `HomeVideoPage` 里两份**逐字相同**的私有扩展 ——
   两处独立演化迟早算出不同的键，键一分叉列表复用会静默错乱。
2. 卡片三块形状 + 角标形状提到文件级常量（省分配，也省掉两处 `remember` 槽位与键比较）。
3. 卡片标题样式 `remember`：`typography.bodyLarge.copy(...)` 直写在 `style =` 上等于
   每次重组新建一份约 20 字段的 TextStyle。
4. 信息区封面微光 brush 从 `drawBehind` 挪进 `drawWithCache` 缓存块。
   **任务书把这条记在 Skeleton 名下，但 Skeleton 的扫光早已被 alpha 呼吸取代、本就没有 Brush；
   真正每帧重建 Brush + `listOf(3 Color)` 的是这里。** 顺手删掉 Skeleton.kt 里因此遗留的
   `Brush`/`Offset` 两个死 import。
5. `FilledTabRow` 的 iOS 分段滑块：`thumbX by animateDpAsState` + `offset(x = )` →
   保留 State 引用 + `offset { IntOffset(...) }`。组合期读动画值 = 切 Tab 的整个 spring
   过程每帧重组一次**顶栏**。
6. `CoverExpandBoundsStore.put` 先把 4 个分量量化成 Float 逐个比，只在真变了时才构造 Rect。
   原实现先构造再比，去重只省一次 map 写入、分配一次没省。
   调用方 `boundsInWindow()` 的临时 Rect 省不掉（公开 API 没有"写入调用方 Rect"的重载），
   净效果稳态 2→1 次分配。

**证据**：`ListAllocationStructureTest` 8 条。已实测**故意破坏会 FAILED**
（临时注入一处内联 typography copy + 一处局部 RoundedCornerShape → 3 条红）。

### T5 设计体系化 → `549811eb`

1. **弹层圆角 token 收敛**：7 个 feature 文件各写一份
   `RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)`，而 `BiliRadius.sheetTop`
   早就存在、7 处注释还点名"这就是 sheetTop 语义"却没用它，注释并且把值写成"20dp"
   （实际 28dp）。字面量散落的真实代价：**token 改了 7 处一个都不跟**。
   - 替换前先反编译查证这确实**像素等价**：`javap` 看 foundation 1.12.0 的
     `RoundedCornerShape$default(FFFF, int, Object)`，四个省略位都是 `fconst_0` →
     **未给出的角默认 0dp，不继承顶角**。不查证就换等于拿 7 处外观赌博。结论写进
     `BiliRadius.topRounded` 的 KDoc。
2. `CapsuleGroup` 分组列表上下两半分角改用 `topRounded/bottomRounded`（与首页卡片同一套写法）。
3. **对比度审计**（`ColorContrastTest`，纯 Kotlin WCAG 计算，读真实 `iOSColorScheme` 色值）：
   - 正文角色 `onSurface`/`onBackground` 在全部真实背景上 ≥4.5:1：实测最小
     **浅色 13.80、深色 9.12**，通过。
   - **发现：浅色 `onSurfaceVariant` 只有 3.13~3.44:1，不满足正文文字的 AA。**
     深色最差 3.95（落在 `surfaceContainerHighest`）。这是刻意贴 iOS secondary label 的结果
     （Apple `systemGray #8E8E93` 纯白底 3.26:1），不是笔误。
   - 阈值因此按角色分档：次要文字钉 3.0 下限 + **3.12 只许升的棘轮**。
   - 若要修到 AA：浅色 alpha 0x99(0.60) → 约 0xB8(0.72)，纯白底即 4.72:1；
     深色 → 约 0xAD(0.68)。**这会明显改变全站次要文字深浅，属需真机走查的设计决策，本轮不擅自改**
     （见 §5-D3 与 §6）。
   - 另加一条自检：确认半透明前景真的做了 SrcOver 合成。若有人图省事直接拿原色算，
     所有断言会集体变"更好"从而继续全绿 —— 那等于整套审计失效。
     合成空间用 sRGB 逐通道插值（与 Android 实际绘制一致），不用线性光，因为线性光会让
     浅色侧算出来偏乐观。
4. **刻意没做全局替换** 1000+ 处内联 dp：`BiliSpacing` 的 KDoc 本来就写着"不要求全局替换既有
   内联 dp"；盲替换会把语义不同的尺寸（内边距/图标/分隔线粗细/约束）塞进同一个 token，
   一次 token 调整连带改掉几十个不相干外观 —— 那是**制造**回归。改用棘轮钉住 + 只收敛语义
   无歧义的那一批。
   顺带记录一个收口慢的真因：**6.dp(111 次) 与 10.dp(80 次) 是高频值却在 BiliSpacing 里没有对应档位。**

**证据**：`DesignTokenLintTest` 10 条。上限数值是用**测试自己的扫描器**校准的（临时加一条
汇报型测试读出真值），不是 grep 出来的 —— grep 会把注释里的 `16.dp` 也算进去，实测高约 10%。

### T6 无障碍与触控目标 → `a436d012`

- **先纠正任务书的一处数字**：它写"ActionChip 内容高约 34dp + 上下 BiliSpacing.sm = 42dp，不足 48"。
  逐行核对**不成立**：图标 22 + 间隙 2 + labelMedium 行高约 16 = 40 内容，加上下各 8 内边距 ≈ **56dp**，
  本来就够。所以没有"放大 ActionChip"，而是补 `defaultMinSize(minHeight = 48.dp)`：现状零像素变化，
  作用是把"靠内容算术碰巧够高"变成"声明上的保证"（日后删数值行/改小图标会不知不觉掉到 48 以下，
  而没有任何编译期检查拦得住）。
- **contentDescription**：5 处图标-only 按钮是 null。判据不是教条 —— 同仓另有 8 处返回箭头都
  规矩写了 `"返回"`、MoreVert 在另外 3 个文件里都有标签，所以这 5 处是**漏写**而非取舍。
  `TopAppBar` 的 `navigationIcon`/`actions` 槽位不会自动补标签，null 等于该按钮在无障碍树上没名字。
- 扫描按**圆括号配平取实参块**，不用单行正则：
  `IconButton(modifier=Modifier.size(32.dp))` 违规，而 `IconButton(...) { Icon(Modifier.size(20.dp)) }`
  是完全合规的 48dp 触控区 + 20dp 图标；不分清楚就会把全仓正常图标算成违规。多行
  `Icon(\n contentDescription = null\n)` 同理，单行正则扫不出来 = 假绿。
- **按出现次数而非文件数统计**：CapsuleIndicator 一个文件就有两处，按文件数会少报一处、
  让上限 3 实质变成 4。

**证据**：`AccessibilityStructureTest` 4 条 + 上述"扫法本身"的三道防误判设计。

### T4 组合期读动画值 → `f67ba8d0`（棘轮 10 → 8）

审查全部调用点（基线实测 9 处，上限原本留 1 空档）。修掉 `IOSControls.IOSSwitch` 滑块：
`animateDpAsState` + `offset(x = )` → `Animatable<Float>`(px) + `offset { }`。

**换写法唯一的坑是初值**：`animateXAsState` 首次组合**直接吸附**到 targetValue，
而 `Animatable` 会**从初值动画过去**。写 `Animatable(0f)` 会让设置页所有"默认就是开"的开关
在首帧把滑块从左滑到右。故初值取 `if (checked) travel else 0f`。

剩下 8 处**逐条写明去向**（在棘轮注释里）：3 处其实合规（值在 `graphicsLayer{}` /
`offset{}` 内读，正则看不见读取阶段）、4 处是喂给 `Text(color=)`/`Icon(tint=)` 的颜色渐变
（组合期入参、无等价绘制期写法，且重组只到那一个叶子）、1 处是真正未修的宿主级读值
（`HomeVideoPage` 封面取色 350ms 淡入）——它只在取色回来那一刻触发一次，**不在滚动路径上**，
真要修得先把卡片颜色管线改成"底色由绘制期承担"，那是重构不是调优（§6 遗留项）。

### T1 拆逐帧全屏重录链 → `403ba19a`（做到终态的一半）

结构核实：`HomeScreen` 在根 `Crossfade` **里面**，而 Crossfade 已把同一块屏幕录进
haze 源与 miuix 背板（供 Dock/胶囊），首页又为自己的顶栏录一份私有源 →
**同一片像素每帧录两遍 1272×2800**。

1. **haze 源与背板源改为互斥挂载**（根 + 首页两处）。依据是读出来的控制流：
   `GlassEffect` 的 LIQUID 分支拿到背板后 `return liquidGlassSurface(...)` **提前返回**，
   根本走不到 haze 那段 → 默认 LIQUID 档下 `hazeSource` 是纯粹白录一整屏。
   根节点那一份影响**四个 Tab**，比首页那份更值。
   仍照常 create + provide `HazeState`：`isGlassActive(state)` 要求 state != null 才承认玻璃生效。
2. **`GlassSourceRefresher` 加 tick 门控**：顶栏完全滚出屏幕后首页私有源没有任何可见消费者。
   滚动中顶栏**本来就是隐藏的**，所以砍的是滚动路径上大部分时间的第二份录制，不是边角情况。
   根节点**不加**门控（Dock/胶囊那时仍可见）。门控 lambda 在 nestedScroll 分发里读 snapshot
   state：组合期之外读不登记观察、不触发重组。
3. **纠正 `GlassRefresher` 的误诊**（任务书 T1.3 点名要复核的）。原始三轮实测数据照抄保留，
   但结论有两处独立缺陷：**(a) 论据用反了** —— `Number Slow issue draw commands` 统计的正是
   "提交绘制命令"阶段超预算的帧数，不是合成/GC 停顿，而每帧两遍全屏重录恰好往这个阶段加活；
   **(b) 样本量不足** —— 同构建不改动时 jank 能从 1.70% 掉到 0.00%，两组各 3 轮、中位数
   1.06% vs 1.27% 整个落在噪声带内，那是"测不出来"不是"证明无效"。
   当前立场改写为**"抽帧是否有效未知、待设备重测"**（§5-D2）。

**没做到终态（只剩 1 处 tick）**：要做到必须把各 Tab 的悬浮顶栏从 `hazeSource` 子树提到与
Dock 同级，否则顶栏采样含自身的背板 → 自采样鬼影（那正是当初建私有源的理由）。
架构级 + 必须真机走查玻璃观感，列为 §5-D1，**不当已完成**。

**证据**：`GlassRecordChainStructureTest` 4 条。已实测把 `homeHazeOn` 改回 `homeGlassOn` →
`hazeAndBackdropSourcesAreMutuallyExclusive` FAILED（不是空过）。

### T2 Dock 合并动画整树重组 → `51a5d26d`

病链：合并动画每帧改 Dock 布局高度（`LinkedDockPolicy`：`height = top + barHeight`，
`top = (barHeight+gap)*(1-merge)`）→ `onGloballyPositioned` 每帧写遮挡比 →
AppNavHost 在 `provides bottomNavOcclusionRatio` 处**组合期**读它 → **280ms 里每帧重组整棵主框架树**。
`onSizeChanged { hiddenDistancePx = it.height }` + 组合期读它的 `animateFloatAsState` 是同一病的第二入口。

两处都改成**只在读到更大值时写**。这不是权宜：merge 只会让高度变小，所以历史最大值
**按构造**就等于展开态高度，正是任务书要的"测展开态固定值"，且让稳态写入归零。
偏大是安全方向。几何缓存用普通 `FloatArray` 而非 `mutableStateOf` —— 一旦被组合观察就
又变回"每帧写→每帧重组"。

读取侧：`LocalBottomNavOcclusionRatio` 由 `Float` 改 `() -> Float`，三个消费者的计算挪进点击路径。
**顺带修掉一个潜在正确性 bug**：遮挡比是 Dock 布局回调**异步实测**的，常晚于页面首次组合；
旧写法靠 `remember` 的 key 变化跟进，一旦哪天漏了一个 key 来源就会把"还没测到"的 0f 永久缓存
→ 返回动画末端卡片被 Dock 切一刀。点击时算则永远取当下值。

**纠正原注释的一处误导**：`:840` 写"隐藏动画只是 graphicsLayer 平移、布局位置不变，
因此常驻遮挡区恒定"——这对**隐藏**成立、对**合并**不成立，布局高度确实在变；
那句"恒定"正是没人去查每帧写入的理由。

### T7 继续移植上游手法 → `208a04ac`

- **§7-8 做了**：`onTrimMemory` 分级抽成纯函数 `MemoryTrimPolicy` + 7 条 JVM 断言。
  值得抽的理由是这段逻辑**分派错了既不会崩也不会进日志**，代价要么是回前台整屏封面重解码、
  要么是该放内存时没放被系统杀；以前只能真机 `am send-trim-memory` 验。
  `reason` 字段刻意由策略给出并单独测了"分支间必须可区分"（真机查内存问题时它是唯一能 grep 的东西）。
  原 `onTrimMemory` 上那段分级 KDoc 一并搬到策略文件，避免两处文档各自漂移。
- **§7-2 做了**：`ScrollStatePersistenceStructureTest`。先核实了任务书前提 ——
  `rememberLazyListState()`/`rememberLazyStaggeredGridState()` 内部本就是 `rememberSaveable` + Saver，
  所以"页面缺持久化"对 BBSpace 不成立；真正的静默缺陷是**绕过工厂直接 `LazyListState()`**（没有 Saver，
  且只在转屏/进程重启后才显现）。钉成"除 VideoDetailPage 外零处"，并把那 3 处豁免本身钉死
  （必须恰好 3 处、必须包在 `remember(aidKey)` 里）。那 3 处经核查是**有意**的：
  换视频要重置位置，且 `VideoDetailActivity` 声明了 `configChanges`（转屏不重建）。
- **§7-6 未做（不适用）**：任务书设想的 `derivedStateOf { isScrollInProgress }` 在 BBSpace **不存在**，
  首页/直播/IM 的滚动态都已是 `snapshotFlow` 形态。唯一一处 `ImConversationScreen:70-85` 是
  翻页阈值判断、只在滚动起止时翻一次，不是每帧绘制观察 —— 套 `withoutReadObservation`
  属于换写法而无收益。已记入 §6。

### T8 代码逻辑审查 → `73fc938e`

审查清单产出 16 条 + 5 条"需运行时证明"。**只修静态可判定且确有可达路径的 4 条**（每条我都
用 Read 复核过，不采信未验证的结论）：

1. `VideoScreen` 的 `DisposableEffect` 里 `onDispose { }` 是空的 → 经 AnimatedVisibility
   直接 dispose 时 `fullOn` 仍为 true，Activity **永久留在横屏**。同文件管 systemBars 的
   兄弟 effect 就正确做了 restore，说明是漏写。
2. `VideoPlayerPane` 播放期间**整块面板每秒重组 60 次**：`LocalVideoGlassTick` 原本是
   `staticCompositionLocalOf<Int>`，交出**数值**，于是 provides 那句必须在组合期读
   mutableIntStateOf，而 tick 的唯一用途是让 drawWithContent 在绘制期强制重录。
   改成 `() -> Int` provider，读取全落到绘制期（与 T2/T4 同一手法）。
   并把 `while (state.playWhenReady)` 改成 `while (true)` 加注释：`state` 是 effect 启动时的
   快照，循环里再读永远得到当时的值，那个条件形同虚设还会误导读者。
3. `favRidType()` 在不支持收藏的 biz 上会 throw，而它被写在 `runCatching` **外面**、
   `viewModelScope.launch` 无异常处理器 → 未捕获协程异常 → 崩。
   **报告说两处都中招，我核了调用方：`submitFav` 上游有 `if (aid <= 0L) return` 挡掉，那处当前不可达；
   `openFavSheet` 只挡了未登录，所以课程/SCG 点收藏是能走到的。** 两处都挪进去（一致，且不改变 submitFav 行为）。
4. 下载播放 `open` 被取消后 `isPreparing` 永久留 true（StateFlow 活得过协程取消）→ 一直转圈。
   复位加了两个守卫（仍是本次 task 且仍 isPreparing），否则会引入新 bug：
   晚到的取消覆盖掉更新的一次 open。

另把 `VideoViewModel` 里 **8 处**包挂起调用的 `runCatching` 全量换成项目自带的
`runCatchingCancellable`（标准库版本把取消吞成 failure → 弹假的"点赞失败"提示并在已清理的 VM 上
继续写状态；`tripleFallback` 更会在已死的 scope 上继续补发请求）。

未修的已定位条目见 §6。`!!` 一类：全仓这三个模块**没有** `!!`（唯一非空断言是
`PlaybackService.kt:123` 的 `checkNotNull`，其前置分支保证非空）。

### 附带修掉一个 CI 级假绿 → 含在 `e64a20de`

`core:designsystem` 的棘轮/结构测试读的是**仓库里的 .kt 文本**，不是模块 classpath 依赖，
Gradle 不认为那是测试输入。实测：改坏 `HomeVideoPage.kt` 后跑
`:core:designsystem:testDebugUnitTest`，**4 秒 BUILD SUCCESSFUL**（任务被判定 UP-TO-DATE 跳过）；
加 `--rerun-tasks` 才跑出 3 个 FAILED。在 CI 上等价于"第一次跑过之后永远绿"。
已把全仓 main 源码显式登记为测试输入。这正是任务书 §8 最强调的那类假绿。

---

## 3. 棘轮 / 阈值变更清单（任务书 §10.3）

| 测试 | 项 | 旧 | 新 | 方向 | 理由 |
|---|---|---|---|---|---|
| `FrameBudgetLintTest` | `maxAnimateAsState` | 10 | **8** | ↓ | T4 修掉 IOSControls 滑块；剩 8 处逐条写明去向（含 3 处"计数但实为合规"） |
| `DesignTokenLintTest` | 全仓内联 `typography.x.copy(` | (新) | **6** | 新建 | T3/T5 修掉信息流卡片那 1 处；其余 6 处不在滚动路径，冻结防扩散 |
| `DesignTokenLintTest` | theme 包外 `N.dp` 字面量 | (新) | **962** | 新建 | 基线 961；T6 为操作栏补 `defaultMinSize(minHeight = 48.dp)` **+1**。48dp 是 Material 规范常量、不是设计标度档位，做成间距 token 会让"哪天调间距顺手改掉无障碍下限"，故保留字面量并写明理由 |
| `DesignTokenLintTest` | 16/12/8/6/10 dp 单项 | (新) | 174/143/149/111/80 | 新建 | 高频值单独钉；6 与 10 在 BiliSpacing 里**没有档位**，先防扩散 |
| `DesignTokenLintTest` | `N.sp` 字面量 | (新) | 4 | 新建 | 字号已基本收口，防回潮 |
| `ColorContrastTest` | 次要文字下限 | (新) | 3.0 / 3.12 棘轮 | 新建 | 见 §5-D3：浅色 onSurfaceVariant 现状 3.13~3.44，按角色分档而非一刀切 |
| `AccessibilityStructureTest` | `IconButton` 显式 size<48 | (新) | 3 | 新建 | 放大它们会真改悬浮胶囊布局，无设备不可走查，故冻结不盲改 |
| `GlassRecordChainStructureTest` | 源侧 glass tick 读取点 | (新) | 2 | 新建 | 终态是 1，路径见 §5-D1 |
| `ScrollStatePersistenceStructureTest` | 直接构造列表状态 | (新) | 3（全在 VideoDetailPage） | 新建 | 唯一合法豁免，且要求必须包在 `remember(aidKey)` |
| `DockMergeRecompositionStructureTest` | — | (新) | 顺序断言 | 新建 | 无上限，钉"去重判断必须先于写入" |

`maxRunBlocking` 等前置工作的 2→3 变更记录是前人留下的，未动。

---

## 4. 静态验证证据索引

| 测试文件 | 条数 | 覆盖 |
|---|---|---|
| `core/designsystem/src/test/.../core/perf/ListAllocationStructureTest.kt` | 8 | T3 |
| `.../core/perf/DesignTokenLintTest.kt` | 10 | T5 |
| `.../core/perf/GlassRecordChainStructureTest.kt` | 4 | T1 |
| `.../core/perf/DockMergeRecompositionStructureTest.kt` | 4 | T2 |
| `.../core/perf/ScrollStatePersistenceStructureTest.kt` | 4 | T7 |
| `.../core/designsystem/theme/ColorContrastTest.kt` | 4 | T5-对比度 |
| `.../core/a11y/AccessibilityStructureTest.kt` | 4 | T6 |
| `app/src/test/.../memory/MemoryTrimPolicyTest.kt` | 7 | T7-onTrimMemory |

新增测试文件的两个共同防假绿设计（照抄 `FrameBudgetLintTest`）：
`countInSources` 跳过注释行 + import 行；以及**每个断言先确认目标文件真的读到**
（本类的断言多为"某文件里没有某种写法"，文件读不到就会得到"没有违规"的假绿，
所以用 `throw AssertionError` 而不是 `assertTrue(file != null)`）。

**破坏性验证已做**（证明测试会咬人）：
- T3：注入一处内联 `typography.copy` + 一处局部 `RoundedCornerShape` → 3 条 FAILED。
- T1：把 `homeHazeOn` 改回 `homeGlassOn` → `hazeAndBackdropSourcesAreMutuallyExclusive` FAILED。
- T7 的对比度自检 `alphaCompositingIsActuallyApplied` 常驻验证"合成这一步没被偷懒"。

---

## 5. 待设备验证（以下都不是已完成结论；D5 已部分执行，见 §5-A）

| # | 项 | 预期信号（怎么判断成立） | 若失败的判据 |
|---|---|---|---|
| **D1** | T1 终态：把各 Tab 悬浮顶栏移出 `hazeSource` 子树，与 Dock 同级共用根 `glassState`，删掉首页私有源 | `GlassRecordChainStructureTest` 的 tick 点数可降到 1；滚动时 LIQUID 顶栏无自采样鬼影、无残影；首页 jank 相对当前基线下降 | 顶栏出现自身像素被采样（鬼影/回授）→ 说明必须保留私有源，此路不通，应回退该步并把棘轮维持在 2 |
| **D2** | T1.3 重跑 stride 抽帧实验（原结论已判为误诊，见 §2-T1） | ≥5 轮取中位、并同轮跑 `Slow issue draw commands` 计数；若抽帧显著降低该计数则原"无效"结论作废 | 若多轮后 jank 与 draw-command 计数都不动，则原实测方向成立、抽帧确实无收益 —— 应把 §2-T1 的措辞从"未知"改回"已排除" |
| **D3** | 浅色 `onSurfaceVariant` 3.13~3.44:1 要不要提到 AA 4.5:1 | 改 alpha 0x99→约 0xB8 后真机走查全站次要文字观感（列表副标题、时间、计数、未选中 Tab） | 若明显变重、破坏 iOS 层级感 → 维持现状，并把 `ColorContrastTest` 的 3.12 棘轮注释改成"经设计确认为刻意" |
| **D4** | T6 那 3 处 `IconButton` 显式 size<48（胶囊播放/关闭 32dp、移除图片 20dp） | 放大到 48dp 后：胶囊不撑高、缩略图角标不重叠、相邻按钮命中区不互抢 | 任一成立 → 保持冻结并在注释补"设备验证过不可放大"；否则把棘轮往下调 |
| **D5** | 本轮所有性能改动（T1/T2/T3/T4）的综合效果 | 按任务书红线：首页 jank ≤0.80%、p95 ≤8ms；≥3 轮取中位 | **部分执行**：A 组（当前代码）已测 5 轮，B 组（优化前）未测 → **无差值、无结论**；且红线是 release+60Hz 口径，本机只能测 debug+120/144Hz。详见 §5-A |
| **D6** | OPPO 无缝 morph 是否被 T2 的"展开态遮挡比"改动影响 | 打开/返回动画末端遮挡渐隐正常、卡片不被 Dock 切一刀 | 若返回动画几何异常 → 优先怀疑 `BUNDLE_LIST_COVER` 的比例来源，回退 T2 的 provider 改动 |
| **D7** | T8 里 5 条"需运行时证明"的疑点 | 见 §6 末尾列表 | — |

---

## 5-A 设备实测记录（USB 接入后补，2026-09-22 下午）

设备：`PLC110`（序列号 3B6F6KE8QDS3NVBU），1272×2800 / density 560 / Android 16 —— 与任务书同一台。

### 先说四个方法学事实（它们决定了这批数字能用来干什么）

1. **只能测 debug 变体。** `app/build.gradle.kts` 的 `hasReleaseSigning` 依赖
   `keystore.properties` / `SIGNING_*` 环境变量，本机**都不存在** → release 产物未签名、装不上。
   好在 debug 有 `applicationIdSuffix = ".debug"`，包名 `com.naaammme.bbspace.debug`，
   与正式包**并存且数据隔离**，不会动到已登录的 release 应用。
   代价：debug 无 R8、带 Compose 调试开销，**绝对值与 release 基线完全不可比**。
2. **面板没有锁在 60Hz。** `settings get system peak_refresh_rate` = 120.00001，
   当前 display mode 是 `id=1 / vsyncRate=144.00002`。任务书的红线（jank ≤0.80%、p95 ≤8ms）
   是按 60Hz / 16.67ms 预算定的，所以**这批数字不能拿去对红线**，只能做同条件下的相对比较。
3. **热节流是误报，别被吓到。** `dumpsys thermalservice` 里 `CPU 104.4°C mStatus=5`
   看着像紧急降频，实为该列表里的**陈旧重复条目**；同一份输出里活读数是
   `CPU 39.5°C / SKIN 34.4°C / BATTERY 32.8°C，全部 mStatus=0`（无节流）。
   下次判热状态要看 `mStatus=0` 那一组，不要看第一条。
4. **A/B 要用 worktree，不要用 `git checkout <旧提交>`。** 我为了测 B 组把仓库切到了
   `63d750fb`（detached HEAD），工作区随之回退到那个时间点，
   **`docs/…执行报告.md` 从磁盘上消失了**（文件在 git 里没丢，切回分支即恢复）。
   在用户正在看的仓库里这么干会造成"东西被删了"的误判，属于我该提前想到的副作用。
   正确做法：`git worktree add ../bbspace-before 63d750fb`，两边并行、互不干扰。

### A 组：当前 HEAD（含 T1–T8 全部改动），debug，5 轮

`scripts/perf-baseline.ps1 -Pkg com.naaammme.bbspace.debug -Rounds 5`

| 轮次 | frames | jank | p50 | p90 | p95 | p99 | slowDraw | slowUi | missedVsync |
|---|---|---|---|---|---|---|---|---|---|
| r1 | 877 | 2.05% | 9ms | 12ms | 16ms | 40ms | 6 | 18 | 8 |
| r2 | 711 | 2.39% | 9ms | 14ms | 18ms | 48ms | 4 | 16 | 8 |
| r3 | 915 | 1.31% | 8ms | 10ms | 12ms | 25ms | 1 | 12 | 5 |
| r4 | 986 | **0.10%** | 7ms | 9ms | 9ms | 10ms | 1 | 1 | 0 |
| r5 | 990 | **0.20%** | 12ms | 13ms | 14ms | 19ms | 2 | 0 | 0 |

另有一次未预热的首轮（脚本 warmup 之前）：693 帧 / **13.85%** / p50 11 / p95 42 / p99 121，
`slowUi=96`、`slowDraw=15`。

### B 组：**未测**（被中断）

B 组的包（`63d750fb`，即我 8 个任务提交之前的最后一个提交）已经构建并安装成功，
应用也已启动到首页信息流，但 5 轮测量在开跑前被中止。
**因此本报告不给出任何"改善了多少"的数字** —— 没有对照组就没有差值，
拿 A 组去比任务书里的历史 release 数字也不成立（变体不同、刷新率不同、热状态与后台都不同）。

### 这批数据里唯一站得住的一条结论

**debug 变体上，jank 由 UI 线程主导，不是绘制成本。** 未预热那轮 `slowUi=96 / slowDraw=15`，
A 组 r1–r3 也是 `slowUi 18/16/12` 对 `slowDraw 6/4/1` —— 每个 jank 帧几乎都记在
`Number Slow UI thread`（组合 + 布局 + 测量）上，而 `Slow issue draw commands` 只是零头。

这与任务书 §3.1 里"jank 不是逐帧绘制成本超预算"的**方向**一致，
但**不等于**当初那个 stride 结论是对的：当初的论证把 `Slow issue draw commands` 当成了
"合成/GC 停顿"的证据（那是误读，它统计的正是提交绘制命令超预算的帧），
而它的样本又落在同构建 1.70%→0.00% 的方差带内（那是测不出来）。
也就是说：**结论碰巧可能对，但推理是坏的** —— 这次是换了个指标（slowUi vs slowDraw 的比值）
才真正支撑住它。这条要等 release 变体上复现才算数（debug 的 UI 线程开销天然被放大）。

实践含义：本轮做的重组类优化（T2 整树重组、T3 卡片分配、T4 组合期读动画、
T8 播放面板每秒 60 次重组）是**对症**的；而"再省一次全屏离屏录制"这类绘制侧优化，
至少在 debug 上不是主要矛盾。

### 一个值得单独立项的观察

A 组 r4/r5 已经到 0.10% / 0.20%，而 r1–r3 是 2.05% / 2.39% / 1.31% ——
**这个形状和任务书 release 基线（r1 1.70 → r4 0.00）几乎一样**：
jank 几乎全部来自**前几轮的预热**（图片解码、字体、JIT/AOT 尚未热），
稳态滚动本来就没有可观测的抖动。

推论（待验证）：任务书写的目标"把 1.1~1.7% 压到 ≤0.80%"，那个 1.1~1.7% 本身就是
预热轮的读数，而稳态早已优于红线。**真正该优化的是冷启动/首屏预热路径**
（首帧解码、字体加载、profile 编译状态），而不是继续压稳态滚动。
下一轮若沿用"滚 4 轮取中位"的口径，很可能测不出任何差异 —— 因为要压的东西不在那里。

---

## 6. 未完成 / 未修项及原因

**任务书项**
- T1 只做到"减少第二份全屏重录 + 门控 + 纠正误诊"，未做顶栏上提（架构级、需 D1）。
- T5 未做全局 dp 收敛（理由见 §2-T5.4）；未做 §7-2 的 `compose_stability.conf`
  —— 任务书判定"模型层已 173/195 标 @Immutable、收益不大，先核查那 22 个"，本轮未做该核查。
- T7 §7-6（`Snapshot.withoutReadObservation`）**不适用**：目标形态在 BBSpace 不存在（§2-T7）。
- T6 未盲改 3 处小触控目标（D4）。

**T8 已定位、静态成立、但本轮未修的 8 条**（都不是设备依赖，是本轮取舍；建议下一轮逐条做）
1. `VideoViewModel.submitFav` 在挂起前抓 `_favSheet.value` 快照、`onSuccess` 用它算乐观更新
   → 提交期间用户仍可改勾选，结果可能与实际发出的不一致。修法：在 `onSuccess` 内重读 `_favSheet.value`。
2. `VideoDetailPage.closeSheet` 在 `rememberCoroutineScope` 上挂起后才执行真正导航
   → 中途 dispose 会**静默丢一次分P/选集切换**。修法：先置 `sheet = null` 再 hide，或由非组合 scope 驱动。
3. `SbSkipType.SHOW_OVERLAY` 落进 `else -> Unit` 且全仓无人读 → "仅显示"与"禁用"等价。
4. `openFavSheet()` 未登录早退后 sheet 仍被置为打开态 → 渲染成"暂无收藏夹，新建一个吧"。
5. `VideoScreen` 的 cover-backdrop `dismissed`/`alpha` 未按 ids 归键 → 切下一条视频封面黑帧
   （旁证：`:160` 的 `seamlessIdsKey` 算了却从未被引用）。
6. `dragMs` 只在 `onDragEnd/onDragCancel` 清 → 控件层 AnimatedVisibility 中途 dispose 会把进度条
   **永久冻在拖动位置**。修法：`PlayerCtrlBarHost` 里 `DisposableEffect(Unit){ onDispose{ onDragMsChange(null) } }`。
7. `viewModel.coinBalance()` 作为 composable 实参在 **UI 线程** 读 SharedPreferences + 解析 JSON，
   且每次重组都读（与本轮已修的 `UserViewModel` 同一类问题）。修法：改成 off-main 的 StateFlow。
8. `RemotePlaybackReporter` / `StreamPlaybackSessionImpl` / `VodDanmakuSession` 同类取消吞没
   （VodDanmakuSession 只补了一半，仍在已取消的 job 上改 `loadingWindowId/loadingJob`）。
   另有 `CoverExpand.kt:192` 一处冗余 cast（`w: No cast needed`，构建时可见）。

**"需运行时证明"的 5 条疑点**（未列为缺陷）：单视频循环时 `PlaybackState.Ended` 是否先于 media3
loop 到达胶囊；`StreamPlaybackSessionImpl:467-477` 取消尾声写 `_currentTarget` 未复查 token
（可能覆盖更新的会话）；`VideoViewModel:139-146` 交互复位以 `Triple(aid,…)` 去重 → 分P 切换残留 delta；
`VideoPlaybackSheet:133` 的 `maxContentHeight != Dp.Unspecified` 疑似恒真；
`VideoPlayerPane:750` 直读 `playbackProgress.value` 未收集 —— **注意它目前被本报告 §2-T8.2
修掉的那个"每秒重组 60 次"意外掩盖着**，那个修好之后这条可能显形，属 D7 首要观察项。

---

## 7. 任务书 §5 验收对照

| 条 | 状态 |
|---|---|
| 1. `:app:assembleDebug` 成功、无新增 `e:` 与 `w: never used` | ✅（最终态 BUILD SUCCESSFUL；无 `e:`；无新增 never-used 警告。遗留 3 条既有 `w:`：FlowPreview、`?:` 恒返左值、CoverExpand 冗余 cast —— 后一条已入 §6） |
| 2. `testDebugUnitTest` 全绿 | ✅ 116/0/0 |
| 3. 该项静态断言已落地 | ✅ 8 个新测试文件，见 §4 |
| 4. 动过棘轮上限都在注释写明理由 | ✅ 见 §3，逐条带理由 |
| 5. 注释解释"为什么"、记录踩过的坑 | ✅（含 `RoundedCornerShape` 默认角的反编译查证、Animatable 初值坑、stride 误诊纠正） |
| 禁止：声称帧率/内存数字 | ⚠️ **初稿成立、现已修正**：接入设备后有 debug 实测数字，但它们**不构成改善幅度的证据**（无对照组，且变体与刷新率都和基线不同）。§5-A 把口径与限制写在数字之前，并把唯一站得住的那条（debug 上 jank 由 UI 线程主导）连同"当初推理是坏的、结论碰巧可能对"一起说明。WCAG 对比度仍是纯算术、非性能测量。 |
| 禁止：放宽断言换取通过 / `@Suppress` 掩盖 | ✅ 未放宽任何断言。两处 dp/sp 上限变化均为**新增字面量的显式记账**（§3）。中途误加过一个 `@Suppress("FunctionType")`，已删除 |
| 禁止：把"待设备验证"标成完成 | ✅ 全部集中在 §5、§6 |

---

## 8. 一句话交接

滚动路径上的可静态证明的浪费基本清完：**每帧少一遍全屏离屏重录、少一份卡片级对象分配、
顶栏与 Dock 的动画不再按帧重组整树**；`animate*AsState` 10→8，71→116 条测试，
外加一条 CI 级假绿被修掉。

设备侧只完成了一半：**A 组（当前代码）测了 5 轮，B 组（优化前）没测**，
所以"改善了多少"这个问题目前**没有答案**，jank 是否进 0.80% 红线也没有答案
（何况红线本身是按 release + 60Hz 定的，而本机只能测 debug + 120/144Hz）。

## 9. 下一轮建议（供你定目标时参考，不是我已做的事）

按"能测出差异"的价值排序：

1. **先补 B 组，用 worktree 而不是 checkout**（`git worktree add ../bbspace-before 63d750fb`），
   同一台机器、同一热状态、A-B-A 三轮交替，才能把"我这 8 个提交到底值多少"说清楚。
   否则前面所有性能工作都停留在"静态上应该更好"。
2. **把测量条件对齐红线**：要么补上签名配置以测 release，要么明确放弃与
   任务书历史基线的绝对值比较。当前 debug 数字与 release 基线差一个数量级
   （稳态 0.1~0.2% vs 未预热 13.85%），混用只会得出错误结论。
3. **重新定义目标**。§5-A 最后一段是这次实测最有用的产出：jank 几乎全在**预热轮**，
   稳态滚动本来就干净。若下一轮仍按"滚 4 轮取中位"考核，大概率测不出任何差异 ——
   要压的东西在冷启动/首屏解码/编译预热路径上，不在稳态滚动里。
4. **UI 侧还剩一个待你拍板的设计决策**：浅色 `onSurfaceVariant` 3.13~3.44:1
   不满足正文文字的 WCAG AA（详见 §5-D3）。现在有了设备，改完可以直接截图对比再定。
5. §6 那 8 条 T8 已定位未修的逻辑缺陷，都不依赖设备，可以离线逐条做掉。
