# BACKLOG —— ai-loop 队列

> 优先级：**A-稳定 → B-流畅 → C-好看**；同级内 P0 > P1 > P2。
> 每条含：编号、标题、模块、根因线索、A/B 级标注、验收标准（机器可判）、状态。
>
> **状态图例**：`待办` / `待复核`（来自审计报告，本人尚未读源码确认 —— 见 FINDINGS W4：
> 交接文档的断言必须自己复核后再采信）/ `进行中` / `已关闭(见 PROGRESS)`
>
> **已复核确认为真（2026-09-26，直接读源码核过）**：ST-04（Geetest `WebView(ctx)` 全文无
> `onDispose`/`destroy`）、ST-05（`BuvidFetcher.kt:77` 无 `.use`/`close`）、
> UI-08（`materialTypography` 全仓仅定义处一个符号，零调用）、
> UI-10（`IosDarkLiftedBackground` 私有且零引用）。
>
> 来源：2026-09-26 三面源码审计（崩溃+协程 / 生命周期+状态 / 资源+内存+网络）
> + 任务书 §1 的 9 处 TODO 埋点 + `docs/2026-09-22-BBSpace可执行优化方案.md` §9.3 遗留。

---

## A-稳定

### P0（崩溃 / 数据丢失）

| 编号 | 标题 | 模块 | 根因线索 | 级 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|
| ST-01 | Room 建库既无 migration 也无回退，**下一次实体变更即硬崩** | `core/download` `feature/space` | 已复核修正（原审计判为"正在发生的 P0"，不成立）：`VideoDownloadDb` 与 `SpaceNoteDb` 的 `version = 2` **是出生值**（`git log -L` 查到 `0c316e59 add Offline caching`、`bbe7d220 feat: space note` 首次引入即为 2），所以不存在 v1 老用户当前崩溃。真实缺陷是：`databaseBuilder` 链上零 `addMigrations` 零 `fallback`，且 `exportSchema=false` 没有留下任何 schema 快照 —— 下一个改实体的提交一定会让升级安装 `IllegalStateException` 硬崩，而那时已经写不出 migration | A | 先做**可差分**：`exportSchema=true` + `room.schemaLocation` 落地 JSON；结构守卫断言"每个 `@Database(version=N>1)` 必须给出 N-1 条 migration 或显式回退并写明理由"（B11 落地为 `RoomSchemaGuardTest` 7 条 + 5 份快照入库） | **已关闭(B11)** |
| ST-02 | 历史库靠 `fallbackToDestructiveMigration(true)` 升过版本 → **用户观看历史已被清过一次** | `core/history` | 已复核：`PlaybackHistoryDb` 在 `215575de del local historymeta` 里 version 2→3，而 `PlaybackHistoryRepository.kt:63` 用破坏性回退 —— 那次升级把本地观看历史清空，且没有任何日志/提示。`PublishedRecordRepository.kt:125` 同款写法（该库仍 v1，属埋着的雷） | A | 这两个模块禁用 `fallbackToDestructiveMigration` 的守卫测试 + 用 ST-01 导出的 schema 补真实 Migration（B11 落地：`Migration(1,2)` 含主键重算 + `Migration(2,3)`，两处回退全删，`RoomMigrationCopyListTest` 5 条 + 本机 SQLite 回放） | **已关闭(B11，Room 侧执行待 L4-14)** |
| ST-28 | 把 schema 演进交给 Room 官方工具（可选项，不是缺陷） | 构建 | B11 用"手写迁移 + 自建守卫"达到可差分与可校验。若要更省人力：catalog 里的 `libs.plugins.room`（`androidx.room` Gradle 插件，**目前没有任何模块应用它**）可开 `verifyRoomSchemas`；`@AutoMigration` 需要历史快照，而 v1/v2 快照是本次**事后重建**、不是当时导出的，自动迁移未必认 | A（工具性） | 先只在一个库上试，比较它与 `RoomMigrationCopyListTest` 的覆盖面，不重复设防 | 待办（低优先） |

