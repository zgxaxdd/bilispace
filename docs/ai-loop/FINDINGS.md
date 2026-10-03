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
**B19 补充**：第三次又犯（`ColdStartTiming.kt` 建到 `com/naammme/`），这次是
`PackageDirectoryConsistencyTest` 在全量跑时红并报出两个路径 —— 这就是那条守卫的价值：
**编译与测试对它都无感，只有专门写的守卫会拦**。所以"写完自己 find 一遍"和"有守卫兜着"两件事都要做。

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

**W18 守卫的规则要精确到「缺陷本身」，不要按文件一刀切。**
B14 把 ST-06b 落成正解后，我第一版守卫写成"这两个文件里**每个** `runCatching` 都必须被
NonCancellable 包住"—— 第一次跑就红，它如实报出 `runCatching { JSONObject(text) }`（JSON 解析）
和 `runCatching { close() }` 两处**合法**的非挂起站点。
按文件写禁令的结局一定是"加豁免"，而带豁免的守卫从此就是噪音（B6 改用前缀规则、
B9 上限拍 0 被打回，是同一件事的三个方向）。
**正解**：把断言锚在缺陷那个调用上（本批 = `publishedRecordRepo.save(...)` 必须落在
`withContext(NonCancellable) { runCatching { … } }` 里），文件级只用"按实测基线登记上限"的
棘轮拦新增站点；上限旁边写明每个已存在站点为什么合法，否则下一个人只能靠猜。

**W19 JVM 单测跑的是桌面 JDK 的协议处理器，不是 Android 的 —— 断言要写契约，不要写分支。**
B15 给 `ImageSaver` 加协议判定时，我按具体 message 断言 `content://` 走"不支持的图片协议"，
第一次跑就红：JVM 上根本没有 `content` 协议处理器，`URL(...)` 直接抛 `MalformedURLException`，
走的是"无效的图片地址"那条；而 Android 有 handler，会返回一个非 Http 的 connection，
才真的走"不支持的图片协议"。
**推论**：凡是依赖 JDK/Android 差异的分支（URL 协议、`MimeTypeMap`、`InetAddress` 解析、
时区/本地化），测试断言"结果属于哪一类受控失败"（异常类型 + 绝不出现内部类型名字样），
而不是"命中了哪一行"。否则同一个测试在 JVM 绿、在真机红，或反过来。

**W20 扫源码的"接线断言"要匹配调用本身，不要匹配名字 —— 定义行也是一处匹配。**
B16 第一版写成 `code.indexOf("sweepOrphanTaskDirsSafely()") > 0`，
而它**匹配到了 `private suspend fun sweepOrphanTaskDirsSafely() {` 那行函数定义**。
把 init 里的调用整行删掉这种回归，实测照样 BUILD SUCCESSFUL —— 守卫从头到尾没看过调用点。
修法：`Regex("""(?m)^\s*名字\(\)\s*$""")`，即"独占一行、前面不挂 `fun`、行尾不带 `{`"，
并额外断言调用点的**顺序**（必须在 recover 之后、drain 之前）。
**推论**：任何"某处必须调用 X"的守卫，都要用"把 X 的调用删掉"来验证，而不是"把 X 的实现改坏"；
后者往往还有类型/编译期兜底，前者只有守卫自己。

**W21 文本扫描型断言：锚点必须"只认代码形态"，每条都要配一对能失败的正反例。**
B20 两次被自己的守卫骗过去，形状都很典型：
1. 锚点写 `lines.indexOfFirst { it.contains("drawBehind") }` —— 而我刚写的那段性能注释里
   正好有一句"原先这段在 `drawBehind` 里"，扫描起点被带到 KDoc 上，抓回来的是无关区块，
   断言变成永真；把「把 getPathForRange 塞回 drawBehind」这种改坏打上去才发现没咬。
   改成只认代码形态 `^\s*\.drawBehind\s*\{` 之后同一个改坏立刻红。
2. 放行文件级常量时用了 `Regex.matches`（整行匹配）而不是 `containsMatchIn`，
   `private object Shapes {` 没被放行 → 是**自检样例**（合成一正一反两例，断言恰好抓到 1 处）
   把它抓出来的，而不是全仓那条"0 处"断言（那时它只会显示"违规 2 处"，看起来像真有其事）。
**结论**：写扫描断言时同时写下一个"应当抓到"的样例；只写"应当抓不到"的断言，
正则一失效它就永久成立。

## W22 · `--rerun` 只在命令行**只有一个任务**时生效（2026-10-01）

