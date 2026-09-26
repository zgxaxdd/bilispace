# FINDINGS —— 调研沉淀（只收性能 / 稳定 / 视觉手法）

> 任务书功能冻结令：**禁止在此记功能点子**。这里的每一条都必须能回答"它让已有功能更稳/更快/更漂亮"。

## 1 · 稳定手法

**S1 裸 `runCatching` 会把「取消」当「失败」。**
快速切页 / 重试取消时，`runCatching { suspendCall() }` 捕获 `CancellationException`，
下游渲染成错误态或空态。本仓已收口 32 处（`runCatchingCancellable`），
守卫是 `VideoLogicGuardTest.cancellationSwallowingIsClosedInFeatureViewModels`
（文件清单 + "必须真的在调用"，防止改成 import 就完事）。
**复用方式**：新增 ViewModel 时先看这条守卫的清单；批量清理时同一套防假绿写法。

**S2 陈旧响应回灌用「代际号」而不是时间戳。**
`CommentViewModel.threadReqId` 的写法：每次发起 +1，回调时不匹配就丢弃。
比 `Job.cancel()` 更容易被静态断言，也扛得住乱序返回。

**S3 状态机不要留旁路标志位。**
`PlaybackHostState.forcedDismissMode` 把"该收起小窗"和"该关闭播放"混进一个布尔，
进程重建后返回键被吞。收成 `collapseToCapsule` + `onPlaybackUiStarted` 单一入口后，
`PlaybackHostDismissGuardTest` 钉住"不许再引入同类旁路"。
**泛化结论**：一个标志位承载两种意图 = 后续每个特判都要在该标志上再叠一个特判。

**S4 枚举解析要有脏值兜底。**
`AppSettings.buildThemeConfig` 原来 `valueOf` 直接抛 —— DataStore 里留一个跨版本脏字符串
就会让整条主题流失效（表现为全站主题回默认）。`runCatching{…}.getOrNull() ?: default`。
同理适用于所有"手改/降级残留"可能的存储读取点。

**S5 三态优先级：错误态必须压过空态。**
`AdaptiveMediaGrid` 原来有错误也显示"暂无内容"。网络失败对用户而言不是"空"，
把失败说成空 = 用户以为数据没了。审其它界面按同一判据。

**S6 数据库的"静默清库"永远比"开发期崩溃"坏 —— 所以 `fallbackToDestructiveMigration` 一律禁用。**
它把"改实体忘写迁移"这一类缺陷从**开发者当场红**降级成**用户数据没了且没有任何日志**。
本仓 `PlaybackHistoryDb` 就是这么清掉过用户的本地观看历史（B11/ST-02）。
判据：升级路径要么给真实 Migration，要么显式写一条"就是要把这张表清掉"的 Migration 并说明理由 ——
后者会出现在 diff 里、会被 review，前者不会。
**配套**：只禁回退还不够，必须同时 `exportSchema = true` + 快照入库，否则连"改了什么"都无从差分。

**S7 单例共享资源的"所有权"要按物理事实建模，别用引用计数粉饰。**
`PlayerEngine` 是 `@Singleton`，内部就一个 `exoPlayer` —— 两个会话**不可能**同时拥有媒体，
后一次 `setSource` 已经物理覆盖了前一次。此时引用计数会假装并存，把"被抢走"这件事藏起来；
正确模型是**抢占式 claim**：写资源前声明归属，只有占用者能拆（`release` 交还、`stopForReuse` 保留）。
两个细节：**claim 必须写在动作之前**（写在成功后会出现"我拆的但没人是 owner"的真空，
失败收尾就再也清不掉）；**同类破坏动作要一起守**（`stopForReuse` 与 `release` 一样打断别人，
只守一个等于留一半洞）。见 B13 / ST-07。

## 2 · 流畅手法

**P1 缓存键必须同源。**
取色回退请求写死 `.memoryCacheKey(原始 URL)`，显示请求用 Coil 从 `data` 派生键
→ 同一封面下载两次、解码两次、缓存两份。规则：**任何"为同一资源服务的第二条请求"不许自定义键**。

**P2 静止界面必须停掉逐帧驱动。**
播放页暂停后玻璃 tick 仍以 60fps 空转（15s 渲染 909 帧 → 修后 0 帧，真机 A-B-A 已测）。
这类浪费**不进 jank 指标**，只烧 GPU/电量，所以"jank 0.00%"不能当作没问题。
判据：`LaunchedEffect` 里凡是在推 refresh/invalidate，都要有退出条件。

**P3 组合期不读动画值 → 几何参数改 lambda。**
`TrackGeometry.filledRight` 由值改 `() -> Float`，只在 `Canvas{}` / `offset{}` 块求值，
每帧推进就不触发重组（`FrameBudgetLintTest` 的红线）。这是"平滑外推"和"不重组"共存的唯一姿势。

