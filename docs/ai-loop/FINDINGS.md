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

## W38 · "共用入口"再犯一次：同一方法同时服务"用户主动"与"内部自动"（2026-10-03）

PB-02 要从上游移植"seek 走关键帧"，上游是**设置项全局生效**（开着就所有 seek 都吸附）。
任务书据此写「本项不改 SponsorBlock 路径即不受影响」—— 但复核源码发现
**SB 跳段（`VideoViewModel:379/404/429`）与用户拖拽（`VideoPlayerPane:2256`）调的是同一个 `seekTo`**，
"不改 SB 路径"这句话在本仓根本不成立：全局吸附会让跳广告先被吸到关键帧（向后吸附还会重入一次），
续播位置也会被吸成粗值写回历史。
这是 **W32（改共享入口的分派语义前 grep 全部调用点）的第二次实证**，且这次是**任务书自己踩的**。
**How to apply**：凡是"上游用它自己的开关/入口做到的事"，移植前先问一句
"**本仓这个入口还被谁用**"；只要存在"用户主动 vs 内部自动"两种意图，
就不能用全局开关表达，必须把意图做进 API（本次：`seekUserInitiated` vs `seekTo`）。

## W39 · 第三方库的"旁路"能力要先编译一次再设计（2026-10-03）

PB-03 的 scheme 闸门，任务书写得像"取键处返回 null 即绕过缓存"。
实际 `CacheKeyFactory.buildCacheKey` 在 media3 1.11 返回**非空** `String` —— 编译一次就发现的错误，
但如果按任务书"设计完再写"，就会把闸门建在一个不存在的能力上（写完才发现，返工面更大）。
同类：`CacheWriter` 的契约（要求上游是 CacheDataSource 实例）也是**不能靠记忆断定**的，
本轮因此改用标准 DataSource 的有界读取实现预热。
**How to apply**：凡是"某个库 API 允许 X"的断言，先写 3 行最小代码编译/跑一次；
库的 API 面与版本强相关，任务书（或记忆）里的写法可能来自另一个大版本。

## W40 · "某函数没被调用"不等于缺陷 —— 先读该处的设计注释（2026-10-03）

真机验证时发现 `PlaybackReporter.setHostBackground` 从未被调用（后台播放开启时
`PlaybackHost` 的 `DisposableEffect` 提前 return，不注册 ON_STOP 观察者），我据此登记了
一条"结构缺口"并推断 PT-04 的"后台不发心搏"不成立。
**读注释后撤回判定**：`PlaybackHeartbeatPolicy` 与 `PlaybackHostViewModel.onEnterBackground`
的 KDoc **两处**都写明这是**有意的产品差异**——"后台播放开启时宿主不下发该信号
（用户显式选择后台续播，此时心搏继续）"。
**How to apply**：断言"这里是缺陷"之前，先读该函数/该字段所在文件的 KDoc 与调用点的注释；
"路径没被执行"有三种可能——① 缺陷、② **有意的条件分支**、③ 环境不满足前置条件
（本轮三者都出现过：L4-101 缺陷是真缺陷；L4-111 是有意分支；L4-98 是环境不满足）。
登记前先分类，别把 ② 写成缺陷（会给下一轮留下错误的修复方向）。

## W41 · "先测量"类任务没有捷径：测不了就是阻塞，不许用"代码看起来没问题"代替（2026-10-04）

SX 批次的 SX-06 要求「先测量再改」（评论区滚动帧率），SX-10 的**硬门禁**是「改动后帧率不得
劣于 SX-06 的基线」。本轮真机不在线（`adb devices` 为空）——这两项**不是"难做"，而是"无法做"**：
没有基线就没有比较对象。**若为了推进先改 UI 再补测量，归因能力就永久丢失了**（重构后再测到的
已经是改后的数，前后对照不再成立）。

**How to apply**：任务书里写"先测量"的项，把"测量能力是否可得"当作**前置条件**检查；
不可得时该项状态写"未能验证（缺 X）"并**保留原始状态**，不要用事后补测冒充前后对照，
也不要把"结构守卫绿"当成"性能门禁过"。三态结论里"未能验证"是合法交付（铁律 3）。

## W42 · 按"代码形态"写的守卫会把"数值"一起钉住：改数值时要分清形态断言与数值断言（2026-10-04）

SX-02 缩小侧栏时，`interactionRailUsesModernParameters` 的**断言**只要求"尺寸走 token 派生"
（形态），但它的**文案**写着"暗盘 52dp、图标 40dp"（数值）—— 改数值后断言没红，
**文案却变成了谎言**。同一批里 SX-10 改面板底色 alpha 时，断言里的
`color = ...surface,`（带尾逗号的字面量）是**数值级形态**，一改就红。

**How to apply**：动数值前先给相关守卫分类 ——
① **形态断言**（标识符出现次数、禁止 `.border(`/`Brush`/`hazeBlur(`）：数值改动能过，
   但**断言文案必须同批更新**，否则守卫开始说谎（这比守卫变红更危险，因为没人会去看）；
② **数值断言**（`surface,` 带逗号、`* 1.5f`、`disc 52dp` 字面量）：会红，按 W27 同批改并写明理由。
**两类都要在同批处理**，不要只处理会红的那类。

## W43 · 守卫断言文案里的中文强调要用「」，不能用 ASCII 双引号（2026-10-04）

本轮**同一个错误犯了两次**：把 `"..."`（ASCII 双引号）写进 Kotlin 字符串字面量的中文句子中
（`FeedSourceDispatchGuardTest` 的「恒 app 协议」、`VideoLogicGuardTest` 的「尺寸走 token 派生」），
直接语法错误（`Unresolved reference` / `Literals must be surrounded by whitespace`）。
本仓的断言文案几乎全是中文长句，这类错误很容易连带把整个模块的测试编译打挂。

**How to apply**：写中文断言文案时，需要强调的短语一律用「」；改完先跑一次
`:<module>:compileDebugUnitTestKotlin`（不用跑完整测试）就能立刻发现 —— 比跑完整套测试快一个数量级。

## W44 · 两类"冻结基线"的修法**相反**：数值棘轮改代码，行号登记表改表（2026-10-04）

SX 批次一轮内**两类冻结基线各红了一次**，而且正确修法完全相反 —— 搞反任何一边都是错的：

| 类型 | 代表测试 | 为什么红 | **正确修法** | **错误修法** |
|---|---|---|---|---|
| **数值棘轮**（只许降） | `ListAllocationStructureTest.shapeAllocationBypassForms_areFrozenAtMeasuredBaseline` | 具名实参 `shape = RoundedCornerShape(percent = 50)` 让实测 17 处 > 冻结上限 16 | **改代码**：换成共享单例 `CircleShape`（宽矩形上即 stadium/pill，观感等价但**零分配**） | ✗ 把上限 16 调到 17（铁律 7：棘轮只许降；调大必须连上限一起改并写明理由） |
| **行号登记表** | `GlassMountRegistryTest.everyGlassMountIsRegistered` | 新增一行 `import` 使 `VideoPlayerPane.kt` 的玻璃挂载点行号整体 +1（登记 402/688，实际 403/689） | **改表**：按失败消息**照抄实际行号**（任务书 §2.4 的既定修法） | ✗ 为了迁就旧行号去压代码行数（本末倒置，登记表只是"挂载点清单 + 当前位置"的索引） |

**How to apply**：棘轮红了先问一句"它是**约束代码成本**还是**索引代码位置**"——
① 约束成本的（dp 字面量、Shape 分配、模糊入口、`animate*AsState`）：**改代码**，把成本降回去；
② 索引位置的（玻璃挂载点行号、挂载登记表）：**改表**，照抄失败消息给的实际行号。
判据：这条基线**调大以后会不会让代码变差**？会 → 它是①；不会（只是位置变了）→ 它是②。
本轮还有第三条相关经验：**形态守卫**（W27）红了要按"形态变了"改断言 + 写理由，三者别混。

## W45 · "先测量再改"类任务：拆成"静态核查"与"测量驱动的改动"两半，别整项搁置（2026-10-04）

SX-06（评论区滚动帧率）在无真机时被整项搁置了三轮。第 4 轮换了个拆法：
把它拆成**两半**，只把"必须等基线"的那半留在阻塞态 ——
- **静态核查（无设备可做，已完成）**：任务书自己列了 6 类"重点怀疑"（列表 key/contentType、
  组合期重活、图片解码夹取、嵌套滚动状态抖动、表情/富文本分配…）。**逐条核到 `文件:行号`**：
  结论是 6 类**全部"已处理"**（评论项用 `key = { it.rpid }`；全模块零时间格式化、正则唯一且文件级
  预编译；头像走 `BiliImageVariant.Avatar → avatarThumbnailUrl` 在 URL 层夹取；加载更多是
  `derivedStateOf(listState.layoutInfo)` + `LaunchedEffect(items.size)`；富文本解析与 inlineContent
  都 `remember` 住）。
- **测量驱动的改动（必须等基线，阻塞）**：既然静态核查**排除**了所有"独立于测量就能正当化"的
  改动点，就没有"先随便改一点"的余地 —— 硬造改动就是在没有基线的情况下动性能代码，正是 W41 要禁止的。

**How to apply**：遇到"先测量再改"类任务而设备不可得时，不要整项标阻塞了事：
① 先把任务书自己的"怀疑清单"当**核对表**逐条核到行号（这一步不需要设备，而且往往能发现
   "怀疑的坑早就填过了"）；② 把核查结果写成文档，明确列出**被排除**的怀疑项；
③ 剩下的改动点如果**必须由测量指向**，才标阻塞，并把**测量协议**（命令、轮次、刷新率上下文、
   口径）预先写进同一份文档 —— 这样真机一到就是"照跑"，不用重新设计。