> **B11 复核修正**（照 W4/W6 复核这两条时发现本表两处低估，动手前改了严重度）：
> 1. ST-01 只说"没快照"，实际是 **`bbspace.android.room` 约定插件早已存在但无人应用**，
>    且它自己不设 `room.schemaLocation` —— "导出快照"这件事从来没有归属，所以修法是接通插件而不是逐模块加参数。
> 2. ST-02 只记了 2→3，实际该库**出生版本是 1**（当时叫 `LocalHistoryDb`），1→2 也只靠破坏性回退兜底；
>    v1 的表叫 `local_history`、主键比 v2 起**多一段 `:video:`**。
>    只补 2→3 会让停在 v1 的安装从"被清空"变成"打开即崩"，所以两步一起补。

### P1（泄漏 / 竞态 / 状态丢失）

| 编号 | 标题 | 模块 | 根因线索 | 级 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|
| ST-03 | ~~`PlaybackHostState.onPlaybackUiStarted` 挂在 `@Singleton` 上从不清理 → 泄漏 Activity~~ | `app` | 已修：回调提为 `val` 字段 + `onDestroy` 按身份比对清理 | A | `SingletonCallbackLeakGuardTest`（4 条，通用条从真值源反推声明名） | **已关闭(B2, `fix(app)`)** |
| ST-23 | 通知权限"待弹意图"存在 Activity 字段里，Activity 死了意图即丢 | `app` | B2 的取舍：`pendingNotificationPermission` 留在 MainActivity，清理回调后 `expand()` 不再记账 | A | 需先定语义（跨进程生存在本仓是否必要）；不急，B2 已保证不泄漏 | **已关闭(B18)**：标志挪到 `PlaybackHostState.notificationPromptPending`（@Volatile，**只存布尔量**，不存 Activity/回调 —— 那正是 B2 修掉的泄漏形状）。复核时也下调了影响：丢了不会永久沉默，下次触发后台播放会重新置位，所以是"这一次不问"而不是"再也不问" |
| ST-04 | Geetest 验证码 `WebView` 无 `destroy()` | `feature/auth` | `GeetestDialog.kt:38` `AndroidView(factory={WebView(ctx)})` 无 `onDispose`；对照正确写法 `WebViewScreen.kt:187` | A | 结构守卫：`src/main` 内每个 `WebView(` 所在文件必须含 `onDispose` + `destroy()` | 待复核 |
| ST-05 | ~~`BuvidFetcher` 的 OkHttp Response 未关闭~~ | `infra/crypto` | 已修并把 `return@runCatching` 改 `return@use`。**复核修正**：`string()` 正常读完会自己关流，所以审计所述"三条早退全漏"不成立，真实漏点是 `string()` 抛错与 body 为空分支；因连接池仅 10 且此路冷启动必走，仍判 P1 | A | `HttpResponseReleaseGuardTest`（全仓 `.execute()` 19 处，白名单精确比对只许减） | **已关闭(B3)** |
| ST-06 | ~~core/infra 剩余吞取消~~ | `core/sponsorblock` `feature/download` `infra/*` `app` `feature/bbspace` `core/download` | 收口清单（全部 `runCatchingCancellable` 或宽 catch 前加 CE 重抛）：SponsorBlock 3 处 + OfflineDanmakuSession 1 处（B8）；TicketGenerator 外层与重试循环内层 + ColdStartClient + GaiaReporter + AppInitializer 预热（B9）；VideoDownloadRepository.cacheDanmaku + AppInitializer 转场默认值迁移（B10）；BbSpaceScreen 导入 json（B12，取消曾被打成 Toast「导入失败」）。infra 各模块本已依赖 `:core:common`，未动依赖方向 | A | `CancellationSweepGuardTest.migratedFilesStayMigrated`（逐文件"下限+上限"登记，沿用 `VideoLogicGuardTest` 范式；B12 把 BbSpaceScreen 登记为 1 to 0 —— 该文件原先只有 1 处 runCatching，再出现第二处就该先说明理由） | **已关闭(B8 core 4 处 + B9 infra 5 处 + B10 再收 2 处 + B12 最后 1 处，core/infra/feature 三边都收完；剩下的两类是刻意不收口的 ST-06b)** |
| ST-06b | ~~两处"提交成功后写本地记录"**不能**换成传播取消，要改用 `withContext(NonCancellable)`~~ | `core/comment` `core/live` | 已修（B14）：两处都改成 `withContext(NonCancellable) { runCatching { save(…) } }`。判据没变 —— 那时评论/弹幕**已发送成功**，传播取消会把成功的发送改判为失败（输入框不清空/列表不刷新），比原缺陷更伤用户 | A | 守卫从"注释还在就行"升级成三条：记账必须落在 NonCancellable 包里 / 未包站点按文件登记上限棘轮（comment 0、live 2）/ ST-06b 标记注释不许删 | **已关闭(B14，取消窗口待 L4-17)** |
| ST-07 | ~~离线播放器与流会话共用 `@Singleton PlayerEngine`，无所有权仲裁~~ | `core/playback` | 已修（B13）。**复核修正**：原描述只点了缓存页一侧的 release，实际**双向都有**——流会话的 `finishVideoPlayback` / `finishLivePlayback` 同样无条件 `release()` / `stopForReuse()`，且 `stopForReuse` 一样会打断别人的在播会话；往引擎写媒体的点也不是 1 处而是 **4 处**（流 3 + 缓存 1）。新增 `PlayerEngineArbiter`（`AtomicReference` + CAS）：写媒体前先 `claim`，只有占用者能 `release`（交还）或 `stopForReuse`（保留） | A | `PlayerEngineArbiterTest`（8 条 JVM 状态测，含并发收尾只放一次）+ `PlayerEngineOwnershipGuardTest`（6 条扫接线） | **已关闭(B13)** |
| ST-08 | ~~`VideoDetailActivity.onNewIntent` 少做三件 onCreate 做的事~~ | `app` | 已修可静态判定的两条：缺 `cancelPendingClose()`（`PlaybackHostState` 写死的契约）与 `setIntent` 排在解析前。**第三条 `capsuleState.deactivate()` 刻意未做**：其锚点 detach 时序来自真机 OEM 日志实验，无设备不可判 → 转 L4-13；审计提到的 `markVideoOpened()` 经复核**不存在这个函数**（onCreate 里是 `onVideoDetailStarted()`，实例存活标志本就为 true，无需重做） | A | `VideoDetailHandoffGuardTest`（3 条） | **已关闭(B5，剩余转 L4-13)** |
| ST-26 | ~~结构守卫会被 Gradle 判 UP-TO-DATE / FROM-CACHE 静默跳过~~ | 构建 / CI | 扫源码型测试读的文件**不是** `Test` 任务的声明输入（只有 `testClassesDirs`/`classpath`）。B6 实测：把守卫自己 `mv` 到拼错的目录 → `--tests` 跑出 BUILD SUCCESSFUL（被跳过）；加 `--rerun-tasks` 才 FAILED。**改注释、改测试自身、改任何不进字节码的文件都不会让它重跑** | A | B7 修正严重度并落地：`pr.yml` 用 `setup-gradle@v5` **会开构建缓存**，故 CI 同样存在（仅在"改动不进字节码"时命中，恰是守卫最该拦的那类）；`build.yml` 不跑单测无需动。修法用**单任务粒度的 `--rerun`**（本地实测该开关存在、任务确实执行），不用 `--rerun-tasks` 重跑整张任务图。本地侧只立口径 → FINDINGS W5 | **已关闭(B7)** |
| ST-27 | 本地开发仍可能拿到"缓存绿"（不改默认行为，只立口径） | 本机流程 | 同 ST-26 根因；强制本地每次重跑会拖慢每一次构建，代价不成比例 | — | 看日志确认测试任务是 `> Task :x:testDebugUnitTest` 而非带 `UP-TO-DATE`/`FROM-CACHE`；要确证时 `--rerun` 或删 `build/test-results` | 已记录 |
| ST-24 | ~~Kotlin 不校验「目录 == 包名」~~ | 全仓 | 本会话两次写错目录而全绿 → 已加守卫 | A | `PackageDirectoryConsistencyTest`（3 条；前缀规则容忍 `core/model` 的"扁平包+分层目录"既有约定；**已实测会咬**） | **已关闭(B6)** |
| ST-25 | ~~4 个源文件以 UTF-8 BOM 开头，`^package` 类匹配对其静默失配~~ | `feature/download` `feature/listen` `feature/live` `feature/space` | 已修（B18），实测仍是那 4 个。**只砍首 3 个字节**、不整文件重写（两文件 CRLF、两文件 LF，整文件重写容易顺手改坏行尾与中文——那正是任务书 §2.3 第一条的动机），前后各算 sha256 确认无损。危害不是编译，而是锚定行首的守卫把它当成"没有包声明"，前缀规则永久成立 | A | 新断言 `mainSourcesDoNotStartWithABom`（读首 3 字节而非文本；先断言扫到 >300 个文件）。破坏性验证：加回 BOM → FAILED，还原后 sha256 一致 | **已关闭(B18)** |
| ST-09 | ~~`onValueChangeFinished` 里 `dragFraction!!`~~ | `feature/listen` | 已修（B15），复核**成立且是真崩溃**：`Slider.onValueChangeFinished` 在无障碍 / IME 路径下可以在没有任何 `onValueChange` 时到达，那时它是 null；文件里两处 `!!` 只有 `displayMs` 那处外面有判空，finished 那处没有 → 一触发就是 KotlinNullPointerException。抽成 `internal fun listenSeekTargetMs(fraction, durationMs): Long?` 后两处共用，没拖过就不 seek | A | `ListenSeekTargetTest`（4 条纯 JVM：无拖动 / 时长未知 / 线性映射 / 越界夹紧） | **已关闭(B15)** |
| ST-10 | ~~`ImageSaver` 的 `as HttpURLConnection` 在 `runCatching` 之外~~ | `core/common` | 已修（B15），**严重度经复核下调**：唯一调用方 `PreviewImageGrid` 把 `saveUrl` 整体包在 runCatching 里，所以 CCE 不会崩给用户；真正的用户可见伤害是它把 `err.message` 直接弹进 Toast —— 用户看到一行 `…cannot be cast to java.net.HttpURLConnection`。新增 `openImageConnection`：非 http/https → `IllegalArgumentException("不支持的图片协议")`，解析失败 → "无效的图片地址"（保留 cause），连接建立移进 runCatching，`disconnect()` 改 try/finally | A | `ImageSaverUrlTest`（4 条纯 JVM，断言的是契约而非分支，见 W19） | **已关闭(B15，Toast 文案观感待 L4-18)** |
| ST-11 | ~~下载：DB 行先删、文件后删且丢弃返回值，无孤儿目录 GC~~ | `core/download` | 已修（B16）。产生路径就写在 `delete()` 的 D1 注释里：drain 抢占窗口内 runTask 带着已删实体继续 mkdirs 并写完整个下载，终态 update 对已删行是静默 0 行更新 → 几百 MB 无主目录，而启动恢复只按 DB 驱动 | A | `sweepOrphanTaskDirs` 纯函数（只删"名字=规范 id + 是目录 + DB 无行"的子项，认不出的一律不碰）+ DAO `allIds()` + 启动路径 fail closed 调用；`DownloadOrphanSweepTest` 6 条含接线检查 | **已关闭(B16，真机确认待 L4-19)** |
| ST-12 | ~~`IMFieldsCache$mid` SharedPreferences 文件不随账号删除而回收~~ | `core/im` `core/auth` | 已修（B16）。`AuthStore.removeAccount` 原先只删 `account_$mid` / 用户信息两个 key，那个 xml 永久留在 shared_prefs | A | **验收方式按实测改过**：fake 整个 `android.content.Context` 在本仓不可行（无 Robolectric），与其写"假装成 Context 的巨型桩"（测的是桩不是 Android），改为钉三件真会坏的事：缓存名字面量单一来源 / removeAccount 确实遍历清理器且 @IntoSet 已绑定 / 依赖方向不许 auth→im。真机看文件消失另列 L4-20 | **已关闭(B16，真机确认待 L4-20)** |