**P4 SharedPreferences 首次访问 = 调用线程读盘 + 解析 XML。**
把它放在首帧组合里（哪怕包在 `runBlocking` 里）会让"优化"比基线慢 ~5%（真机实测）。
正确做法：构造期后台线程整体加载 → 首帧只读 `@Volatile` 引用（纯内存）→ 读不到才回退慢路径。
**另一个坑**：镜像若只在"写设置"时生成，从不改设置的用户永远没有镜像，快路径形同虚设
→ 必须在**首读**时也回填快照。

**P5 自建缓存必须登记进 trim 分级。**
`coverMemo`（无缝动画软件位图 LRU，约 8MB）原先不参与 `MemoryTrimPolicy`。
新增 `SeamlessCoverAction`（Keep/TrimToRecent/Clear）并让 `BiliApplication.applyTrimPlan` 收口。
注意：`ImageCacheAction.Nothing -> return` 这种写法会**短路掉后续新分支**，要改成 `-> Unit`。
trim 真机效果：TOTAL PSS 132.5 → 91.8MB（整链效果，不能记在单一分支名下）。

## 3 · 视觉手法

**V1 角色色要单一真值源 + 一致性断言。**
`BiliColorRoles`（一级/二级/三级文字、separator、fill）与 `iOSColorScheme` 必然漂移，
除非用测试钉死：`iosSecondaryLabelMatchesScheme`、`iosSeparatorMatchesScheme` 两条断言。
**这是"新增语义层"的标准配套**——没有一致性断言的 token 层等于制造第二个真值源。

**V2 对比度按角色分档，不要一刀切 AA。**
Apple secondaryLabel 语义本身约 3.2:1；三级文字（占位/时间戳）本来就"存在但退后"。
本仓策略：次要文字设棘轮地板（现 4.5，浅色已提 AA），三级文字只设"退化到看不见"的地板 1.6，
并在测试注释里写清"要更可读请换角色，不要抬 alpha（会破坏与 Apple 的取值对齐）"。

**V3 深色分层不取纯黑，取"同色相低饱和阶梯"。**
通行做法（Telegram #17212B / Discord #313338 / Spotify #121212）：以 tint 色相给
底→卡→容器→浮层上 4–10% 染色，层间连续可辨。纯黑留给 OLED 档（`isPureBlack`），
且此时用 7% 白发丝描边替代染色分层（卡片在纯黑上无层次）。

**V4 同心圆角规则。**
`dockChip = dock − 内边距`（32−8=24）、`playerControl = 高度一半`（34/2=17）。
已成文并在 `Radius.kt` 注释里 —— 新组件的胶囊/圆角按此推导，不要凭手感取整。

**V5 `card == player` 是无缝 morph 的不变量，不是巧合。**
`IOSColorSchemeTest` 有断言。改任一圆角会破坏卡片→详情页的形变动画。

**V6 结构守卫是视觉规格唯一的长期防线。**
270dp 警告框宽、56dp 行高、36×5dp grabber、发丝分隔线 —— JVM 取不到 Compose 语义树，
只能扫源码。写法三件套：**先断言文件读到了（`mainSources.size > 100`）→ 跳过注释与 import 行 →
故意改坏验证会咬人**。缺一件就是假绿。

## 4 · 流程手法（本仓踩过的坑）

**W1 A/B 对照一律 `git worktree`，绝不 `git checkout <旧提交>`。**
用 checkout 回退工作区会把用户正在看的文件从磁盘上抹掉（git 里没丢），造成"你把报告删了"的误判。

**W2 判定"某能力不可用"前，把配置的全部查找路径走一遍。**
项目级 → `GRADLE_USER_HOME` → 环境变量 → 命令行 `-P`。
曾在 `GRADLE_USER_HOME/gradle.properties` 里存在签名配置的情况下断言"release 装不上"。

**W3 帧率上限看 `mActiveRenderFrameRate`，不看 `peak_refresh_rate`（那是上限）
也不看 `supportedModes`（那是硬件列表）。** 本机面板实际 60Hz。

**W4 交接文档里的行号与数字必须自己复核再采信。**
同类任务书曾给出三处错误断言（"ActionChip 只有 42dp"实测约 56dp；要移植的写法本仓不存在；
把某日志读成"与逐帧绘制成本无关"是误诊）。复核后若发现错，改注释并写明，不要照做。