④ **不要**因为"总得做点什么"就在无基线时改性能代码：那会同时失去归因能力与"没动过"的干净基线。

## W46 · 真机调试三个坑 + 一条"静默返回路径"教训（2026-10-04）

真机第一次上线，前两次手势验证**全部无效**，白花了一轮。三个坑都值得记：

1. **`monkey -c LAUNCHER` 会启动 LeakCanary，不是本 App。** 调试包内置 LeakCanary，它同样注册了
   LAUNCHER category，`adb shell monkey -p <debug> -c android.intent.category.LAUNCHER 1` 可能拉起
   `leakcanary.internal.activity.LeakLauncherActivity`。表现极具误导性：**任何手势都"没反应"，
   连给 App 埋的诊断日志都不打**，非常像"功能坏了"。自检方法：`dumpsys window | grep mCurrentFocus`
   看前台是不是本 App 的 `MainActivity`。**一律用显式 Activity 启动**。
2. **手势"进不去"要先怀疑位移阈值，不要先怀疑代码。** 本仓 `pullDownToFullscreen` 的提交阈值是
   **释放时 progress ≥ 0.5**，progress = 手指位移/(windowBottom − zoneTop)。本机 zoneTop≈856px、
   span≈1944px ⇒ 要拖 >972px；从 y=2000 起拖 790px（0.41）**不会**提交。所以"同样的手势昨天行、
   今天不行"往往只是起点选错了（视频带高度随宽高比变化）。
3. **动效取证：把连拍放进同一条 `adb shell`。** 每次 `adb shell` 往返约 200ms，用 PowerShell 连续调用
   `screencap` 根本拍不到 300ms 的动画；改成
   `adb shell "input …; screencap -p f1.png; screencap -p f2.png; …"` 一次下发，
   就能拍到**中途帧**。判据：中途帧里"面板内容整体偏移""两页同屏、页面边界可见" ⇒ 是连续缓动，
   不是瞬变。这条比录屏更省事（不需要 ffmpeg），也是本轮把两个"动效项"从"未能验证"变成"通过"的原因。

**教训（最重要）：排查"点了没反应"时，先找有没有 `?: return` 这类静默返回路径。**
`VideoViewModel.toggleLike` 经 `withInteraction`，而后者有两条不产生任何可视反馈的返回路径：
`interactionTargetOf() ?: return`（无提示）与 `if (_interaction.value.busy)`（提示语可能不在当前面板显示）。
于是"按钮没反应"既能由"点击没送达"引起，也能由"送达了但被静默拦下"引起 —— 而**两者的现场表现完全一样**。
⇒ 遇到此类症状，**先穷举静默返回路径并在每条上埋点**，再谈功能是否有 bug；否则会像我本轮一样，
在"自相矛盾的两组观察"之间来回，最后只能诚实地写"根因未定位"（**这是正确的取舍，不是失败**
—— 按铁律不许猜）。同时：**矛盾信号本身就是重要线索**，它排除了"简单地点击没送达"这一类解释。

## W47 · 依赖库的"机制类结论"必须读它的**实码**，别拿社区经验当架构事实（2026-10-08）

10-08 那份刷视频链路分析给了 P0-3 的一条优化：「ExoPlayer 语义里 stop() 会释放解码器，
所以切页不要 stop()，直接 setMediaSource + prepare() 就能复用已初始化的解码器」，
并引 Reddit 的 ExoPlayer 实践作为出处。**读了 Media3 1.11.0 的源码之后，这条不成立**：

| 核对点 | 源码事实 |
|---|---|
| `ExoPlayerImplInternal.prepareInternal()`（`:1061`） | 第一件事就是 `resetInternal(...)` |
| `resetInternal()`（`:2072`） | **无条件** `disableRenderers()`（`:2088`） |
| `setMediaItemsInternal()`（`:1075`） | 不直接 disable，但经 `handleMediaSourceListInfoRefreshed` → period 变化时落 `seekToPeriodPosition`（`:1825`），那里 `forceDisableRenderers \|\| period 变了` 就 `disableRenderers()`（`:1860`） |
| `MediaCodecRenderer.onDisabled()` | → `flushOrReleaseCodec()`（`:1038`）：**多数机型只 flush 不 release**；`MediaCodecVideoRenderer:1712` 恰恰是给 `c2.mtk.{avc,hevc,vp9}.decoder` 开例外才 release（b/362904942） |

⇒ "不调 stop()"跳不过任何一次渲染器 disable；而"切页到底重不重新初始化解码器"**本身是机型相关**，
静态推不出来 —— 只能测（本轮把它落成 `FirstFrame` 的 `t2=`、`PlayWatch` 的 `decInits=`、
`DecoderReuse` 的 `result=` 三行读数，见 L4-128）。

**核对手法**（比 javap 快且能读注释，记下来复用）：
```
curl -sSLo src.jar https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar
```
注意 **sources jar 不是每个 artifact 都有**：`media3-datasource-cache` 在 1.11.0 已经并进
`media3-datasource`（拿它的 sources jar 会 404，而 404 的 HTML 会被 curl 存成"jar"，unzip 才报错）。
只有 class 的 aar 时用 `javap -p -c classes.jar <类>` 看字节码里的 `// Method xxx:()V` 也能定调用点。

**How to apply**：报告/交接文档里凡是"**因为 A 机制所以 B 优化有效**"的句子，动手前把 A 读到源码行；
数值与行号要复核（W4/W6）已经守过几轮，**机制断言是同一类风险但更隐蔽** ——
它错了不会编译失败，只会让一整套改动方向白做（本轮直接撤掉了一条 P0）。相关：[[bbspace-perf-taskbook-execution]]。

## W48 · 归档脏工作区之前，先把自己刚写进去的改动**撤回**（2026-10-08）

本轮开工顺序错了半拍：先在某文件里写完了新接线，才发现工作区还压着 169 处前序会话的未提交改动。
此时若直接归档，那个文件的 diff 就同时含"别人的 497 行 + 我的 60 行"，
**归档提交与本会话第一项的提交都无法单独回滚** —— 而分组归档的全部目的就是这个。

做法（没用 shell 改内容，见 §2.3 第一条）：
1. `cp` 当前文件到 `/tmp` 并记 sha256；
2. 用 Edit **逐条撤回自己的改动**（我这里是 8 条），撤回后 `grep -c` 自己引入的符号必须为 0；
3. 按路径分组归档前序工作；
4. `cp` 复原 → 核对 sha256 与步骤 1 一致、`git diff` **恰好等于**自己那 +60/−0；
5. 再提交本会话的第一项。

**不要用 `git checkout HEAD -- <文件>` 撤销**：那会连带丢掉同一文件里前序会话的未提交工作。

## W49 · 哨兵值 `<= 0` 会让"注入 0 时钟"的单测**静默变成没开始**（2026-10-08）

`FirstFrameTimeline` 沿用本仓口径（`FirstFrameTimeoutPolicy.startedAtMs <= 0` 表示"还没开播"）
用 `t0 > 0` 判"时间线是否已启动"。三条单测用 `FirstFrameTimeline { 0L }` 注入常量时钟 ⇒
`begin()` 记下的 t0 就是 0 ⇒ 整条时间线被判为"未开播"、`completeLine()` 恒 null、三连红。

**How to apply**：凡是"`<= 0` = 未设置"的哨兵语义，(a) 测试里注入的时钟**从非零起步**；
(b) 顺手补一条**正面钉住哨兵**的用例（本轮加了 `monotonicClockZeroMeansNoTimelineStarted`），
否则下一个人还会踩，而且报错形态是"字段全是 `-`"这种最难倒回去的一种。

## W50 · 埋点类改动最常见的失效是"被悄悄拆掉且看起来仍然绿"，守卫要**按调用形态 + 函数体限定 + 反向成对**（2026-10-08）

功能测试管不着"引擎里有没有人读 `videoDecoderCounters`"：接线一旦被删，日志只是永远打 `-` /
`dropped=0`，编译与单测全绿。本轮四个新守卫（`FirstFrameAnatomyWiringGuardTest`、
`PlaybackObservabilityWiringGuardTest`、`VideoPlayerRenderCostGuardTest`）统一按三条写：

1. **匹配调用形态**而不是符号名（`firstFrameTimeline.begin(`、`has(body, "player.videoDecoderCounters")`）—— W20；
2. **限定在对应函数体内**（花括号配对取体）—— "把 begin 挪出 setSource"和"删掉 begin"都会红，
   而注释里提一句名字不会让它假绿（扫描先剥注释与 import 行）；
3. **正向必配反向**：`VideoPlayerRenderCostGuardTest` 里"不得再出现 `withFrameNanos { }`"、
   "`Modifier.height((100 *` 不得回来"；`PlaybackObservabilityWiringGuardTest` 里更有一条
   **语义反向** —— 缓存监听器**只许挂在读路径**，预热/预取（往缓存里写）挂了就把命中率
   算成"预取越猛越命中"的假结论。

**本轮的实证缺口要诚实记下**：只有 `GlassMountRegistryTest` 在这一轮**真的咬过**
（我加注释导致行号漂移，179 项里唯一失败就是它，按 W44 的"改表"修法回填实号后绿）。
上面四个守卫只跑到"绿"，没逐条改坏验证据 —— 说"会咬"之前别把它当成已证。

## W51 · 新埋点要拿**第二条独立信号**交叉验证，否则"埋错了"会一路绿到真机（2026-10-08）