### P2（边角错误分支）

| 编号 | 标题 | 模块 | 根因线索 | 级 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|
| ST-13 | ~~`DownloadDao` 是唯一无 `@Transaction` 的 DAO，读-改-写竞态会抛裸 ISE~~ | `core/download` | 已修（B17），**危害经复核升级**：`DownloadViewModel.enqueue` 的调用点在 `viewModelScope.launch` 里**没有 try/catch**，所以那句 `error(...)` 不是文案问题，是一次崩溃。现在插入+冲突回读进同一个 `@Transaction`（SQLite 串行化写事务，中间插不进并发 delete），返回 `DownloadEnqueueOutcome(taskId, created)`；仍不一致时先重试插入、再回读，最后才返回新增的 `Conflict` 变体，界面文案与"已存在"区分 | A | `DownloadEnqueueRaceGuardTest`（3 条：`@Transaction` 在 / 那句 `error` 没回来 / 界面处理了 Conflict）。刻意不写"全仓禁止 error()"——它在不可达分支与前置校验里是正当写法（W18） | **已关闭(B17)** |
| ST-14 | ~~gRPC 编解码丢掉自己写的长度域，畸形帧报 ISE 而非 `BiliApiException`~~ | `infra/network-grpc` | 已修（B17）：按声明长度取载荷，帧过短 / 声明>可用 / 声明压缩却无 `grpc-encoding` 一律 `BiliApiException`。长度域按**无符号**解释 —— 用 Int 拼时最高位为 1 会读成负数，"声明 > 可用"就被负数绕过，坏帧反而通过 | A | `GrpcFrameCodecTest`（9 条纯 JVM：往返、阈值两侧 0..70000、帧头声明值、截断、过短、最高位长度、尾随字节、无头压缩帧、空载荷） | **已关闭(B17)** |
| ST-15 | 单例里的 `CoroutineScope(SupervisorJob())` 无取消路径 | `app/playback` | `CapsuleIndicatorState.kt:43,52`、`PlaybackHostState.kt:44,72`（今日因 `@Singleton` 而安全，改作用域即变泄漏） | A | 记录为设计约束；若 ST-03 改动牵涉则一并补 `close()` | **已关闭(B18，定为设计约束、刻意不加 close())**：Android 没有"进程即将销毁"的可靠回调，close() 得不到任何合法调用时机，只会变成没人调用的死代码并让人误以为已处理。改为在两处 KDoc 写明约束：不许改成 Activity/ViewModel 作用域，新增长驻协程必须容忍活到进程结束 |
| ST-16 | `AppSettings.readResponsePrefix` 里 `response.body ?:` 恒假分支 | `infra/player` | `SingleFileDashMediaSourceFactory.kt:220` 编译警告（OkHttp 5 body 非空） | A | 死分支删除 + 编译警告数不上升 | 待办 |
| ST-17 | 免流/元数据 `BiliMetadataBuilder` 的 type/oid 仍按静态值 | `infra/network-http` | `BiliMetadataBuilder.kt:117` TODO | — | **需网络行为验证，无设备不做**：转 L4/决策产物 | 挂起 |
| ST-18 | eac3 软解缺失 | `core/playback` | `StreamPlaybackSessionImpl.kt:684` TODO | — | 真机解码能力相关，无设备不可判 | 挂起 |
| ST-19 | 翻译 locale 只能英文（`x-bili-locale-bin` 未动态化） | `feature/comment` | `CommentViewModel.kt:312` TODO | — | 属"多了一个能做的事"边界 → **功能冻结令，不做** | 冻结 |
| ST-20 | `DynamicRepository` 接口迁移遗留（OpusDetailReq → DynDetailReq） | `core/dynamic` | `DynamicRepository.kt:81` TODO | — | 换接口=改线上行为，无设备不可验 | 挂起 |
| ST-21 | `DeviceIdentity` 远程 fp 未接 | `infra/crypto` | `DeviceIdentity.kt:220` TODO（注释自评"不知道有啥用"） | — | 无收益且涉凭据 | 冻结 |
| ST-22 | `ImConversationComposer` 输入态未收敛到 `TextFieldState` | `feature/im` | `ImConversationComposer.kt:51` TODO | A | 属状态收敛：需先有可判定的"清理时机"回归测，否则不动 | **挂起(B18 明确不做)**：本仓确实没有能判定 IME/无障碍下输入态清理时机的回归测，且这类差异只能真机观测；它属于"以后少一类 bug"的重构，不在"让已有功能更稳/更快/更漂亮"的可验证范围内。TODO 注释保留 |