**W5 扫源码型单测会被 Gradle 判 UP-TO-DATE 跳过 —— 本会话实测复现，这是全仓守卫的系统性漏洞。**
机制：这类测试在运行时自己 `File(...).readLines()`，**被扫的文件不是测试任务的声明输入**；
`Test` 任务只以 `testClassesDirs` + `classpath` 为输入，所以只要字节码没变，任务就直接跳过。
实测证据：把 `PackageDirectoryConsistencyTest` 自己 `mv` 到拼错的目录（它应当立刻自检失败）→
`--tests` 跑 → **BUILD SUCCESSFUL in 22s（被跳过）**；同一状态加 `--rerun-tasks` →
`declaredPackageMatchesDirectory FAILED`。
**推论**：改注释、改测试自身、改任何不进字节码的文件，都不会让守卫重跑。
**因此本仓每次宣称"守卫会咬人"的破坏性验证，必须确认那一轮测试任务真的执行了**
（看日志里有没有 `> Task :x:testDebugUnitTest` 而不是 `UP-TO-DATE`，或直接 `--rerun-tasks`）。
修法见 BACKLOG ST-26（注册 inputs / CI 加 `--rerun-tasks` / `cacheIf{false}`）。

**W6 判断"某个 schema 版本号有没有真把用户坑过"，用 `git log -L` 看那行的历史。**
`git log -L '/version =/,+1:path'` 能直接给出该值的每次变更与提交。
审计报"Room 无 migration → 正在硬崩"，实测 `VideoDownloadDb`/`SpaceNoteDb` 的 `version = 2`
是**出生值**（首次引入即为 2）→ 没有 v1 安装可崩，缺陷要从 P0 降成"下次改实体才炸"的埋雷；
而同一条查法把 `PlaybackHistoryDb` 的 `2→3` 揪了出来，配上 `fallbackToDestructiveMigration`
就是**已经发生过**的数据丢失。同一工具，两个方向都要用它校准严重度。

**W7 结构守卫要成文"谁能引用谁"，否则同一缺陷会在没被覆盖的文件里复发。**
`ListAllocationStructureTest` 的正则盖 `RoundedCornerShape`/`composed`/`animate*AsState`，
盖不到 `Path()` 与 `Outline.Generic` —— 于是 `UserCollageSection` 里五个 Shape 内联构造、
`SelectableText` 在 `drawBehind` 里 `getPathForRange` 都不报警。**棘轮的覆盖面 = 它真正会红的写法集合**，
不是它的名字。

**W8 新建源码文件后必须核对「目录 == 包名」，全绿不代表没错。**
本仓包名是 `com.naaammme.bbspace`（三个 m 三个 a 的拼法），我把测试建到了 `com/naammmee/` 下 ——
**Kotlin 不强制目录等于包名**，于是它照样编译、9 条测试照样跑绿，躲过全部编译与测试门禁，
只有 IDE 与"按包名定位源码"的工具会撞上了 `git mv` 归位。判据：新文件写完后 `find` 一次路径。

**W9 防假绿阈值要按实测基线定，不要拍脑袋。**
`scannerActuallyReadsSources` 我凭"app 模块应该有不少文件"写了 `> 20`，实测 `app/src/main`
只有 **19** 个 .kt（本仓是薄 app 模块，业务在 core/feature）→ 第一次跑就红。
比阈值更容易空转的是"文件没读到"：所以除了数量，还要断言**目标文件名确实在扫描结果里**
（`MainActivity.kt` / `PlaybackHostState.kt` 两个都必须在），否则后面的断言全是对空集合成立。

**W10 「临时改坏一下」也必须用编辑工具，不能用 sed。**
任务书 §2.3 第一条禁的是"用 shell 批量改文件内容"，动机是**中文被写成乱码仍然合法 UTF-8、
照样编译、极难发现**。做破坏性验证时的临时改坏同样是改内容 —— 我用 `sed -i` 改过测试文件，
事后必须专门核对中文；用 Edit 工具就没这个风险，且改完即被工具记录。

**W11 `ResponseBody.string()` 会自己关流，所以"没 use 就一定漏连接"是错的。**
读完整就归还连接；漏的是 **`string()` 抛出的那条路**（连接被掐、超时、对端半关）。
判据仍然是"整段处理包进 `execute().use { }`"—— 但报严重度时要按这条算，
不要把四条 return 都算成漏连接（会把自己写的守卫变成不可信的噪音）。

**W12 同一个项目目录里绝不并发跑两个 Gradle 构建。**
本会话踩过：图省事先后起了三次 `:app:assembleDebug testDebugUnitTest`（两个后台一个前台），
结果 `:core:designsystem:testDebugUnitTest FAILED`，但去查 XML 是 **13 个 suite、failures=0、errors=0**，
日志里也没有任何一条测试级 FAILED —— 这是**测试任务输出目录被并发的另一场构建覆盖**造成的假红灯，
不是回归。判据：`--stop` 清干净守护进程后单跑一次 → BUILD SUCCESSFUL。
顺带：两次构建写**同一个日志文件名**会让日志交错成不可读的碎片，`> log` 每次用唯一名字。