我给"切页有没有重建解码器"同时埋了两条自认为是同一件事的信号：解剖行的 `t2`（来自
`onVideoDecoderInitialized`）与 `PlayWatch` 的 `decInits`（来自 `DecoderCounters.decoderInitCount` 差分）。
**真机第一次连滑就互相矛盾**：每一页都有 `t2` + `decoderInit=26~57ms`，而 `decInits` 一路 0。
矛盾本身就是线索 —— 它排除了"解码器被复用"，把嫌疑指向**埋点自己**。

源码核对后的事实：`decoderInitCount` 属于**每个解码器实例一份**的 CounterSet
（`MediaCodecRenderer:1453` 新建时 `++`，换解码器时整个 counterSet 换成新的），
所以 `player.videoDecoderCounters` 取到的那份里它**恒等于 1** ⇒ 跨窗口差分恒为 0。
它不是"自播放器起累计的初始化次数"。**改法**：按 counters 的**实例身份**变化记进程级换代数（`swaps`）。

**How to apply**：
1. 任何"计数/累计"类埋点，先问一句**"这个数是进程级累计、还是每次事件重置？"**——
   名字（`...Count`）完全不提示这一点，只有源码提示；
2. 同一件事尽量留**两条互相独立**的信号（一次回调 + 一次轮询读数）。两条一致才敢下结论，
   两条矛盾就把嫌疑指向测量本身而不是被测对象（本轮若只有 `decInits`，结论会正好反过来：
   "解码器一直是复用的，所以预热没有收益" —— 一个会把后面整批工作带偏的错误结论）；
3. 单测拦不住这类错：JVM 测的是**格式化与判据**，库语义只能靠真机读数或读源码；
4. 守卫要写成**反向**断言（本轮：`reportPlaybackWatch` 函数体内不许再出现 `decoderInitCount`）——
   错误写法看起来比正确写法更像"正确答案"，只钉正向的话下一个人会顺手改回去。

相关：W47（机制断言要读依赖实码）、W21（正反成对断言）。

## W52 · 网络条件会改变被测路径本身：蜂窝下测出的首帧构成是"最坏值"，不能当结论（2026-10-08）

刷视频链路上有好几道 **Wi-Fi 闸门**（头部字节预热 `PlaybackHeadPrewarmPolicy`、DASH 分段预取、
蜂窝下不预热整份详情）。本轮真机读数是在**蜂窝**下取的（`PlayWatch` 里 `unmetered=false`，
每页 `index=NET`、`t1` 中位 271ms 占首帧 72%）⇒ 这些预热/预取**当时全都是关着的**，
`t1` 是"没有任何预热"的形状。

**How to apply**：任何"取流/建源这一段慢"的结论，都要先确认当时的网络档位，
并在 Wi-Fi 下复测同一批数（本轮的下一步就是同一台机器换 Wi-Fi，只看同样那三行、不改代码）。
反过来也成立：**Wi-Fi 下测出来的预取收益不能外推到蜂窝**（闸门一关，收益归零是设计行为，不是 bug）。
读 `PlayWatch` 时 `net=`/`hit=` 与 `host=` 一起看，能立刻认出这次到底走没走网络。
相关：L4-128、铁律"无设备不报数字"的同等要求 —— **无对应条件也不报数字**。

---

## W53 · 并发跑 Gradle 会**伪装成"基线是红的"**，`0 字节 *.bin` 是并发的指纹而非代码失败（2026-10-15）

**What happened**：2026-10-15 第 16 轮，同一仓库在约 25 分钟内**四次**单测全部没拿到结果，
一度被记为"基线未建立、原因不明"，甚至有人据此判断"环境坏了"。实为**两个 Gradle 调用并跑**。

**两种表现，同一根因**（都在 `feature/video/build/test-results/testDebugUnitTest/` 下）：

| 表现 | 触发机制 | 原始证据 |
|---|---|---|
| `java.nio.file.NoSuchFileException: ...\binary\in-progress-results-generic.bin` | 一方正在写该文件，另一方**按纪律清空旧结果**把它删了 | `r7-video-baseline.log`（BUILD FAILED in 9m23s） |
| `java.io.EOFException` + `binary\*.bin` 全为 **0 字节** | 双方**同时写**同一 `binary/` 目录，写到一半被截断/覆盖 | Main Bot 亲历（19:26 与 19:32 撞车） |

**Why it misleads**：这两条**长得像代码或环境坏了**，而实际与代码无关。
更糟的是它有**假绿反向风险**：若只看到目录里残留着上一轮的 `TEST-*.xml`，
会把"这次的运行根本没落地"读成"通过"。

**How to apply**（判据写死，顺序不要颠倒）：

1. **同一时刻全仓只允许一个 Gradle 调用。** 要跑先确认没有别人在跑。
2. 见到 **`0 字节 *.bin`**、`EOFException`、`NoSuchFileException` ⇒ **先怀疑并发，不要先怀疑代码**。
3. 读测试结果前必须同时确认两件事：**(a) 有活的 Gradle/JVM 进程；(b) `TEST-*.xml` 的 mtime 晚于本次启动时间**。
   二者缺一，结论一律记 **未能验证**，**不得记通过**。
4. **（2026-10-15 22:4x 修订，原写法作废）跑测试一律不要手动删 `test-results` 目录。**
   构建手 A/B 实测：删目录再跑 → `BUILD FAILED`（`NoSuchFileException: ...binary\in-progress-results-generic.bin`）；
   不删、改用 `--rerun`（单任务，见 W22）→ `BUILD SUCCESSFUL in 9m 44s`。
   ⇒ **外部抹掉 Gradle 的输出目录本身就会杀死运行**，不需要并发参与——这是与"并发撞车"**独立的第二条根因**。
   防假绿靠第 3 条双证据判读 + `--rerun`，不靠删目录；删目录这个动作本身就是失败签名的制造方式。
5. 跨 Bot 协作时，"我这边 `Get-Process java` = 0"**不能**证明对方没在跑
   （不同 Bot 可能在不同隔离域/视图）。**以文件 mtime + 进程双重证据为准，别用单侧进程表否证对方。**
6. **判"通过/失败"看 XML mtime 是否晚于本次启动时间，不看数字。**
   `UP-TO-DATE` / `FROM-CACHE` 等于没跑；数字再齐也可能是上一轮残留。
   （2026-10-15 当晚两个活例：① 19:26 轮 XML 残留 10-14 的通过结果；② 22:2x 一轮 Gradle 重跑
   按默认行为清空本模块旧结果——上一轮数字若没当场抄进台账就再也数不回来了。）

**复发记录（2026-10-15 晚，当日 4 次）**：`EOFException`/`NoSuchFileException` 当日出现 4 次，
其中两对确定为并跑（19:26×19:32、19:53×20:09）。新细节：**有一次测试本体已跑完**
（控制台明确输出 `52 tests completed, 1 failed`），死在写 `binary/*.bin` / XML 阶段
⇒ **控制台打印过用例数 ≠ 结果落了地**，判读仍按第 3、6 条执行。
对照组：当晚 `:feature:video` 126/0/0/0 与 `:core:designsystem` 181/0/0/0 均为**单任务独跑**所得
⇒ **"基线绿不绿"一律以单任务独跑为准**。

**归因更正（当日，Main Bot 自纠）**：他先前把构建手那次 `NoSuchFileException` 失败归成"并发"，
以构建手 A/B 为准**改判为删目录所致**（见第 4 条）。当晚两条根因并存，勿再合并。

**外部依赖符号的检索域（当日两条独立账）**：
`git grep` 只覆盖本仓源码，**0 命中不构成"不存在"**——当晚两个人在同一处栽倒（`Highlight.Glass*Dark` 判无、
`DanmakuContext` 窗口参数判无），取证一律走 aar→classes.jar（常量池或 `javap`）；
说"不存在"必须交代检索范围。详见 PROGRESS 批次 UP-05 §10。

**相关**：PROGRESS.md 批次 UP-05 §1 / §13；W50（"看起来仍然绿"的失效模式）是同一思想在**守卫**上的应用，
本条是它在**构建产物**上的应用。

---

## W54 · 报文件行数必须带 **mtime + 口径**（物理行 vs 非空行）（2026-10-15）

**What happened**：同一份 `docs/ai-loop/PROGRESS.md`，今晚先后被报成 2058 / 2104 / 2134 / 2170 / 1727 / 2299 行——
**每一条都"对"，但没有一条可比**。根因两样：

1. **计数口径混用**：`Measure-Object -Line` 只数**非空行**；同一时刻物理行数多 25%+
   （台账员实测 22:40:52：**物理 2339 / 非空 1865 / 空行 474 / 195,840 B**）。
   "1727"就是非空口径，"2299/2339"是物理口径——**同一个文件，谁也没说谎**。
2. **文件正在被并发写**：相隔 3~5 分钟的两次"矛盾"读数，差 40~60 行，全是别人写入的正常增长。

**How to apply**：
1. 台账里写任何文件行数，一律给 `物理行 / 非空行 + mtime`（例：`2339 物理 / 1865 非空 @22:40:52`）；
   单独一个"行数"没有可比性，**带字节数更好**（字节不受口径影响）。
2. 别人报的数与自己不符时，**先对口径、再对 mtime，最后才怀疑对方错**——
   今晚两次"文件被改短了 / 我在乱写"的虚惊都源于没对这两样。
3. 统计写法：物理行 `(Get-Content $f).Count`；非空行 `(Get-Content $f | Measure-Object -Line).Lines`；两者都给。

**相关**：W53（同晚教训同型——数字必须绑定落盘时间/观测时点才作数）。

---

## W55 · 带锚点的"机制行"与任务书"结论句"必须逐条对账——两者同表共存一天，没人发现它们互相矛盾（2026-10-15）