---

## B-流畅

| 编号 | 标题 | 模块 | 根因线索 | 级 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|
| PF-01 | OkHttp 全局无 `connectTimeout`/`readTimeout`/`callTimeout` | `infra/network-http` | `NetworkModule.kt:60` 是仓库唯一 `OkHttpClient.Builder()`；`BiliDns.lookup(:186)` 在 `Dns.lookup` 里同步串三次 DoH，`Call.cancel()` 打不断 | **B**（超时值属手感/成功率权衡）| **复核修正**：原写"全局无超时"字面成立但结论不对 —— OkHttp 有默认值（connect/read/write 各 10s，call 无上限），真实风险是"没写"带来的两件事：下一个人顺手补 `callTimeout`（会砍断下载与流式读）+ 升级时默认值漂移无人 review。本批把三个超时**显式写成与默认值相同的数字**（行为零变化）并加 `NetworkTimeoutGuardTest`（4 条：全仓只 1 个 Builder / 三个超时必须显式 / **出现 callTimeout 即红** / 不许设 0 且值在 1..60_000ms）。取值收紧属手感 → L4-21 | **部分完成(B19，数值待 L4-21)** |
| PF-02 | 单文件 DASH 预取窗口固定 91536 字节，索引超出即起播失败 | `infra/player` | `SingleFileDashMediaSourceFactory.kt:198` TODO（"100 小时视频"）；B1 已让解析器不因盒头形态误判，窗口截断仍判 null | **B**（网络行为，需真实索引分布） | 先做探针日志量测真实索引大小分布，再谈扩窗口；不许猜 | 待办（需真机/抓包） |
| PF-03 | 冷启动链路不可测量 | `infra/coldstart` | T3 遗留；`ColdStartClient.kt:82` 的 catch 还吞取消（并入 ST-06） | A（可测性，非新功能） | 新增 `ColdStartTiming`：一行 key=value 的阶段耗时（build/grpc/parse/cache + total，超阈值标 `slow=1`），时钟注入所以能确定性测；取消/异常/空数据三条出口都出日志。`ColdStartTimingTest` 7 条（含接线检查） | **已关闭(B19)** |
| PF-04 | 首屏预热路径 jank（稳态已达标，剩冷缓存 r1~r3 1.0~1.8%） | `feature/home` | 见记忆第二轮结论与 `docs/performance-baseline.md` | B | 需真机 A-B-A ≥5 轮中位，无设备不下结论 | 挂起 |
| PF-05 | 文字选区 `getPathForRange` 在 `drawBehind` 里每次重绘都分配 `Path` | `core/designsystem` | `SelectableText.kt:106-110` | A（每帧分配，属 `ListAllocationStructureTest` 范畴） | 长文本拖动选择的 Path 分配计数守卫（**复核修正**：`getPathForRange` 只有返回新 Path 的重载，javap 查 ui-text 1.11.2/1.12.1 都一样，所以"选区变了分配一次"省不掉；省的是**重绘但选区没变**的那些 —— 原先在 drawBehind 里，滚动/父级动画都会重绘。改成带 key 的 remember） | **已关闭(B20)** |
| PF-06 | 勋章拼图五个 `PolygonShape/StarShape` 在组合体内联构造 | `feature/user` | `UserCollageSection.kt:38-42` + `UserCollageCanvas.kt:147,195` 每次 `createOutline` 分配 `Path`/`Outline.Generic`；现有棘轮正则盖不到 `Path()` | A | `ListAllocationStructureTest` 扩计数字段（含 `Path(`/`Outline.Generic`），棘轮只降不升 | **部分作废 + 换目标完成(B20)**：原描述**已过期** —— 那五个形状早就收在文件级 `UserCollageShapes` 里，真正还内联的是**播放器**那 8 处（VideoScreen 2 + VideoPlayerPane 6），已提到文件级。守卫按 W7 扩了覆盖面（形状内联改按"最外层声明类型"判定而不是按变量名；`Path()`/`Outline.*` 按实测 4 处冻结），共 4 条新断言 |