**W13 Kotlin 的块注释可以嵌套** —— KDoc 里出现"斜杠+星号"的任意组合都会撑开一层注释。
在 KDoc 里写 `` `feature/*` `` 会让整个文件 `Syntax error: Unclosed comment`（Java 不会这样）。
写中文文档注释时常想写"某某/\*"表示目录通配，改成"某某 层"或转义空格。编译器会接住，但会白跑一次构建。

**W14 "看起来同类的缺陷"里藏着必须反向处理的点。**
`runCatching { save(记录) }` 出现在"网络请求已成功之后"和"请求本身"是两件不同的事：
前者传播取消会把**成功的操作改判为失败**（用户可见回归比原缺陷更大），正解是
`withContext(NonCancellable)` 而不是 `runCatchingCancellable`。
**所以批量收口必须逐个读上下文，不能按正则一把梭**；并且把"为什么不改"写成注释 +
让守卫断言注释还在（`CancellationSweepGuardTest.deferredSitesKeepTheirExplanation`），
否则下一个批量操作的人会顺手把它一起改掉。

## 4.1 待修的"设计约束"类结论

**D1 `@Singleton` 上的 `var callback` 必须有清理路径。**
`PlaybackHostState.onPlaybackUiStarted` 由 `MainActivity.onCreate` 赋值、**全仓无处置 null**，
而 MainActivity 根本没有 `onDestroy` 重写。后台播放时 Activity 可以销毁不重建（进程靠播放活着），
单例就长期持有 Activity + Compose 树 + ViewRoot。
判据：凡是"进程级单例持有 UI 层的 lambda/引用"，都要在同一文件里配一个按身份比对的清理
（无条件置 null 会在重建时序下把新实例刚装的回调清掉）。

**D2 全仓唯一的 `OkHttpClient.Builder()` 意味着所有链路共用一套超时。**
`NetworkModule.kt:60` 未设任何超时。想加 `callTimeout` 必须先确认它不含下载与流式读
（`callTimeout` 覆盖整个 call 包括 body 传输，会给几百 MB 的下载判死刑）。
**只有 `connectTimeout` 是安全的收紧**（只管 TCP 建连，不含传输）。

## 5 · 外部调研（待做，只允许性能/稳定/视觉）

- wiliwili 的低配设备优化思路（内存分级、图片降采样）→ 对照本仓 P5/V3
- Material 3 Expressive 动效规范（spring 族参数）→ 服务 iOS 升级阶段 D
- PiliPlus / BiliRoaming 的深色 `darkenTheme` 取值 → 已在 V3 采纳，待补 vibrancy 层级对照

**W15 多行 git 提交说明一律用 heredoc，正文里不要出现反引号。**
本会话用 `git commit -m "..."`（双引号）写带反引号的中文说明，bash 把反引号当命令替换执行，
`?:` 被当命令跑掉、那一段直接从消息里消失，构建与 diff 都正常 —— 只有日志烂了。
用 `git commit -F - <<'EOF'`（带引号的 heredoc）或 `git commit -m` 单引号可避免。

**W16 Room 跑完手写 Migration 后「不会」拿 schema 快照校验建出来的表，只更新 `room_master_table` 的 identityHash。**
所以少拷一列、写错 NOT NULL 在"打开库"这一刻不报错：要么静默把该列写成 NULL，
要么到第一次查询才炸，而炸的位置看起来跟迁移毫无关系。
**结论**：手写迁移的正确性必须自己兜 —— 姿势是把 **Kotlin 源码里的 `execSQL` 字面量还原成 SQL**
（不是另抄一份）与导出的 JSON 逐字比（`RoomMigrationCopyListTest`），再用本机真实 SQLite 回放整条链。
**另一个坑**：`version` 一 bump、重编译一次，KSP 就自动写出新的 JSON 快照 ——
所以"快照文件存在性"这类断言会被重编译**抵消**，真正拦得住的是**升级链检查**（每步都有 Migration）。

**W17 Windows 上连续构建会撞 `classes.jar` 文件锁，报"另一个进程正在使用此文件"，这不是回归。**
现象：`:core:published:bundleLibCompileToJarDebug FAILED` + `java.nio.file.FileSystemException`，
且发生在**代码已还原、上一轮刚绿过**之后。原因是残留的 Gradle 守护进程还占着上一轮的 jar。
处置：`./gradlew --stop` → `tasklist` 确认无 java 进程 → 重跑即过（W12 的同类，都是"并发/残留构建"代价）。
**顺带教训**：这一轮测试根本没跑，而我读的 `test-results` XML 是**上一轮留下的**，
差点得出"守卫没咬"的错误结论。破坏性验证先看 `BUILD` 行、再看 XML 的 mtime，最后才读内容。