**What happened**：任务书 §3.2 断言"链路 B（播放页控件玻璃）默认就是开的"，核查按任务书走了一整天；
而我自己的事实表里**第 8 行早就写着相反的机制**——`effectiveControlsBlur = controlsBlur && !useSurfaceView`
（`VideoPlayerPane.kt:713`，标"通过"）。省电模式默认开（`AppSettings.kt:495-496` `?: true`）
⇒ `useSurfaceView=true` ⇒ 磨砂强制关 + `videoBackdrop=null` ⇒ 玻璃修饰符三重早退全走纯色 scrim
（`:693/:382-383`）。**机制记对了，结论没对上**——直到 Main Bot 真机像素实测（41/26/114/139 对上纯色
理论值 40.8/25.5/113.9/139.4）才把 §3.2 推翻，记为任务书**第 4 处锚点错误**。

**Why it misleads**：两种表各看各的都"自洽"——机制行有行号有锚点、结论句出自任务书权威，
单看任何一条都不会起疑。**冲突只在两条并排读时才可见**，而台账把它们放在不同小节里。

**How to apply**：
1. **收口前做一遍"机制↔结论"对账**：事实表每一条标"通过"的机制行，逐条与任务书/上游结论句比对，
   冲突时**以带源码锚点的机制行为准**，并当场在两边互写"以对方为准"的注记（本次 §3 行 8 与 §2 勘误 4 已互钉）。
2. "默认就是 X"类断言（默认开/默认关/默认生效）**必须找到默认值的那一行代码**（`?:` 回退、
   DataStore key 缺省）才算核实；从"开关存在"推不出"默认开着"——§3.2 就是这么错的。
3. 走查/实验类判据要检查**前置条件是否成立**：省电模式开着时做玻璃 A/B = 两档都回落纯色的
   **假性通过**；先改设置（关省电、重进页面）再评价。判据清单里应把这类前置写成 0 号步骤。

**相关**：W29（任务书结论随修订过时，动手前回读源码）、W30（任务书"依赖核查"里的结论是断言不是事实）、
W37（报缺陷先做对照实验）——本条是三者没覆盖的空档：**两条都对的记录放在一起却互相矛盾**的检出方法。

---

## W56 · 注释里的"调用点清单"会随代码收敛过时，且与守卫互相矛盾（2026-10-15）

**What happened**：`AppSettings.kt` / `VideoPlayerPane.kt` / `view_bili_player_surface.xml`
三处注释都写"外层带 `graphicsLayer`/`clip` 的**三处**调用点传 `allowSurfaceView = false`
（宽屏展开布局、形变布局、刷视频 Pager）"。实际全仓只有 **1 处**（`PortraitSwipePager.kt:753`）——
宽屏展开与形变布局的图层问题后来已解决，`VideoScreen.kt` 自己的注释（`:517/:548`）与守卫
`PlayerSurfacePowersaveGuardTest.onlyPagerKeepsTextureView`（断言 VideoScreen 里必须是 **0** 处）
都已反映新事实，**只有那三处旧注释没跟着改**。Main Bot 照注释派活（"三处调用点"），
差点让排查方向偏掉。

**Why it misleads**：注释与守卫**指向相反**，而两者都在仓库里、都"看起来权威"。
守卫是机器执行的、注释是人读的 —— 人读的那份过时了不会有任何信号。

**How to apply**：
1. 注释里凡是写**数量**（"三处"/"两处"）或**清单**（"宽屏展开、形变、Pager"）的，
   都是高漂移风险项：数量会随收敛变化，清单会被逐项解决。**引用清单时给守卫名**，
   让读者能一步验证（本次修法：改为"全仓仅一处（刷视频 Pager），VideoScreen 守卫要求 0 处"
   + `PlayerSurfacePowersaveGuardTest.onlyPagerKeepsTextureView`）。
2. 派活前若引用的是**注释**而非**代码/守卫**，先 grep 一遍实际命中数（W29 的同型：
   结论会过时，动手前回读源码）。

**相关**：W29、W30、W55（机制↔结论对账）——本条是"注释 vs 守卫"这一对。

---

## W57 · 播放页三处读**设置原值**而非**生效值**：改渲染面判据时必须同步对齐（2026-10-15）

**无 substrate 归档 / 隐患警示**（当前**不是缺陷**，是未来改动的地雷）：

- `VideoScreen.kt:531`、`:557`、`:1050` 都读 `surfacePowersave == true`（DataStore 设置**原值**），
  而播放页内真正决定渲染面的是**生效值** `useSurfaceView = surfaceSetting && allowSurfaceView`
  （`VideoPlayerPane.kt:561`）。
- 今天两者等价，因为 `allowSurfaceView` 只在 Pager 一处为 false、且那处本来就走 Pager 分支。
  **一旦引入任何影响渲染面的新判据**（例如"要开玻璃就不用 SurfaceView"、HDR 强制、
  设备兼容性否决），这三处就会与实际渲染面**不一致**：可能出现"实际已是 TextureView，
  却仍按 SurfaceView 跳过进场动画 / 用 offset 而非 graphicsLayer"——白丢动效且无报错。
- **纪律**：改 `useSurfaceView` 判据的那一批，必须同批把这三处改成读同一个生效值
  （或把生效值提升到可被 `VideoScreen` 读到的地方），并检查
  `PlayerSurfacePowersaveGuardTest` 里锚 `surfacePowersave == true` 的断言（`:122`、`:557` 相关）。

**HDR 判据（无 substrate，归档）**：上游 `PlayerPageBackdrop` 用
`currentQualityId == 125/126 || colorTransfer == 6/7` 决定是否强制 SurfaceView；
本仓 **`colorTransfer` / `ColorInfo` / `COLOR_TRANSFER_ST2084` / `BT2020` / `dynamicRange`
全仓 0 命中**，`PlaybackStream`（`PlaybackModels.kt:190-233`）只有
`quality/format/mimeType/width/height`，**没有色彩传输字段** ⇒ 真 HDR 判据取不到。
只有字符串标签可近似：`AudioVideoSettingsScreen.kt:267-268`、`VideoDownloadModels.kt:63-64`
的 `125 -> "HDR"` / `126 -> "杜比视界"`。**搬不了，不是"还没做"。**

**相关**：W55（省电模式默认开 ⇒ 播放页玻璃默认整条关）、W44（冻结基线的两类修法相反）。

---

## W58 · **派单正文不是源码**：转述现状必须回读源码或贴行号（2026-10-16）

**What happened**：UI-EDGE 批次派单里，Main Bot 凭印象把**旧排布**写成了现状
（"6 个挤在一排 / 全是文字 / 全屏是文字钮"）。移植手出规格表时**照抄了派单的框架词**，
于是诊断里出现三条**已于 2026-10-11 修过**的旧问题。源码可自证：
`VideoPlayerPane.kt:2628-2629` 注释即当年重排记录（"改成按内容自适应宽度…全屏改成**贴角图标**"），
且 2026-10-15 之后那一排的 weight 分配又被 FIX-01 改过一轮（`:2637-2656` + 守卫 `:233`）。

**Why it misleads**：派单是下游**第一个读到的文本**——移植手/核查员的产出以它为框架，
错误转述会被"照抄进规格表"放大成**立项依据污染**；而派单不像任务书结论那样会被默认要求回读源码
（W29/W30 只管到了任务书，没管到派单正文）。

**How to apply**：
1. **派单/任务书里凡描述"现状如何"的句子，必须附 `文件:行号` 或注明"未复核，动手前先读源码"**——
   凭印象转述与凭印象写结论是同一类错误，只是换了个载体。
2. 下游（移植手/执行方）**照抄派单框架词前先做 substrate 式回读**——本仓"动手前逐条回读"的纪律
   同样适用于派单正文，不只适用于上游提交。
3. 发现派单与源码矛盾时，**当场在派单回执里指出并把教训记进本文件**（W58 就是这么来的），
   不要私下按源码做——否则错误框架会活到下一轮。

**相关**：W29（任务书结论随修订过时）、W30（任务书"依赖核查"里是断言不是事实）、
W55（机制行↔结论句对账——本条是它在**派单侧**的应用）。

---

## W59 · 渐变遮罩的可读性验收：必须量**文字所在横带**的反推 α，不能拿末端值/中段值代入（2026-10-16）

**What happened**：控制条遮罩是**三段渐变**——中段 `0.14`（`:208` 一代）到底端 `0.55`
（`:201` `PlayerOverlayScrimBottomEnd` ✓ 台账员已核）。文字坐在盒内**中上部**
（约 0.33~0.62 高度带），**有效 α 既不是 0.14 也不是 0.55**：拿 0.14 代入会高估可读性、
拿 0.55 代入会低估——两个都错，且错的方向相反。
本批的正确做法已写进源码注释（`VideoPlayerPane.kt:2615-2617`）：量**文字墨迹带**的反推 α
（该带实测全程 ≥0.55 ⇒ 纯白字 ≈4.75:1），而不是拿任何一个分段常量当真值。

**Why it misleads**：渐变遮罩**不存在"一个数"**——同一控件内不同高度的 α 可差近 4 倍
（0.14 vs 0.55）。用单个常量做对比度验收，等于给一个不存在的量算公式。

**How to apply**：
1. 凡涉及**渐变遮罩**的可读性结论，必须**实测文字横带的反推 α**（截图取像素 → 按文字色反推），
   并在结论里写明**测的是哪一条带**（高度区间）；
2. 禁用代入：`BottomEnd`/`Mid`/任何一个分段常量都不许直接当"遮罩 α"用；
3. **字重对 WCAG 对比度公式是零贡献**——"0.92 + 加粗"仍是 4.32:1，只有感知改善；
   不得写"≈5.5:1"这类**无标准换算**的数字（本批已评估未采纳该备选，理由即此）；