`./gradlew testDebugUnitTest --rerun`（不限定模块）与
`./gradlew :a:testDebugUnitTest :b:testDebugUnitTest --rerun` 都会把 `--rerun`
**静默丢掉**：实测整轮 `1352 actionable tasks: 1352 up-to-date`、13 秒 `BUILD SUCCESSFUL`，
看起来像"复跑过且全绿"。逐模块单任务调用 `./gradlew :core:designsystem:testDebugUnitTest --rerun`
才真的执行（同一条命令 5~6 分钟）。
**How to apply**：任何"我复跑过守卫"的声明，先看日志里那一行是 `> Task :x:testDebugUnitTest`
（无后缀）还是带 `UP-TO-DATE`/`FROM-CACHE`；多模块要**分别**调用，别指望一次 `--rerun` 通吃。

## W23 · 扫源码 helper 的前缀参数不许带缩进（2026-10-01）

`blockBody(lines, sig)` 的判据是 `it.trimStart().startsWith(sig)`。把 sig 写成
`"    fun requestExtraction("`（照抄源码里的缩进）→ 永远匹配不到 → 断言必红，
而且红的原因看起来像"代码不符合规范"，实际是扫描器没扫到。
新写的 `CoverTintAllocationStructureTest` 两条断言就是这么废的，靠"故意改坏"那轮
（改坏点根本不在它们守的东西上，它们却先红了）才暴露。
**How to apply**：给这类 helper 加 `require(!prefix.startsWith(" "))`，把静默失配变成显式失败；
写扫描断言时永远先跑一次"未经改坏的绿"——如果它红得莫名其妙，第一嫌疑是扫描器没抓到函数体。

## W24 · 棘轮的 actual 必须用本仓扫描器读，别自己写脚本（2026-10-01）

`DesignTokenLintTest.countMatching` 统计的是**匹配行数**，不是出现次数；一个文件集合、
一条跳过注释与 import 的规则。我按"出现次数"另写脚本实测得到 `12.dp×156`，
而守卫自己报的是 **144** —— 差 12 处，若照我的数去调上限，会把棘轮设成一个永远够不着的假值。
**How to apply**：要读 actual 就把 `assertRatchet` 临时改成恒失败（`actual <= limit && false`），
跑一次过滤到该类的任务，从失败消息里读真实数字，再还原、再写上限。
顺带：这条临时改坏本身也就是"棘轮会咬人"的证据，不用另做。

## W25 · §2.3 第一条我又犯了（第四、五次，2026-10-01）

用 python 脚本改源码内容两次：一次是 9 处同构替换（RankScreen 的 dp→token），
一次是 4 处"临时改坏"。规则本身覆盖临时改坏，且这是台账里已经记过的同一件事。
这次侥幸没出事（读写都显式 utf-8 + `newline=''` 保留 LF，事后 `git diff` 逐条核对过），
但**根因是"多文件同构替换时图快"**，不是不知道规则。
**How to apply**：同构替换要么逐个 Edit，要么先 `git diff` 复核再编译；
临时改坏一律 Edit（一次调用就够，没有"批量"这回事）。

## W26 · 注释式"临时改坏"会骗过 contains 型守卫（2026-10-02）

做破坏性验证时，把"必须调用 X"的守卫对应的**调用行注释掉**（`// foo()`），
`body.any { it.contains("foo()") }` 仍然为 true → 守卫看似有效、其实抓不到。
这是 W7 的又一实证：**覆盖面 = 会红的写法集合**，注释掉的调用等于没有调用（很可能是最
常见的回归形态——"先注释了试试"）。同理 `it.trim() == "call()"` 也抓不到带尾注释的
`call() // note`（用 `trimStart().startsWith("call()")`）。
**How to apply**：所有"块体内必须调用 X"的断言，先 `codeLines(body)` 过滤注释行；
"独占一行调用"按行首形态判定，不用整行相等。

## W27 · 挂载点登记表的行号漂移是**特性**，不是维护负担（2026-10-02）

`GlassMountRegistryTest` 的行号随任何编辑漂移而红。本轮它红了 5 次（每次都是我自己
在玻璃文件里加行/加注释）。看起来烦，但每次红都强制"过一遍登记表"——这正是它要的效果：
玻璃挂载点是**运行期成本**的唯一权威清单（AP-01 的核心盲区就是"数调用点不数实例"）。
**How to apply**：玻璃文件改动后按失败消息照抄实际行号；不要为了"少红"把行号校验去掉，
那等于把登记表变回死文档。

## W28 · Room 的 @Query 参数被"留着不用"会编译失败（2026-10-02）

破坏性验证时删掉 SQL 里的 `LIMIT :limit` 却留着 `limit: Int` 形参 → KSP/Room 直接
编译失败（不是测试失败，是构建挂掉），验证记录因此中断一轮。
**How to apply**：改 DAO 查询的探测要连形参一起删；反过来，给 `@Query` 加参数就必须
在 SQL 里用它——这本身就是一道天然的"SQL 与签名一致性"检查。