---

## C-好看

> UI 主线在 `docs/2026-09-25-iOS界面风格升级方案.md` 的阶段框架内推进；
> 每批次只动一个视觉维度。阶段 A/B/C 的**已完成部分**见 PROGRESS B0。

| 编号 | 标题 | 模块 | 根因线索 | 级 | 验收标准 | 状态 |
|---|---|---|---|---|---|---|
| UI-01 | `LocalUiStyle.current == UiStyle.IOS` 散点仍未收口到 0 | designsystem + 11 个 feature 文件 | 方案 §7.2 清单；材质门禁（`GlassEffect.kt:101,211`、`HomeVideoPage.kt:338`）应改读 `GlassConfig` | A | `grep` 计数棘轮（只许减），designsystem 内部之外为 0 | 待办 |
| UI-02 | 6 处内联 `typography.x.copy(...)` 未消 | `core/designsystem` | `DesignTokenLintTest` 棘轮 6 | A | 棘轮 6 → 0 | 待办 |
| UI-03 | 6/10dp 间距档已补，字面量离群值待继续收敛 | `core/designsystem` | `Spacing.kt` 新增档位后的残留字面量；`DesignTokenLintTest` 上限需按实际下调 | A | 棘轮**下调**（写明基线依据） | 待办 |
| UI-04 | 三态（加载/空/错误）未全站统一 | 各 feature | `StateMessageCard` 体系；`AdaptiveMediaGrid` 错误优先已修，其余界面待审 | A | 三态组件覆盖清单 + 结构守卫 | 待办 |
| UI-05 | 三处小触控目标被 `AccessibilityStructureTest` 冻结（D4） | `core/designsystem` | 任务书把"触控 ≥48dp"列为 **A 级可机器判定** | A | 能静态判定就抬到 48dp 并解冻结 | 待办 |
| UI-06 | 玻璃材质层级（Thin/Regular/Thick）与 vibrancy 参数统一 | `core/designsystem/glass` | 方案 §3-D、`LiquidBackdrop.kt:73` 与 `VideoPlayerPane.playerControlGlass` 两处 vibrancy 无单一来源 | **B**（观感） | 只出参数表 + 待真机清单，默认值冻结 | 挂起 |
| UI-07 | 动效 tween → spring 族 | `core/designsystem/theme/Animation.kt` | 方案 §3-D | **B** | 同上，默认值冻结 | 挂起 |
| UI-08 | `materialTypography()` 定义了但无人调用（死代码 or 漏接） | `core/designsystem/theme/Typography.kt` | PROGRESS B0 归档时发现 | A | 查清后删除或接线；不留未消费的真值源 | 待复核 |
| UI-09 | `SecondaryLabelAAVariants` 只被测试引用 | `core/designsystem/theme/iOSColor.kt` | 阶段 B"两版 token 待翻转"的产物；AA 版已翻成本地默认值 | A | 若已无用则删；否则补用途注释 | 待复核 |
| UI-10 | `IosDarkLiftedBackground` 未被引用 | `core/designsystem/theme/iOSColor.kt` | 深色改炭色阶梯后残留 | A | 删除（未使用即删，勿留 `_vars`） | 待复核 |

---

## 已关闭 / 不需再做（防重复劳动）

- 可执行优化方案 §7 全部条目、§9.1 的 B1–B9（PROGRESS B0 已归档提交）。
- `GlobalScope` 全仓零使用；两处 `runBlocking` 均有 `withTimeoutOrNull` 上限（冷启动镜像回退路径）。
- `LiveRepository.kt:119`、`PlaybackCdnSelector.kt:37` 的 `first()`、`ImRepository.kt:412,416` 的 `[0]`
  均已被上游空值守卫 —— **审计误报**，不要修。
- `AuthRepository` 的 `json.getString/getLong` 全在 `runCatchingCancellable` 内 → 不会崩，只成 failure。