4. 与"读生效值"同族（W57）：**代理值 ≠ 真值**，渐变场景的代理值甚至有两个且互相矛盾。

**相关**：W57（改渲染面判据必须同步对齐三处读原值的点——同类"别拿代理值当真值"）、
W53（数字必须绑定观测条件——本条要求绑定到"哪条高度带"）。

---

## W60 · **XML mtime 早于源码 mtime ⇒ 该批结果不覆盖当前工作树**；且单类 `--tests` 跑会清掉本模块旧结果（2026-10-17）

**What happened**：2026-10-17 一整天，同一个陷阱被实证 **5 次**，每次形态都是
"盘上有全绿 XML、看起来基线是绿的"，但那批 XML **跑的是旧树**：

| # | 现场 | 假绿来源 |
|---|---|---|
| 1 | `:core:designsystem` 32 XML @10-16 23:12:36（187/0） | 源码 `PlayerOverlayScrimContrastTest.kt` 改到 **10-17 00:05** |
| 2 | `:feature/video` 13 XML @10-16 22:55:23（126/0） | 源码 `VideoPlayerPane.kt` 改到 **23:58** |
| 3 | `:feature/video` 13 XML @10-17 10:21:24（127/1） | 守卫 `VideoLogicGuardTest.kt` 改到 **10:22:15**（晚 51 秒）——那批红也是旧守卫跑的 |
| 4 | `:core/playback` 13 XML @10-17 10:48:04（98/1） | 守卫源码改到 **10:48:24**（晚 20 秒） |
| 5 | `designsystem` 32 XML @23:12 被**单类 `--tests` 跑清掉**，只剩 1 个 | Gradle `test` 任务**默认清本模块 `test-results`**，不是第三方删除 |

**Why it misleads**：`build/test-results/` 是**易失现场**，不是账本。
① 源码一改，旧 XML 立刻变成"覆盖旧树"的假绿；② 任何一次**单类跑**（破坏性验证、`--tests` 过滤）
都会**清空本模块全部旧结果**，把上一轮的全绿证据物理抹掉。
⇒ "我记得上次是绿的"不可验证；"盘上现在是绿的"同样不可靠——**它可能覆盖的是两小时前的树**。

**How to apply**（判据写死，顺序不要颠倒）：
1. **读任何测试结论前先比 mtime**：`XML mtime ≥ 最新源码 mtime` 才算**覆盖当前树**；
   否则一律记 **「未能验证」**，**不得记通过**——即使数字是全绿。
2. **单类 `--tests` 跑会清模块旧结果** ⇒ 破坏性验证可以用单类跑（快），
   但**之后必须补一次全量**，否则现场只剩那一类的 XML。
3. **重要绿批当场快照**：复制 `test-results` 到 `build/evidence/<模块>-full-<时间戳>/`，
   附 `BUILD.txt` + `MANIFEST.md5`（md5 逐文件）。今晚三批绿（designsystem 10:12 / video 10:31 /
   playback 10:50）都靠这个才留下证据；**没快照的那批（designsystem 23:12）已经没了**。
4. **汇总从快照自己算**，不读现场（现场可能已被后续跑冲掉）。
5. 跨 Bot 报"跑过了/绿了"时，**必须自附 BUILD 行 + XML mtime + tests/failed**（与 W53/W58 同族）。
6. **破坏性验证的顺序（2026-10-17 第四次实证后 Admin 立规）**：**单类篡改验证必须在全量跑之前**。
   "先全量再单类"是错的顺序——单类 `--tests` 跑会清掉本模块其余 XML，把刚建好的全量证据自己冲掉
   （本轮实录：篡改单跑后 `:core:designsystem` 现场只剩那一类，靠随后一次全量才重建 32 个 XML）。
   正确节奏：**篡改单跑（快）→ 复原 → 全量跑（建证据）→ 当场快照 + `md5sum -c`**。
7. **单类 `--tests` 探针规矩（第五次实证后 Admin 立规，13:5x）**：**① 起手先在群里报备**"我起一个单类探针（约 N 秒）"；
   **② 跑完立刻报"已恢复"**；**③ 不得与主线门禁连着跑**。
   第五次实录：移植手为验 B 上界起的单类探针，把 `:core:designsystem` 32 个 XML 清到只剩 1 个（`tests="1"`），
   而后面接棒的构建手拿到的是一个"看着在、其实只剩一类"的现场 ⇒ **"189/0"当场作废，模块重回「未能验证」**。
   **探针不是免费操作**：它和全量跑一样会改现场，只是方向相反（全量是建、探针是清）。
8. **`> Task` 行出现 ≠ 测试跑完（14:4x 立规）**：daemon 连接重置会**零 BUILD 行、零 XML**。
   实录：`r27-video.log` 裸 `> Task :feature:video:testDebugUnitTest` 已出现，随后
   `MessageIOException: Could not write '/127.0.0.1:3459' / Connection reset by peer`，
   **零 BUILD 行、零 FAILED、零 AssertionError**，XML 仍停在上一批 ⇒ 传输层故障，测试根本没跑完。
   ⇒ **判"通过"必须同时满足四条**：裸 `> Task` 行 + `BUILD SUCCESSFUL` + XML mtime 晚于源码 + tests/failed 计数；
   **只有 Task 行时记「未能验证」**。
9. **并发跑不只清 XML，还能把 BUILD 行打成假红**（第六证，14:4x）：两个 `testDebugUnitTest --rerun` 同任务并发
   写同一结果目录 ⇒ `NoSuchFileException: binary/in-progress-results-generic.bin`、`BUILD FAILED` 但
   **13 个 XML 逐类全绿、`<failure` 全仓 0 个** ⇒ **红的是构建系统的记账文件，不是测试**。
   判读顺序：先看 XML 的 tests/failures，再看 BUILD 行；两者矛盾时按"测试本体 + 重跑一次"处理，别急着改代码。
   ⚠️ **并发的规模要按顶层进程算**：按命令行匹配 `gradlew.bat` 会把**单条命令链**（`cmd → wrapper → daemon → …`）
   数成 11 个"并发"，实际同时只有 1 个构建（构建手两次自我更正；Admin 已撤回"并发违反铁律"的定性）。
   **但确有一次真实重叠**（r28/r29 同时活着 + PID 23028 半路插入）⇒ 记"一次重叠"，不记"11 个并发"。

**相关**：W53（并发的两种表现，0 字节 bin 是并发指纹）、W22（`--rerun` 只在单任务命令行生效）、
W58（派单正文不是源码——本条是"盘上现状也不是结论"）。
**W60 与 W53 合起来是本仓测试证据的完整纪律**：W53 管"别把并发当代码失败"，W60 管"别把旧树当当前绿"。

## W61 · **删常量 / 删死 import 也在挂载点之上**，照样触发玻璃链登记表漂移（2026-10-17）

设计师 v3 规格 §4.1 判「本波改动全在挂载点之下 ⇒ W27 零漂移、登记表不用动」。
**实测被触发两次**（`GlassMountRegistryTest` 钉表）：

| 笔 | 动作 | 挂载点漂移 |
|---|---|---|
| 第一批 | 删 `PlayerOverlayScrimLowerMid`/`Base` 等（净 **+3** 行） | 495/894 → **498/897** |
| 第二批 | 删 `PlayerPlayButtonScrim`/`PlayerSliderThumbScrim` + 死 import（净 **−7** 行） | 498/897 → **491/890** |

根因：`drawBackdrop`(`:495`)/`layerBackdrop`(`:894`) 之上的**常量区与 import 区**也算"挂载点之上"。
"改动在挂载点之下"只对**纯绘制/brush 改动**成立（brush 注释自述插在两挂载点之后 ⇒ 改它确实不触发）。

**How to apply**：
1. 判 W27 前先分类：**brush/绘制节点改动**（挂载点之下，不触发） vs **常量增删 / import 增删 / KDoc 行数变化**（挂载点之上，触发）。
2. 判断依据是**行号净变化的位置相对挂载点**，不是"改了哪个函数"。
3. 落盘后**必须实测行号**再更登记表（本批两笔都是"删完立刻实测"才没漏）。
4. 删常量前先 grep 全仓确认外部消费者为 0（`BiliRadius.playerProgressDock` 即如此删掉），
   否则删出编译错会被误判成 W27 问题。

**相关**：W27（登记表纪律本体）、W60（同批的证据纪律）、W58（派单正文不是源码——本条是"规格正文也不是源码"）。

## W62 · 解析型守卫的"停靠项必须写裸符号"是**假绿陷阱**：写成内联 `copy()` 不报错，而是**静默截断后全部外推**（2026-10-17）

`PlayerOverlayScrimContrastTest.parseStops`（`PlayerOverlayScrimContrastTest.kt:75-111`）从源码文本解析渐变停靠点：

- `:82` 停靠项正则右侧是**裸标识符** `([A-Za-z][A-Za-z0-9_.]*)`；
- `:86` 循环体**见 `)` 立即 `break`**；
- `:63` `colorAlphaOf` 反查要求常量在**本文件内**以 `Color.Black.copy(alpha = …)` 定义。

⇒ 把新档按文档字面写成 `0.644f to Color.Black.copy(alpha = 0.86f),`：
该行既不匹配正则、又含 `)` ⇒ **解析在此行截断**，只读到前几档，
之后所有断言的墨迹带 α 一律**外推成末档平台值** ⇒ **全绿，且不报任何错**。
这不是"改松凑绿"，是**连绿的理由都消失了**——比红危险得多。

**How to apply**：
1. 往 `colorStops` 加档必须**两行**：常量行 `private val Xxx = Color.Black.copy(alpha = 0.86f)`
   （这才是"单行 copy 形态"）+ 停靠行 `0.644f to Xxx,`（只写裸符号）。