## W29 · 任务书的"代码结论"会随修订过时，动手前必须回读源码（2026-10-02）

本轮又复核出三处过时/不精确的交接结论（继上一轮的 6 条之后）：
- 「IM 会话时间格式化在组合期逐项做」→ 实际已有 `remember(state.messages)` 预计算（AP-20 ① 早已成立，行号是旧版）；
- 「DynamicScreen 谓词含 `!isLoadingMore`」→ 该谓词没有，**缺陷在 `DynamicUiState.canLoadMore`
  的 getter 里**（效果相同、定位不同）；
- 「`resolveGlassInputScale` 仍在被消费」→ 是本仓死代码（FL-04 已删）。
**How to apply**：任务书给的是"哪一类问题 + 大概在哪"，不是事实认定书（W4 的强化版）。
每条动手前 `grep + 读现场`，与自己读到的不一致时以源码为准，并把更正写进执行报告。


## W30 · 任务书写在"依赖核查"里的结论是断言，不是事实（2026-10-03）

`S-A-feed-api-type-plan.md` §2 结论白纸黑字：「**前置能力全部就绪**，不需要新增任何依赖或跨模块能力」，
依据是「已有 WBI 能力 `WbiSigner.sign` + `WbiKeyProvider`，已在排行页用通」。但 `WbiSigner` 在
`infra:crypto`、`WbiKeyProvider` 在 `infra:network-web`（`infra/web/WbiKeyProvider.kt:23`），
而 `core:feed/build.gradle.kts` **只依赖 `infra:crypto` + `infra:network-http`** ——
`WbiKeyProvider` 根本不在 core:feed 的编译类路径上。落 PT-10 必须新增一条
`implementation(project(":infra:network-web"))`（与 `core:rank` 同口径，属仓内既有先例）。
**How to apply**：任务书写"零新依赖"时，去 `build.gradle.kts` 里把每个要用的类型所属模块
逐个对一遍 `implementation/api` 列表；跨模块能力"已在别的模块用通"≠"本模块能直接用"。

## W31 · 既有工具"看起来能处理"要读实现，读名字/注释都会错（2026-10-03）

`S-A` §4.3 映射表写「`pic` → `cover` 需走 `httpsImageUrl()`（web 返回 `//i0.hdslb.com/...`
协议相对地址）」。实际 `core/common/.../BiliImageUrl.kt:35-41` 的 `httpsImageUrl()` 只做
`http:// → https://` 替换，**`else -> this` 把 `//` 开头的地址原样返回** ——
照任务书照抄会让 WEB 档所有封面拿到 `//` 地址（Coil 加载失败），且因为很多图床域名
在 app 协议里都是完整 https，这个缺陷**只在 WEB 档出现**，最容易漏测。
**How to apply**：任务书点名的"既有工具"必须打开读那 6 行实现；记不清就写一条覆盖
该输入形态的单测（本轮落在 `WebFeedMapperTest.protocolRelativeUrlIsMadeAbsolute`）。

## W32 · 改"共享入口"的分派语义前，必须 grep 全部调用点（2026-10-03）

`S-A` §4.2 的落地方案是「`fetchFeed` 签名不变，内部按来源分派」。但 `FeedRepository.fetchFeed(idx,…)`
**不只首页在用**：`feature/video/.../swipesource/SwipeSources.kt:24`（刷视频无限接力）也调它，
而刷视频接力的翻页**依赖 app 协议回传的 `idx`**。web 协议不下发 `idx`（恒 0）→ 若这条路径也
按设置切到 WEB，接力会拿 `idx=0` 反复拉同一页 = **静默死循环**（无异常、无日志、只是列表不再增长）。
落地方案：旧入口**恒走 app 协议**（`return fetchMobile(...)`），只有首页走感知来源的游标重载。
**How to apply**：把"某设置项决定的行为"塞进一个被多处复用的方法以前，先 `grep` 调用点并逐个判
"新语义对它是合理还是灾难"；对灾难的那条，宁可在入口上分层（旧入口保持历史语义），
也不要为了方案的字面整齐把它一起改掉。

## W33 · 破坏性探针要"一次只改一处，且改在守卫真正锚的地方"（2026-10-03）

本批 7 处探针里两次值得记：
- **改对了锚点**：`fetchMobileIsTheOldImplementationVerbatim` 的探针是把
  `buildParams(idx, pull, flush, profile, hdFeed)` 改成 `buildParams(..., false)` ——
  只改这一个实参，红的正好是这一条（9 条里 1 条），说明它锚的确实是那行代码形态，
  而不是"某个字符串在文件里出现过"。