2. 落盘后 grep 自查：停靠区**每一行右侧都应是标识符**，`colorStops = arrayOf(` 之后到 `)` 之前不得出现内联 `Color.Black.copy(`。
3. 设计文档里写渐变档位时，**写符号名不写内联式**（`d-ios4:143-153` 已按此改正）。
4. 同类风险适用于一切"从源码文本解析再求值"的守卫：**解析失败的下游必须是 fail-loud**；
   本例的 `out.size >= 2`（`:105-109`）挡不住"读少了但还剩 2 档"的情形，只能靠写法纪律。

**相关**：W26/W34（解析判据找不到锚必须红，不许静默通过——本条是它的镜像：**解析少了也别静默**）、
W60（证据覆盖当前树）、W61（同族的"文档写法坑实现"）。

## W63 · 历史规格文档里的对比度数，**前提消失后必须整篇标死**，否则会被当判据继续用（2026-10-17）

`d-ios4-A案-合成效果实测.md` 里的 **4.58:1** 算的是「未播轨 × 进度行黑45%胶囊底」；
本批把 `PlayerProgressDockScrim` 删了 ⇒ **前提消失，该数连同全文带"胶囊"的数一起作废**。
同类还有整篇的**基线过时**：按旧六档（末档 0.82、含 `0.44f`/`0.60f` 两档）算的，
而现树是五档 `0/0.10/0.24/0.36/1` 全平 0.88、`Base`/`LowerMid` 已删、未播轨保持白 0.42（未提到 0.50）。

**How to apply**：
1. 规格文档每被一批代码推翻，**作者当场在抬头加"历史文档/基线过时"警示**并列出作废项，
   不要只在新文档里写（下一轮翻到旧文档的人不会知道有新文档）。
2. 台账侧同步：作废数进 PROGRESS「作废标注」小节，与「中段 0.14」「4.41:1 取错底」同一待遇。
3. 真机判据引用规格数之前，**先核该数的前提在当前树是否还成立**（本例：胶囊都没了，4.58:1 无从谈起）。

**相关**：W59（可读性要量文字所在横带的反推 α，不能拿末端值代入）、W62（同族的文档/写法陷阱）、
「旧数值作废」规则（任何标了具体数值的旧记录，落地前必须回源码核现值）。

## W64 · 快照 MANIFEST 必须 GNU 格式且**建完当场跑一次 `md5sum -c`**——"纪律执行了但没人跑过校验"等于证据链缺最后一环（2026-10-17）

今晚 10 个 evidence 目录里并存四种 MANIFEST 格式：GNU（认）、BSD `*` 前缀（`md5sum -c` 不认）、
BSD `*` + `./` 前缀、CRLF 行尾。⇒ **"快照可自证"这条纪律执行了，但校验那一步从来没人真跑过，一跑就全红。**

**How to apply**（Admin 13:1x 已裁为硬规矩，构建手 13:2x 实测补全）：
1. 生成一律：`cd <快照目录> && md5sum -t BUILD.txt *.xml *.log > MANIFEST.md5`。
   **`-t` 不能省**：GNU coreutils 在 Windows/MSYS 上检测到"可能是二进制"会自动切 BSD 风格加 `*` 前缀
   （台账员实测：`md5sum t.txt` → `9dd4… *t.txt`；`md5sum -t t.txt` → `9dd4…  t.txt` 两空格无 `*`）。
   **这就是四个目录里 `*` 的真正来源**，不是手写错的。
2. **覆盖范围 = 目录内除 MANIFEST 外全部文件**（含 `BUILD.txt` 与原始运行日志）——
   只对 `TEST-*.xml` 生成会把 BUILD.txt/日志漏出校验范围（构建手第一版就把条目从 34 掉到 32）。
3. **建完必须当场跑 `md5sum -c MANIFEST.md5` 并把汇总行贴进回执**——不跑不算快照完成。
4. 台账侧汇总前自己跑一遍。台账员 13:2x 实测修复后 **10/10 全 PASS**，
   且 `entries == 目录内文件数`（34/33/33/33/15/5/15/14/14/14）、`*` 行 0、bad 0。
5. 判"快照内容 vs 现场"用 `cmp` 逐文件；判"快照自身完整"用 `-c`——两件事别混。
   `build/` 下全部 git-ignored ⇒ MANIFEST 重生成**不进 diff**（`git status` 仍 79 处）。

**修复前的失败分布**（构建手实测，台账员未亲测修复前状态 ⇒ 按通报记）：10 个里 **6 个不能校验**
（CRLF 5 个：`r21-ds-1123`/`r21b-ds-1212`/`r20-settings-1058`/`r20-video-1031`/`r21-video-1140`；
BSD `*` 2 个：`r20-ds-1012`/`r20-playback-1050` 能靠规范化脚本救回、`r22b-ds-1257`/`r22c-video-1257` 同形态）
⇒ **六类失败、四种格式并存**。

**相关**：W60（证据覆盖当前树、当场快照）、W53（并发指纹）、W62（解析型判据的假绿——本条是**证据侧**的同类）。

## W65 · 反查常量的正则写死颜色类 ⇒ 新增白色常量会让"绑定源码值"的断言**静默返回 null**（2026-10-17）

`PlayerOverlayScrimContrastTest.colorAlphaOf`（`:62-63`）的正则写死 `Color\.Black\.copy\(`。
本批新增的 `PlayerCtrlOptionDim` 是 **`Color.White.copy(alpha = 0.60f)`**（`VideoPlayerPane.kt:357`）
⇒ `colorAlphaOf("PlayerCtrlOptionDim")` 返回 **null**。

后果有两档，都要防：
- 若断言直接 `colorAlphaOf(...)!!` ⇒ **NPE 红**（看得见，尚可）；
- 若断言写成 `?: 旧值` 或有 fallback ⇒ **静默沿用旧值，假绿**（看不见，最坏）。

**How to apply**：
1. 要"绑定源码值"而不是硬编码，把正则放宽为 `Color\.(?:Black|White)\.copy\(`——**必须用非捕获组 `(?:…)`**。
   **写成捕获组 `(Black|White)` 会反噬**：`groupValues[1]` 拿到的是 `"Black"`/`"White"` 而不是 α 值，
   `toDoubleOrNull()` 返回 null ⇒ 反查失败（构建手 13:2x 实测确认；他上一条给的正是捕获组写法，被移植手纠正）。
   **纯增配成立**：现有五档全走 `Color.Black.copy`，放宽前后解析结果一字不变（脚本复算，非推断）。
2. 反查不到定义时**必须 fail-loud**（现 `:96-100` 的 `assertTrue(a != null)` 就是对的方向），
   不许给默认值。
3. 同族盲区：`parserActuallyReadsRealValues` 只断言"起 0 止 1、单调不减、层高"，**不断言档数**
   ⇒ "读少了但还剩 2 档"能过；建议补 `>= 5`（与 W62 第 4 条同源）。
4. 放宽正则这类"纯增配"改动也要**先写等价性验证脚本再跑批**——一次 `:core:designsystem` 是 7 分钟，
   写法错就白烧一轮。

**相关**：W62（停靠项裸符号 / 见 `)` 截断）、W26/W34（解析判据找不到锚必须红）。

## W66 · 「想让遮罩往底部收薄」会撞**单调不减守卫**——设计愿望与既有判据冲突时，先判守卫编码的是不是真性质（2026-10-17）

Admin 13:3x 方向表写"`0.36 起 0.72 平台、**往底部收到 0.55**"；设计师 13:4x 报不可行，构建手也算出自己的数错了。
台账员回读核实：`PlayerOverlayScrimContrastTest.kt:442-445` 断言
`stops.sortedBy{it.first}.zipWithNext().all { (a,b) -> b.second >= a.second - 1e-6 }`，
且 `:250-261` 的求值段本身会先 `sortedBy` 再插值。

⇒ **α 随 f 只能不减**，"平台后再往底部降"必然红。而这条守卫编码的是**真性质**
（比例递增而 α 下降 ⇒ 文字带可能落进更亮的段），不是形态洁癖。

**How to apply**：
1. 设计侧提"收薄/递减遮罩"时，**先问一句"现有单调守卫怎么办"**，再报数——本次两位报数的人都撞了同一条。
2. 可行解只有两种：**整体平台压低**（0.36→1.0 全平一个更低值，仍单调不减），
   或**改守卫**（那属于放宽既有判据，必须 Admin 裁 + 破坏性验证 + 写明理由，不许默默改）。
3. 报"不可行"必须带**守卫行号与断言原文**（设计师带对了），否则接收方无法判断是真约束还是猜的。
4. 台账侧：**方向表里被否掉的格子要当场标「不可行 + 根因」**，否则下一轮会照着一张作废的表落地。

**相关**：W59（可读性要量文字所在横带的反推 α——压低平台会同时影响这条，必须重算）、
W63（前提消失要标死）、W62（同类：设计/规格侧与判据侧对不上）。

## W67 · **同批编辑之后，当事人引用的仍是编辑前的行号**——漂移方向固定为正（补 KDoc 只会加行）（2026-10-17）

移植手 13:2x 报「播放页浮层语言清点」，表里引 `PlayerPillScrim:243`/`PlayerPillShape:268`/白0.68 `:245`/黑72% `:254`/20dp `:272`。
这些是**他自己 13:19:22 那笔 `0.36→0.50` 编辑之前**的行号；台账员 13:20 回读现值是
`:251`/`:276`/`:253`/`:262`/`:280`（**+6~+9 行**）。

⇒ 连**报数人自己**都会在编辑后引用编辑前的行号，因为他的注意力在"改对了没有"，不在"行号变了没有"。
**How to apply**：
1. 台账/规格引用任何行号前**当场回读**（本仓铁律，此处是第 N 次实证）；
2. 报数人交表时若同批有编辑，**表要按编辑后重出**，或在表头标"行号为编辑前快照"；
3. 漂移方向可预期：**补 KDoc/注释只会让行号变大**，所以"报的行号比现值小"就是这类漂移的指纹。

**相关**：W58（派单正文不是源码）、W61（删常量也推动挂载点——同族的"位置会动"）、
W27（行位置改动必须同批更钉表）。

## W69 · 「缺口自述」注释在缺口闭合后必须同批改——否则它会变成**反向谎**：让人以为改坏了不会红（2026-10-17）

`VideoPlayerPane.kt` 的 KDoc 长期自述"轨道对比度没有断言保护、改坏了不会红"
（`:1876-1878` 与 `:300`）。13:35:47 轨道断言 `progressTrackClearsTheNonTextFloorAtItsRealAlpha`
（`PlayerOverlayScrimContrastTest.kt:557`）真的落进去了，缺口闭合——**但这两句注释没跟着改**。

⇒ 危害与 W63 同向但更隐蔽：W63 是"注释说的前提已消失"，本条是"**注释说的保护还不存在，其实已经有了**"。
下一轮谁读它，会 (a) 以为推起点不会红、放心改；(b) 或者重复补一条同样的断言。
**同批实录**：同期另两处旧数 KDoc（`:288-296`、`:312-315`）已在 13:36:35 改掉，
说明"改数"比"改缺口状态"更容易被想到——**缺口状态要单独列进同批清单**。

**How to apply**：
1. 补守卫/补断言的同批清单里，**必须单列一条"改掉所有自述该缺口存在的注释"**，
   并 grep 关键字（如"没有断言保护""已登记的缺口""目前没有"）确认 0 命中。
2. 反过来也成立：守卫被删/放宽时，自述"已被保护"的注释同样要改。
3. 台账侧记录缺口时**写"闭合时间 + 闭合位置"**，便于下一轮回读校验（本条：13:35:47 / `:557`）。

**相关**：W63（前提消失要整篇标死——本条是它的镜像：保护出现了也要标）、W67（同批编辑后引用编辑前的行号）、
W60（门禁覆盖当前树——本条断言虽已落盘，但**尚未被任何门禁盖过**，记「未能验证」）。

## W68 · 「去某形态」不可外推成「去该类手段」——10809 把"去 BiliPai 胶囊比例"做成了"去按钮的底"（2026-10-17）

10809 的控制行改成"白线语言"：`PlayerIconButton` 无底图标 + 档位纯文字无底无框。
Admin 13:2x 自纠：**从"胶囊 = BiliPai 形态"一路推到"按钮不该有底"是判错了**——
**错的是胶囊的圆角比例，不是"有底"这件事**（YouTube/B站/抖音都有底，"给按钮一个可见底"不是模糊专属）。
用户 13:2x 的原话正好戳中后果：**「现在的就文字摆在上面太单调」**。

**How to apply**：
1. 反对一个具体形态（圆角比例、某个语言）时，**只否那一项**，写下"否的是 X 不是 Y"，
   否则执行侧会把承载层级的手段一起删掉（本次删掉了"底"）。
2. 观感类改动要有**可判据的承载检查**：每个可点件是否有底/层级是否分三级/信息件是否降级——
   "没有底"要作为显式判据，不能靠"看起来干净"判断。
3. 用户反馈"单调"时，**先查是不是上一轮删多了**，别急着加新东西（本次正是删过头的后遗症）。
4. 台账侧：**防回退清单要随自纠更新**——本次已把"零胶囊/无底"修正为
   "针对 BiliPai 圆角比例的正确动作，但不可外推成按钮不该有底"。

**相关**：W63（前提消失要标死——本次是"结论的外推范围"要标死）、UI-EDGE 批次（同一用户的上一轮否决，
"去容器化"教训同族：用户否决过"一排白字悬在画面上"，本次是"一排光字"的变体）。

## W70 · **写数值前先找到约定是怎么定义的，别自己推**——同一个仓里 `f` 的方向，测试侧与源码 KDoc 必须一致（2026-10-17）

Admin 复算轨道对比度时用 `f = 1 − y_距盒顶/H`，得出轨道 f ∈ `[0.5932, 0.6102]`；
而本仓真实约定是 **`f = 1 − 距盒底/H`**（源码 KDoc `VideoPlayerPane.kt:3270-3271` 自写 `f=0.381/0.407`，
测试侧 `PlayerOverlayScrimContrastTest.kt:579-580` 用 `1.0 − topFromBottom/h`）。
两套约定下"哪一端更紧"的结论**相反**——Admin 因此要求把取样从上沿改成下沿，
而按真实约定**上沿才是最紧端**，改过去会把守卫改松（台账员按常量复算后发现并拦下）。

**怎么发现约定错了（两条独立佐证法）**：
1. **代入一个已知点校验**：墨迹带距底 19~31dp，按真实约定 f=0.737~0.839 ≥ 0.50 ⇒ 落在平坦段 ⇒ α=0.88（与实测一致）；
   按 Admin 的反约定会落到 f≈0.26~0.36 ⇒ α 远小于 0.88，**与事实矛盾** ⇒ 约定错。
2. **找源码里已写下的数值**：KDoc 白纸黑字 `f=0.381/0.407`，与自己推的对不上就是信号。

**How to apply**：
1. 报任何 `f`/比例值前，**先在源码或测试里找到该量的定义式**（不是"应该是"），再代入；
2. 用自己的约定算出一个数后，**必须找一个已知点反验**（本例的已知点：墨迹带 α=0.88）；
3. 同一物理量在两处（测试 helper 与 KDoc）各写一套数时，**以定义式为准**，并在注释里互相引用；
4. 派生结论（"哪端更紧"）不要只靠语言描述（"离盒底更远"），**把两个端的数都算出来比大小**——
   Admin 的语言描述其实是对的（上沿离底 71 > 下沿 69），但结论写反了，**数一比就露馅**。

**相关**：W59（可读性要量文字所在横带的反推 α，不能拿末端值代入——同族的"代入点/约定"错）、
W67（行号漂移）、W62（解析判据静默失败）。

## W71 · **量某元素前，先确认它没被兄弟节点盖住**——今晚两起"量错对象"同源（2026-10-17）

两起实录，都不是工具错、不是公式错，是**量的那块东西不是你以为的那块东西**：

| # | 报的数 | 错因 |
|---|---|---|
| 1 | 未播轨亮度 **71/255** | 量在**拖动态**，而那个位置正被**时间气泡的黑底**盖住（气泡 x≈697-879）⇒ 把"遮罩后的轨"当成了轨本身。暂停态五段重测中位数全 117~122 ⇒ **真实基线 122/255、与遮罩区差 98**（不是 43~52） |
| 2 | （早前）玻璃圆用亮度法量 | **暗叠暗**——被测物本身就半透明压在别的东西上 |

**How to apply**：
1. **量之前先问"这一帧里这个位置还有谁"**：拖动态/浮层出现时，元素可能被气泡、按压叠色、Toast 盖住；
2. **同一量至少换两个状态各测一次**（静止 vs 拖动、控件显 vs 隐），**对不上就说明有一帧量的不是它**；
3. **报数必须带状态前缀**（"暂停态"还是"拖动态"），不带状态的亮度/尺寸数一律按不可复用处理；
4. 台账侧：**旧数发现量错对象要当场标作废并写错因**（本例 71 → 作废，122 为准），
   否则下轮会拿着一个"量错了对象"的数当基线（V2 风险本来就要靠它判）。

**相关**：W60（证据覆盖当前树——同族的"这份证据量的不是当前这个东西"）、W53（并发指纹：
别把环境表现当被测物）、真机手自述"两次都不是工具错，是量之前没确认量的那块东西没被别人盖住"。

## W72 · **量任何元素前，先报一个"应恒定"的不变量；对不上立刻停**——不许直接对期望值（2026-10-17 立）

真机手把口头纪律变成可执行形式，**他自己今晚两个错都能被这条拦住**：

| 错 | 当时直接对了期望值 | 该先对的不变量 |
|---|---|---|
| 未播轨量成 71/255 | 量完就报亮度 | **轨高应恒为 7px(2dp)** —— 若同时报轨高，会发现那 71 是气泡黑底上的轨，形态对不上 |
| 玻璃圆亮度法量出 68 | 量完就报亮度 | **直径应恒为 42px** —— 差 26px 早已超出抗锯齿，是量到了别的东西，**当场该停** |

他三个脚本已内置这类自检（ whoever 用之前先看它报不报）：
- `thumbdisc.py`：水平/垂直两次扫描，**差 >3px 就报"平行轴被污染"**；
- `dragarc.py`：圆拟合**残差 rms >0.5px 就说明那截弧不是同一个圆**（拖动态 0.018px 所以才敢用）；
- `fix01_icon.py`：报告**最右一组 + 右沿 + 右 margin**，图标被挤时立刻看出宽度不对（45 vs 失败的 9）。

**How to apply**：
1. **报任何一个测量值，必须同时报它的不变量**（高度/直径/右沿/边距——同一个元素上不该变的那一项）；
2. **先对不变量，再对期望值**；不变量对不上 ⇒ 量的不是这个东西，**停，不要报期望值**；
3. 台账侧：**不带不变量的测量数按不可复用处理**（与 W71"报数必须带状态前缀"配套：
   状态 + 不变量 + 期望值，三样缺一不可）。

**相关**：W71（量之前先确认没被兄弟节点盖住——本条是它的可执行化）、W60（证据覆盖当前树）、
W53（别把环境表现当被测物）。