- **一次探针红了两条（必须如实记录）**：给 `WebFeedMapper.mapItem` 塞一个非空
  `dislikeContext` 时，红的是 `webItemCarriesNoDislikeContext` **和** `missingFieldsDoNotCrash`
  —— 后者断言"缺字段不崩"时顺手也断言了 `dislikeContext == null`。这本身没问题，
  但记成"1 条红"就是假报告。
**How to apply**：写探针前先回读守卫用的是哪个 `contains`/`assertEquals`，把改坏点**对准那一条**；
一次探针只改一处；跑完把"实际红了几条、分别是哪几条"逐条抄进验证记录
（红多了要解释，别只写"FAILED"二字）。反之，探针若改在一个"守卫根本没锚的位置"上，
红不红都不能当证据（W7）。

## W34 · W26 的复发点：把"守卫必须过滤注释"变成新守卫的模板，而不是每次靠记性（2026-10-03）

PT-04 的 `endHeartbeatCancelsTheLoop` 又把调用**注释掉**当探针，守卫照样绿（W26 原样复发）。
本轮之后的处理：把 `codeLines()`（过滤 `//`、`*`、`/*`、`import` 行）写进 PT-10 的**每一个**新守卫，
包括 `FeedSourceDispatchGuardTest` / `FeedApiTypeSettingsGuardTest` 的 `codeOf()`；
并且"块体内必须调用 X"的断言一律先过滤再 `contains`。
**How to apply**：新建守卫文件时直接从这两个模板抄 `codeOf`/`codeLines`/`blockBody` 三件套，
不要在裸 `readLines()` 上写 `contains` —— 注释行里出现目标字符串是**最常见**的假绿来源
（本仓的注释习惯是把被修的旧写法抄进注释说明为什么改）。

## W35 · 破坏性验证里的 `git reset --hard` 会连**未提交的其它工作**一起清掉（2026-10-03）

NX-01 验收⑤要证明"合并提交可单独 revert"，我用了 `git revert --no-commit -m 1 <merge>` →
看 diff → `git reset --hard HEAD` 还原。**探针本身没问题，代价是当时未提交的两处改动
（`.github/workflows/pr.yml` 的 CI 修复、AP 报告的 NX-02 口径说明）被一并清掉**，
直到 `git add` 报 "nothing added to commit" 才发现，两处全部返工。
**How to apply**：任何"改坏 → 还原"的验证，**上手前先 commit 或 stash**；
批量还原优先用 `git stash` + `git stash pop`，或把探针放进独立 worktree；
`git reset --hard` 只允许在"工作区确认无未提交成果"时使用。
（判据：跑完验证后 `git status` 里出现的"未修改"列表，必须与你验证前的列表逐项对得上。）

## W36 · 扫源码型守卫的 `codeOf(后缀)` 会**静默命中错文件**（2026-10-03）

NX-08 的守卫写了 `codeOf("SettingsScreen.kt")`，而仓里有 `AppearanceSettingsScreen.kt` /
`FeedSettingsScreen.kt` / `PerformanceSettingsScreen.kt` … 一串**同后缀**文件；
`firstOrNull { it.path.endsWith(normalized) }` 命中的是遍历顺序里的第一个，
于是断言跑在**错的文件**上并给出误导性的失败（"routeNav 里没有 BLOCKED_UP_ROUTE"，
而目标文件里明明有）。
**How to apply**：`codeOf` 这类按后缀找文件的 helper，必须**收集全部匹配并断言恰好 1 个**
（本轮已把这条写进 `BlockedUpSettingsGuardTest.codeOf` 的消息里：多命中就直接报出候选文件名）；
调用侧尽量用足够长的路径后缀（`settings${FS}SettingsScreen.kt`）而不是裸文件名。

## W37 · 报缺陷之前先做**对照实验**（2026-10-03）

Track B 里遇到"点 WEB 档的卡片打开详情页永远停在骨架屏"，第一反应是 PT-10 的映射缺陷
（`id → param` 路由或 aid/bvid 不完整）。**做了两次对照才定性**：
① 同变体切 MOBILE 档点卡片 → 同样骨架屏（说明与来源分派无关）；
② 换 release 变体点卡片 → 详情完整加载（说明与"详情链路本身"也无关，是变体/登录态问题）。
若跳过对照，会以"PT-10 缺陷"上报，浪费一轮修复且**改错地方**。
**How to apply**：任何"新功能看起来坏了"的观察，先问一句"**同一路径在旧路径/另一变体/另一来源下是什么表现**"，
把"新引入"与"环境既有"分开；对照结果要写进记录（本轮写进 L4-98）。