## W73 · **作废的规格数字不许删，要包在 `<details>` 或标"作废 + 错因"**——今晚四起"旧值当依据"全源于此（2026-10-17 立）

Admin 立规（原话）：作废的规格数字**不许删，要包在 `<details>` 或标注"作废 + 错因"里**。
今晚四起"旧值当依据"：**渐变两档 / 中段 0.14 / "无断言保护"自述 / 加底公式方向**——
根因都是**旧稿留在盘上、没人标作废，下一轮翻到就当依据**。

**为什么"留着但标作废"比"删掉"好**（Admin 原话）：删了下轮还会有人**凭记忆重推一遍**；
留着带作废标记，下轮一眼看到"这已作废 + 为什么错"，省一次重推，也省一次争吵。

**How to apply**：
1. 规格/KDoc 里的数字被判错时，**整表包进 `<details><summary>（已作废的旧表，保留仅供追溯）</summary>`**，
   表内数值加 `~~删除线~~` + `❌作废`，**表外写明正确值在哪一节**（`d-ios11` 已是范例：§1 整表作废、§1′ 出真值）；
2. 台账侧同步：作废数进 PROGRESS「作废标注」小节并写**错因**（不是只写"作废"）；
3. **连带约束也要标适用条件**：一条裁定只在某个形态下成立时（如"按压提亮到 1.00"只在"档位组有常驻底"时必要），
   **必须写清适用条件，不能做成硬性规则**——否则下一轮会让代码去满足一个已经不存在的约束；
4. 评审规格时**先搜 `<details>` 与"作废"标记**，确认引用的数不在作废区里。

**相关**：W63（前提消失要整篇标死——本条是它的执行细则：怎么标）、W69（缺口自述要同批改）、
W67（同批编辑后引用编辑前的行号）、W68（否一项别外推成一类）。

## W74 · **冻结靠看门狗工具保证，不靠人的记忆**；禁改点必须带"当前值"（2026-10-17 立）

今晚 14:0x 前，源码冻结一直靠"我记得我说过冻结"这种**不可验证的东西**当保证，结果**死令被破四次**，
每次都要人肉 find 一遍 mtime、再群里追问"谁改的"。

**真机手做了个看门狗**（`chk01/watchdog.sh`，后台跑，每 20s 扫一次源码、**排除 docs 与 build**），
把基线钉在 14:11:37，**任何新源码变动立刻报**；分工也随之定清：**真机手看门狗、构建手跑批**（职责不冲突）。
Admin 采并立为团队工具，**下一批照做**。

**配套第二条：禁改点必须带"当前值"，不只写行号。**
理由：今晚四次破令里**至少有一笔是"以为自己在改别的东西、实际碰到了行号附近"**——
**光写行号看不出被动没被动，带值才能一眼看出**。定稿写法：

```
① 停靠行      :1896 = 0.50f
② 控件行结构   :2921-2939（四件未动）
③ 滑块/轨道常量 :325 / :326 / :337（2dp / 12dp / 1.18）
```

**How to apply**：
1. **下冻结令 = 三件套**：① 禁改点（行号 + 当前值）② 看门狗（谁跑批谁开，或专人开）③ 一句"破令了当场点名"的分工；
2. 看门狗脚本要**排除 `docs/` 与 `build/`**——今晚 5 笔 mtime 变动里 4 笔是文档，不排除就会天天误报；
3. 跑批人起跑前**先看一遍看门狗基线**，确认测的是哪棵树（真机手原话：只要接下来 8 分钟没人再动，这批就有效；再动一次就又是第三次作废）；
4. 台账侧：**"冻结成立"这句话必须附看门狗/实测证据**（mtime + 时间点），不许只写"我说过冻结"。

**相关**：W60（证据覆盖当前树——本条是它的前置：没有冻结就没有"当前树"）、W67（行号漂移）、
W73（作废数字不许删——同族的"盘上旧物会被当依据"）。

## W75 · **用户等的是看得见的变化；数字进展必须翻译成"你手机上会看到什么"**（2026-10-17，用户失望换来的）

用户原话（14:3x）：**「搞了几个小时了，我都不清楚你们到底在干什么？把我项目都搞坏了」**。
事实是项目没坏（HEAD `87fefe6d` 未动、79 处未提交、designsystem 32 XML 全绿、手机上 10809 能播），
但这几小时团队产出的是**数字**（对比度口径、守卫、预检表、行号勘误），
**用户等的是"看一眼新 UI"**——他看不到对应关系，于是判断我们搞坏了他的项目。

**How to apply**：
1. **每轮汇报必须有一句"用户侧可见结论"**（"你手机上会看到 X 变了 / 还没变，因为 Y"），
   不许只报 tests/failures/mtime；
2. **纯内部工作（守卫、口径、行号）不许单独当进展汇报**——它们要翻译成
   "它保证了你会看到的哪一项不出错"，或者干脆不汇报；
3. **等待超过一轮仍无用户侧交付时，立刻停止加新活**（今晚的教训：一边说锁定一边派活，
   派活的人对并发负责——Admin 自认，台账员 13:5x 即指出）；
4. **用户表达怀疑时，先给完整性证据再给解释**（HEAD / 未提交数 / 门禁状态 / 装机版本四件套），
   证据要能当场复算。

**相关**：W60/W74（证据与冻结纪律——本条是它们的"为什么"）、W67（行号漂移：纯内部工作的典型）。

## W63-A · **几何一变，该文档里所有比值都要重算**——不是只算改动的那一个（2026-10-17 升级）

W63 原本管"前提消失要标死"。今晚 `4.13 → 3.09 → 2.57` 一案把它升级：
**引用任何历史文档里的对比度数字前，必须先核对它写作时的几何是否还存在。**

台账员用 `ContrastMath.kt:84-90` 原公式独立复算（当前几何、全屏态紧端 α=0.7335）：
未播轨白 0.42 = **3.154**、白 0.50 = **3.796**、已播 9.759
⇒ **已播:未播 = 3.09（现状）/ 2.57（提亮后）**，与通报逐位一致；
**2.57 < 3:1 ⇒「一眼可辨」门槛破线**（`d-ios13:104` 自写的门槛）。

**为什么这次比前几次严重**：前几次是**数错了、方向还对**；这次是**数错把方向带反了**——
设计师基于旧几何表推出"下一版先动轨道白度"，而实算是提亮会破自家 3:1 门槛 ⇒ **方向性结论已撤回**。

**最危险的一类：「过程错但结果碰巧对」**。表中 3.79 这个数在两种几何下都出现（旧几何"轨道坐 0.88 底"/
当前几何"0.50 轨坐 0.7335 底"）——**看起来无需修正，还会被当佐证继续引用**。
（台账员标注：新几何的 3.796 已复算证实；旧几何那个 3.79 的推导路径复现不出——α=0.88 下白 0.50 应得 5.12、
α=0.72 下得 3.66 ⇒ **"巧合相同"按通报记、推导待核**，不影响主结论。）

**How to apply**：
1. **改起点/平台值/层高任一 ⇒ 把所有引用旧比值的文档全量标"几何已变、待重算"**，不是只改手上那一处；
2. 文档里每个对比度数**带几何签名**（起点 / 平台值 / 层高 / 取样端），不带签名的数按不可引用处理；
3. **方向性结论必须写在数的同一张表里**——数作废时结论一起封死（`d-ios14 §7` 是范例：不删数字，连结论一起标作废）；
4. 见到"这个数我两种算法都对上了"时，**先怀疑是不是巧合**，把两种算法的中间量（α、f）都打出来比。

**相关**：W63（前提消失要整篇标死——本条是它的几何版）、W73（作废数字不许删）、W59（代入点/约定错）、
W70（写数值前先找约定定义）、W75（别在用户等的时候算下一轮）。

---

## W76 · **交接书必须分角色写**：总账里没有证据索引、快照目录与未验证项（2026-10-17 立）

用户 14:4x 喊停并要求写交接书时，管理员写的是**总账**（`docs/2026-10-17-交接书与升级计划.md`，192 行）：
现状、做了什么、数字陷阱、踩过的坑、建议下一步。**它一条都没错，但它缺三样接手方必须有的东西**：

1. **证据索引**——哪批绿有效、快照目录在哪、怎么自校验（`md5sum -c MANIFEST.md5`）；
2. **三态清单**——哪些是"通过"、哪些是"失败"、哪些是**"未能验证"**（10810 判图、流畅度、缩层高全部未能验证，
   总账里只写"规格完成未落地"，读起来像"已完成设计所以快好了"）；
3. **作废数清单 + 错因**——`4.13/3.09/2.57`、`6.90/7.09/17.24`、`71/255` 未播轨、`3.79` 旧几何，
   这些数今晚各被引错过一次，总账只提了结论没列数。

**病灶与 W67 同族**：**派单人/总账写作者手里没有"哪批证据还有效"的盘面**，只有台账侧有。

**How to apply**：
1. 交接书至少两份：**总账（管理员）+ 证据与三态（台账）**，互相引用不互相代替；
2. 台账侧交接书必须包含：盘面快照四行（HEAD / 未提交数 / APK md5 / 源码冻结 mtime）+ 快照目录表 + 每条计划的三态；
3. **"规格就绪"必须与"已落地"分列两栏**——今晚 `d-ios15` 预检表三行全绿、只有第一行真在树上，
   差点被读成"三条都已验证"（设计师已在文档里标死，台账侧在升级计划表里用 `规格就绪未落` 复述一遍）；
4. 接手方第一件事：**复核盘面四行**，不是读结论。

**相关**：W67（引用前不核盘面）、W72（测量三要素：状态+不变量+期望值）、W73（作废数不许删）、W60（哪批绿有效）。



