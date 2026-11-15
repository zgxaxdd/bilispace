package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃挂载点登记表（AP-01(a)）：把守卫从"源码调用点计数"扩到"挂载点显式登记"。
 *
 * ## 为什么需要它（既有守卫的系统性盲区）
 *
 * 既有玻璃守卫数的是**源码调用点**：tick 计数口径是 `[Gg]lassRefresher.frame`
 * （GlassRecordChainStructureTest），GlassDrawChainStructureTest 只查 3 个工厂的
 * remember 顺序——它们都数不到**运行期实例数**。而玻璃的真实成本来自实例数：
 * `HomeVideoPage` 在 iOS + LIQUID 档下每张可见卡各起一条
 * `layerBackdrop + 2×drawBackdrop` 链，源码调用点数量不变、实例数随卡片数膨胀，
 * 既有棘轮永远绿。
 *
 * 本测试在无设备条件下钉住下一层不变式：**源码里每一个玻璃挂载点都必须出现在
 * 下方登记表里**（文件 + 行号 + surfaceType + 门控说明）。新增挂载点 = 新增运行期
 * 玻璃面 = 必须先在登记表里写明它是什么、由谁消费、有没有门控——这个动作被刻意
 * 做得显眼。登记表与实际调用点**双向**校验：
 * - 源码出现登记表之外的挂载点 → 红（新增未登记）；
 * - 登记表条目在源码里找不到 → 红（代码删了表没删，表就是死的）。
 *
 * 行号也会被校验：挂载点在文件内移动会红，失败消息会打印实际行号，照抄进登记表即可。
 * 这让"任何玻璃改动都必须有意识地过一遍登记表"成为机器强制，而不是口头约定。
 *
 * ## 纯 JVM
 * 不依赖设备、不依赖 Compose 运行时。运行时实例数的定量收益见 AP-T6（待真机）。
 * 注意 W22：多任务命令行下 `--rerun` 不生效，验证本守卫时必须单模块单任务调用。
 */
class GlassMountRegistryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    /** 四类玻璃挂载 API 的调用形态（W20：匹配调用本身，不是名字）。 */
    private val kinds: List<Pair<String, Regex>> = listOf(
        "glassEffect" to Regex("""\.glassEffect\("""),
        "layerBackdrop" to Regex("""\.layerBackdrop\("""),
        "drawBackdrop" to Regex("""\.drawBackdrop\("""),
        "hazeSource" to Regex("""\.hazeSource\("""),
    )

    /**
     * **登记表**（AP-01(a)）。唯一权威清单；行号为登记日实测。
     *
     * 维护规则：
     * - 新增玻璃挂载点 → 必须先在这里登记（surfaceType + 门控），否则红；
     * - 删除/移动挂载点 → 同步删/改条目，否则红；
     * - **只许登记真实需要的东西**。想加第 12 个挂载点前，先回答：它由谁消费？
     *   有没有门控？能不能复用既有源？（AP-26：每卡玻璃链就是这么长出来的。）
     *
     * 行号漂移记录（W27：形态/位置变了必须同批改守卫并写明理由）：
     * - **2026-10-14（播放页液态玻璃批次）**：`VideoPlayerPane.kt` 的 `drawBackdrop` 415→426、
     *   `layerBackdrop` 813→824。原因是同一文件里新增了拖动滑块放大（`thumbScale` 状态与
     *   `ThumbDragScale` 常量，共 11 行），两处挂载点整体下移。**挂载点本身没有增删**，
     *   门控条件一字未改 —— 漂移纯属行位移，故只改行号。
     * - **同日第二处**：`drawBackdrop` 426→443、`layerBackdrop` 824→842。原因是给
     *   `playerControlGlass` 加上"高光边档位按控件尺寸自适应"（Big/Middle/Small 三档，
     *   17 行）—— 这段代码位于 `drawBackdrop` **调用之前**，故同样只是整体下移。
     *   门控与采样语义仍未改动。
     * - **2026-10-16（播放页 UI 现代化「Edge 边缘」批次）**：`drawBackdrop` 443→459、
     *   `layerBackdrop` 842→858（同批两次调整后的终值：先做结构性拆分与去底，随后按
     *   可读性复核把 `CtrlBtn`/`PlayerIconButton` 的底盘彻底删掉、常态字提亮到 0.92）。
     *   原因是同批做了：控件玻璃胶囊底换成扁平 scrim 再到**彻底无底**（真·去容器化）、
     *   中央播放键减重（72→64dp / 删描边）、进度条从控制条搬出成常驻贴底细线
     *   （`PlayerCtrlBarHost` 拆成「控制条 + 进度线」两层）。新增/改写的行位于两处挂载点
     *   **之前** ⇒ 整体下移。**挂载点本身没有增删、门控一字未改**（`playerControlGlass`
     *   函数体与 `videoBackdrop` 的创建条件都原样保留，只是消费它的调用点少了几处），
     *   故只改行号。
     * - **2026-10-16（「滑动/运行卡顿」性能修复批次）**：`drawBackdrop` 459→460、
     *   `layerBackdrop` 858→859。原因是同批做了两件位于两处挂载点**之前**的事：
     *   ① `RowScope` import（新增 1 行，供时间文本叶子用 `weight`）；
     *   ② 新增 `resolveDurationMs` / `RowScope.PlayerTimeTextLeaf` / `PlayerProgressLeaf`
     *   三个函数与 `PlayerCtrlBarHost` 的瘦身（把 500ms 的 `playbackProgress` 订阅从宿主
     *   下沉到两个最小叶子，进度更新不再让整条控制条重组）。
     *   **挂载点本身没有增删、门控一字未改**（`playerControlGlass` 函数体与 `videoBackdrop`
     *   的创建条件都原样保留），漂移纯属行位移，故只改行号。
     * - **2026-10-16（iOS-2「渐变 + 分组胶囊」阶段 A）**：`drawBackdrop` 460→474、
     *   `layerBackdrop` 859→873（各 +14）。原因是 `PlayerSliderInactiveTrack` 的 KDoc
     *   按本次可读性结论**扩写**（写明 0.22/0.335 都不达标、0.42 才过非文本 3:1 门槛）、
     *   以及控件层新增分组胶囊的落点调整 —— 这段全部位于两处挂载点**之前**，故整体下移。
     *
     *   ⚠️ **规格卡 §5.3 原本建议"路径 1"（把新常量放文件后段 ⇒ 挂载点零漂移）**，
     *   本次**只做到一半**：新增的 4 个 iOS-2 颜色常量与 `PlayerCtrlRowHeight`
     *   **确实放在挂载点之后**（未造成漂移），但 `PlayerSliderInactiveTrack` 的 **KDoc 扩写
     *   无法后移** —— 它就在常量定义处（挂载点之前）⇒ 漂移不可避免，故走路径 2。
     *   **挂载点本身仍无增删、门控一字未改**（`playerControlGlass` 函数体与 `videoBackdrop`
     *   的创建条件都原样保留），漂移纯属行位移。
     * - **2026-10-16（iOS-2「渐变 + 分组胶囊」阶段 B）**：`drawBackdrop` 474→495、
     *   `layerBackdrop` 873→894（**各 +21**）。
     *
     *   **可证的推断**：两处挂载点**同增 21**，说明这 21 行**全部落在第一处挂载点
     *   `drawBackdrop` 之前**（若两处之间有新增，`layerBackdrop` 会比 `drawBackdrop`
     *   多漂）。据此可排除任何位于挂载点**之后**的改动 —— 例如 `PlayerCtrlBarScrimBrush`
     *   （`:1867`）与 `PlayerSliderShadow` 的删除（`:3081`）**都在两者之下**，
     *   物理上不可能造成上行漂移。
     *
     *   挂载点之前的本批次改动即那 21 行的来源：六档渐变的中间停靠常量及其 KDoc
     *   （`PlayerOverlayScrimMid` / `UpperMid` / `LowerMid` / `Base`，`:216-237`）
     *   与 `PlayerOverlayScrimBottomEnd` 的 KDoc 扩写（`:203-214`）等。
     *   ⚠️ 这 21 行**未逐项归因**（本批改动与前面几轮的工作区改动混在同一段 diff 里，
     *   无中间快照可切分）⇒ 本条只登记**实测位移 +21** 与上述位置约束，
     *   不主张逐项来源。
     *
     *   **订正（2026-10-17）**：本条最初把"渐变画笔从 `PlayerCtrlBar` 移到宿主"
     *   与"删除 `PlayerSliderShadow`"列为漂移原因 —— **两者都在挂载点之下，成因不成立**，
     *   系 @核查员 复核指出，已按挂载点位次改正。
     *
     *   **挂载点本身没有增删、门控一字未改**（`playerControlGlass` 函数体与 `videoBackdrop`
     *   的创建条件都原样保留），漂移纯属行位移，故只改行号。
     * - **2026-10-17（「无模糊语言」方向二 · 第一批：停靠 + 去胶囊）**：`drawBackdrop` 495→**498**、
     *   `layerBackdrop` 894→**897**（**各 +3**）。
     *
     *   **实测来源（本批可逐项归因，与前两批不同）**，四笔全在**第一处挂载点之前**：
     *   ① 删 `PlayerOverlayScrimLowerMid` / `PlayerOverlayScrimBase` 两个常量及其 KDoc：**−5 行**；
     *   ② `PlayerOverlayScrimBottomEnd` 的 KDoc 改写（理由由"胶囊填充达标"换成
     *      "去胶囊后轨道靠平坦段达标"，0.82→0.88）：**+7 行**；
     *   ③ `PlayerOverlayScrimMid` 的 KDoc 改写（去掉"六档 / 0.58/0.68/0.76 / 胶囊上沿 3.44:1"
     *      等已失效描述，换成平坦段与轨道带口径）：**+3 行**；
     *   ④ `PlayerSliderInactiveTrack` 的 KDoc 改写（去掉"胶囊底+0.335 数学解"那段，
     *      换成"轨道坐在 0.88 平坦段上 ⇒ 白 0.42 = 4.01:1"）：**−2 行**。
     *   合计 **−5 +7 +3 −2 = +3**，与实测 495→498 / 894→897 **逐笔吻合**。
     *
     *   ⚠️ 本批**同时删掉了进度行的黑 45% 胶囊底**（`PlayerProgressDockScrim`）并把渐变
     *   平坦段起点由 `0.644f` 上移到 `0.36f` —— 但这两件都在**两处挂载点之下**
     *   （胶囊底在 `:2624+`、停靠在 `:1871+`），**不参与**本次位移。
     *   `playerControlGlass` 函数体与 `videoBackdrop` 创建条件仍**一字未改**。
     * - **同批第二笔（「无模糊语言」方向二 · 第二批：滑块 + 中央播放钮）**：
     *   `drawBackdrop` 498→**491**、`layerBackdrop` 897→**890**（**各 −7**）。
     *
     *   **实测来源**，三笔全在第一处挂载点**之前**：
     *   ① 删 `PlayerPlayButtonScrim`（中央钮黑影底盘，随"白环"取代玻璃盘而失去消费者）：**−3 行**；
     *   ② 删 `PlayerSliderThumbScrim`（滑块底盘染色，随两个绘制节点合并为一个实心白圆而失去消费者）：**−3 行**；
     *   ③ 删已失效的 `Stroke` import（白环改实心圆后 `Stroke(` 归零）：**−1 行**。
     *   合计 **−3 −3 −1 = −7**，与实测 498→491 / 897→890 **逐笔吻合**。
     *
     *   ⚠️ 同批的滑块两节点合并（`:3135-3178`）与中央播放钮改白环（`:2490+`）都在
     *   **两处挂载点之下**，**不参与**位移；`playerControlGlass` 仍保留一个消费者
     *   （整条轨道玻璃 `:3069`）与函数本体，故 `GlassDrawChainStructureTest` 不受影响。
     * - **同日第三笔（2026-10-17 下午：遮罩平坦段起点 `0.36f` → `0.50f`，"底部一整块死黑"）**：
     *   `drawBackdrop` 491→**499**、`layerBackdrop` 890→**898**（**各 +8**）。
     *
     *   **实测来源**，两笔全在第一处挂载点**之前**，且**两笔都是 KDoc 改写**：
     *   ① `PlayerOverlayScrimBottomEnd` 的 KDoc 重写 —— 补"起点推到 0.50 后轨道离开平坦段、
     *      α 0.7725/0.7426 ⇒ 白 42% 3.42/3.21:1"以及两条边界（不能收浅 / 不能推过 0.54）：**+6 行**；
     *   ② `PlayerOverlayScrimMid` 的 KDoc 改写 —— "轨道**必须**落在平坦段内"这条前提**已不成立**，
     *      改成"落在 0.24→0.50 爬坡上"，并补白 45% 两态数：**+2 行**。
     *   合计 **+6 +2 = +8**，与实测 491→499 / 890→898 **逐笔吻合**。
     *
     *   ⚠️ 本批**真正的源码改动只有一处**：停靠行 `0.36f` → `0.50f`（现在 `:1885`）——
     *   它在**两处挂载点之下**，不参与位移；位移**全部**来自上面两段注释。
     *   **又一次印证 W61**：触发 W27 的是"改动位于挂载点之上"这一**位置**事实，
     *   与改的是代码还是注释**无关**。`playerControlGlass` 函数体与 `videoBackdrop`
     *   创建条件仍一字未改。
     * - **同日第三笔·补（2026-10-17 下午：补齐三处漏改的 KDoc）**：`drawBackdrop` 499→**504**、
     *   `layerBackdrop` 898→**903**（**各 +5**）。
     *
     *   **来由**：构建手指出"停靠数改了但 KDoc 没全同步"。他给的行号**是错的**
     *   （`:3242-3244` 是层高 KDoc 的开头、`:2662-2665` 是 `PlayerCtrlBar(` 调用点；
     *   全文件唯一的 "0.50（现状）" 在 `:1867` 的**新旧对照表**里，语义正确）——
     *   **但方向对，确实漏了三处**，三处都还写着"轨道落在 0.36 起的 0.88 平坦段内 / 4.01 / 4.40"：
     *   ③ `PlayerSliderInactiveTrack` 的 KDoc（`:285` 区间）：改成"轨道在 0.24→0.50 爬坡上、
     *      α 0.7725/0.7426、白 42% 3.42/3.21"，并补"起点与平台值是串联旋钮"（平台降到 0.72
     *      会把轨道打到 **2.75/2.67:1**）；
     *   ④ "进度条尺寸规范"段注释（`:310` 区间）：改成"压在控制层渐变上但**不在平坦段内**"；
     *   ⑤ `PlayerCtrlOptionDim` KDoc 里的 `平坦段自 f=0.36 起` → `f=0.50`：**±0 行**。
     *   三处均在第一处挂载点**之前**，合计实测 **+5**。
     *
     *   **W63 第二次实证**：改了数没改注释，注释就从"说明"变成"谎言"，而**没有任何
     *   编译期检查会拦住它** —— 这三处全靠人读出来（第一处靠构建手点名）。
     * - **同日第四笔（2026-10-17 下午：`d-ios10` 浮层语言统一表 · 五项归并）**：
     *   `drawBackdrop` 504→**510**、`layerBackdrop` 903→**909**（**各 +6**）。
     *
     *   **实测来源**，单一笔、在第一处挂载点**之前**：`PlayerPillShape` 的 KDoc 重写
     *   （它承载**文字** ⇒ 圆角由 R1 全圆改为 R2 `BiliRadius.playerControl`；并把"不与
     *   `PlayerControlShape` 合并"的**解耦意图**写进注释）：**+6 行**。
     *
     *   其余各项**改值不改行**（`PlayerPillScrim` 0.30→0.38、`PlayerPillTextDim` 0.68→0.60、
     *   `PlayerSecondaryText` 0.90→0.92），`PlayerPillChip` 的内边距 `xsPlus`→`sm` 在 `:3459`、
     *   位于挂载点**之后** —— 都不参与位移，与实测 +6 吻合。
     *
     *   ⚠️ `d-ios10` §5 第 3 项（`PlayerPillBorder` 白 0.16→0.18）**未落地**：该常量的唯一
     *   消费者是 `:3118` 的「**禁用已播轨**」颜色，**不是任何描边**（描边 2026-10-16 已删）
     *   —— 该条目基于错误的消费者，照落会改错东西。
     * - **同日第五笔（2026-10-17 夜：现代化重绘 · 控制行三级层级）**：
     *   `drawBackdrop` 510→**514**、`layerBackdrop` 909→**913**（**各 +4**）。
     *
     *   **实测来源**，单一笔、在第一处挂载点**之前**：文件头新增 4 行图标 import
     *   （`Icons.Outlined.Fullscreen/FullscreenExit`、`Icons.Rounded.Pause/PlayArrow`，
     *   供控制行"主操作实心 + 贴角线性"两档图标用）：**+4 行**。与实测逐笔吻合。
     *
     *   本批**其余改动全部位于两处挂载点之后**（控制行组合、`CtrlBtn`/`PlayerSegmentGroup`/
     *   `PlayerIconButton`、按压助手、拖动气泡、时间文本降级），不参与位移 ——
     *   这也是把它们集中放在文件后段常量区的既有纪律（见 `PlayerCtrlPressOverlay` 上方注释）。
     * - **同日第六笔（2026-10-17 夜：首页信息流卡片排版现代化）**：`HomeVideoPage.kt` 的
     *   `layerBackdrop` 765→**758**（**−7**）、`drawBackdrop` 940→**934**（**−6**）。
     *
     *   **实测来源**（同一批 UI 改动，三处都在第一处挂载点之前）：① 卡片标题从
     *   `bodyLarge + copy(Medium)` 改为 `iOSTypography.titleSmall` 出口（多一行 import、
     *   少两行内联样式）；② `FeedCardSkeleton` 删掉真实卡片并不存在的第三行占位
     *   （骨架比内容高约 24dp，数据到达时会整块缩一次）；③ 若干 `N.dp` 收口成
     *   `BiliCardDensity`/`BiliSpacing`。第二处挂载点相对第一处再 −1（−6 与 −7 的差）。
     *   计数棘轮（`DesignTokenLintTest`）因此**净下降**，不是被放宽。
     * - **同日第七笔（2026-10-18 凌晨：iOS 底栏滑动指示器 + 横向拖动切 Tab）**：
     *   `AppNavHost.kt` 的 `hazeSource` 828→**835**（**+7**）。
     *
     *   **实测来源**（单一笔、在挂载点**之前**）：`navigationContent` 的 iOS 分支从
     *   "每个 Tab 各自画一层选中底色"改为"**单一滑动指示器 + `dampedHorizontalDrag`**"，
     *   该分支位于 `hazeSource` 调用**之前**（同一个 `LinkedBottomDock(...)` 参数列表里
     *   的靠前参数），新增的注释、动效状态与 `offset{}/graphicsLayer{}` 读取共 +7 行。
     *   **挂载点本身没有增删、门控 `hazeGlassOn` 一字未改**，玻璃链不受影响。
     *   这同时是"按位置漂移"的又一例：改的是底栏导航的**表现层**，与玻璃无关。
     * - **同日第八笔（2026-10-18 上午：清未使用 import）**：`AppNavHost.kt` 的
     *   `hazeSource` 835→**829**（**−6**）。
     *
     *   **实测来源**：审计指出该文件有 6 个未使用 import（`Spring`/`Color`/
     *   `SeamlessTransitionState`/`VideoSrc`/`VideoTargetTool`/`mutableIntStateOf`），
     *   删除后文件头少了 6 行 ⇒ 挂载点整体上移。**挂载点无增删、门控一字未改**
     *   （`hazeGlassOn` 原样），与前七笔同性质。
     * - **同日第九笔（2026-10-18 下午：底栏拖拽重写）**：`AppNavHost.kt` 的
     *   `hazeSource` 829→**830**（**+1**）。
     *
     *   **实测来源**：底栏拖拽修复新增 1 行 `import kotlinx.coroutines.launch`
     *   （跟手时 `indicatorX.snapTo(...)` 是 suspend，必须在协程里调用）。
     *   挂载点无增删、门控 `hazeGlassOn` 一字未改，纯行位移。
     *
     * - **同日第十笔（2026-10-18 傍晚：第十八轮 S2/S4/S5 三批改动）**：
     *   `AppNavHost` 831（+1）、`LinkedBottomDock` 玻璃面 421→**430**（+9）、
     *   `HomeScreen` 三处 246/247/459 → **284/285/519**（+38/+38/+60）。
     *
     *   **实测来源与归因**（三处各自独立，都不改变玻璃实例数）：
     *   1. `AppNavHost` **+1**：底栏导航格新增 `.appElasticPress()`（全局拖拽反馈）
     *      与配套 8 行注释 —— 挂载点在**其后**，但 import 区多 1 行 `appElasticPress`
     *      ⇒ 净 +1。
     *   2. `LinkedBottomDock` **+9**：`DockGlassSurface` 的 KDoc 增 3 行（外壳精修说明）、
     *      搜索位新增 `.appSearchElasticPress()` + 5 行注释、import 1 行 ⇒ 净 +9。
     *      **挂载点本身（`glassEffect` 调用形态、`glassOn` 门控）逐字未改**。
     *   3. `HomeScreen` **+38/+38/+60**：这是本笔唯一**语义性**变化 ——
     *      S5 给顶栏玻璃加了一层运行期门控 `homeTopBarGlassEnabled()`
     *      （只在 `GlassMaterial.LIQUID` 时挂）。因此：
     *      - `hazeSource` / `layerBackdrop` 两处各自 +38（函数 KDoc 22 行 + 调用点
     *        注释 8 行 + `homeGlassOn` 多一次与运算）；
     *      - `glassEffect` 单独 +60（除上面 38 外，`HomeTopBar` 内部 `topGlassOn`
     *        的判定也要加同一门控并补注释）。
     *      **实例数没有增加**：门控只会让运行期**更少**挂载（FROSTED 档不再挂）。
     *      登记表仍逐点登记，因为"代码里存在这个挂载点"这一事实没变。
     *
     * - **同日第十一笔（2026-10-18 深夜：第 33 轮真机反馈 · 四批改动）**：
     *   ① `HomeScreen` 三条**整块删除**（移除，不是漂移）：`hazeSource` 284、
     *      `layerBackdrop` 285、`glassEffect` 545 全部从登记表移除；
     *   ② `VideoPlayerPane` 两处行号下移：`drawBackdrop` 561 → **521**（−40）、
     *      `layerBackdrop` 960 → **920**（−40）；其余四条（`AppNavHost` 831、
     *      `LinkedBottomDock` 430、`ChromeBackdropSource` 61、`LiquidBackdrop` 67）**未动**。
     *
     *   **① 的语义**：用户第 33 轮原话「顶栏被修坏了，内容文字可以继续上滑隐藏而模糊
     *   顶栏无法隐藏，请你重新设计顶栏的所有ui，要求需要减少性能消耗，减少顶栏区域」。
     *   顶栏改成纯色 chrome（`background(background, topBarShape)`），那份私有玻璃源
     *   （HazeState + layerBackdrop + GlassSourceRefresher 逐帧全屏离屏录制）**没有任何
     *   消费者了** ⇒ 整块删除。这是本笔唯一**减少玻璃实例**的改动（−3 个挂载点、−1 份
     *   逐帧全屏录制）。`GlassRecordChainStructureTest` 同批翻转为反向断言
     *   （HomeScreen 里 hazeSource/layerBackdrop/GlassSourceRefresher 必须全为 0）。
     *   ⚠️ 教训：上一轮我加的 `homeTopBarGlassEnabled()`（只在 LIQUID 挂）对用户**恒真**
     *   —— 他的设备就是 `ui_style=IOS + glass_material=LIQUID`。"收紧门控"在这种组合下
     *   省不到任何东西，不能当作"省性能"交付。
     *
     *   **② 的归因**：两处**同减 40 行** ⇒ 这 40 行全部落在第一处挂载点
     *   (`drawBackdrop`) **之前**（若两处之间有新增，`layerBackdrop` 会少减）。
     *   实测来源：`VideoPlayerPane.kt` 顶部常量区的三条整层遮罩画笔
     *   （`PlayerTopScrimBrush` / `PlayerCtrlBarScrimBrush` / `SwipeBottomScrimBrush`
     *   及其大段 KDoc）被删除 —— 用户同轮第 2 条「视频播放页还是有一层遮罩请全部去除」。
     *   取而代之的是两个新常量 `PlayerChipScrim`(黑 0.58) 与 `PlayerInfoTextShadow`，
     *   净效果 −40 行。**挂载点本身无增删、门控一字未改**（`playerControlGlass` 函数体与
     *   `videoBackdrop` 的创建条件都原样保留），漂移纯属行位移。
     *
     * - **2026-10-19（第 34 轮 T2：全局拖拽反馈逐点补挂）**：`VideoPlayerPane` 两处
     *   **各 +1**：`drawBackdrop` 521 → **522**、`layerBackdrop` 920 → **921**。
     *   原因是该文件新增了一行
     *   `import com.naaammme.bbspace.core.designsystem.motion.appElasticPress`
     *   —— 给「失败重试」按钮挂 `.appElasticPress()`（用户第 34 轮第 2 条：
     *   「将所有可接入的位置全部接入」）。import 在两处挂载点**之前** ⇒ 同 +1。
     *   **挂载点本身无增删、门控一字未改**，漂移纯属行位移，故只改行号。
     *
     * - **2026-10-19（第 35 轮：控制层唯一化 + 安全区）**：两处行号再次漂移，
     *   `drawBackdrop` 522 → **513**（−9）、`layerBackdrop` 921 → **927**（+6）。方向相反，
     *   因为改动分布在该文件两处挂载点的上下两侧：
     *   · 顶部（两处之前）：删除 `import android.os.BatteryManager`、删除自绘时钟/电量
     *     （`readPlayerTopMetaText` + `topMetaText` 状态与渲染，真机横向叠字 P0-2 的源头），
     *     新增 `sessionChromeVisible` / `onSessionChromeRequest` 两个参数与受控 `showCtrl`
     *     （P0-1 控制层唯一化）+ `statusBarsPadding` import ⇒ 净 **−9 行**；
     *   · 中部（两处之间）：顶栏与底部控制层各自 `.then(if (isFull) …Padding() else Modifier)`
     *     （P0-2 安全区）+ 注释 ⇒ 净 **+6 行**，只影响 `layerBackdrop`。
     *   **挂载点函数体、门控条件一字未改**（`playerControlGlass` 与 `videoBackdrop`
     *   的创建条件原样保留），这次漂移同样只是行位移，不是新增玻璃挂载。
     */
    private val registry: List<GlassMount> = listOf(
        GlassMount(
            "app/src/main/java/com/naaammme/bbspace/navigation/AppNavHost.kt", "hazeSource", 831,
            surface = "根 haze 源（Crossfade 全屏录制）",
            gating = "hazeGlassOn = glassOn && !liquidGlassOn（LIQUID 档互斥，GlassRecordChainStructureTest 钉死）"
        ),
        GlassMount(
            "app/src/main/java/com/naaammme/bbspace/playback/capsule/LinkedBottomDock.kt", "glassEffect", 430,
            surface = "Dock 玻璃面（BOTTOM_BAR，预算值来源见 AP-27）",
            gating = "glassOn（材质≠OFF）；消费端 glassFrameRefresh 重采样"
        ),
        GlassMount(
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/glass/ChromeBackdropSource.kt",
            "layerBackdrop", 61,
            surface = "根背板录制源（Dock/胶囊/播放控件的采样来源）",
            gating = "无门控（Dock 与胶囊在顶栏滚出后仍可见、仍采样，GlassRecordChainStructureTest 有反向断言）"
        ),
        // ⚠️ **HomeScreen 的三条挂载已于 2026-10-18 第二十轮整块删除**（不是漂移，是移除）：
        //   - `hazeSource`（原 284）、`layerBackdrop`（原 285）—— 首页私有玻璃源；
        //   - `glassEffect`（原 545）—— 顶栏玻璃面（HEADER）。
        // 原因：用户第 33 轮原话「顶栏被修坏了，内容文字可以继续上滑隐藏而模糊顶栏
        // 无法隐藏，请你重新设计顶栏的所有ui，要求需要减少性能消耗，减少顶栏区域」。
        // 处置：顶栏改成**纯色 chrome**（`background(background, topBarShape)`），
        // 那份私有源（HazeState + layerBackdrop + GlassSourceRefresher 逐帧全屏离屏录制）
        // 失去全部消费者 ⇒ 整块删掉，而不是继续收紧门控。
        // 教训：上一轮的 `homeTopBarGlassEnabled()`（只在 LIQUID 挂）对用户**恒真**
        // —— 他的设备就是 ui_style=IOS + glass_material=LIQUID。门控在这种组合下
        // 省不到任何东西，"收紧门控"不能当作"省性能"交付。
        // `GlassRecordChainStructureTest` 已同批翻转为反向断言（HomeScreen 里
        // hazeSource/layerBackdrop/GlassSourceRefresher 必须全部为 0）。
        // 想加回来之前请先回答：顶栏用什么采样？那份 1272×2800 的逐帧录制由谁消费？
        // ⚠️ **HomeVideoPage 的两条挂载已于 2026-10-18 下午删除**（不是漂移，是真正的移除）：
        //   - `layerBackdrop`（原 758）：每卡封面包裹层，N 卡 = N 份录制；
        //   - `drawBackdrop`（原 934）：`glassBadgeSurface` 里的角标采样链。
        // 原因：用户真机反馈"减少首页模糊效果的使用面积"，而**每卡一份背板录制**是首页
        // 滚动路径上最大的一块非必要开销（默认单列每滚动帧 ≈2.3 Mpx 录制），且新卡进入
        // 视口时要首次构建 per-card 玻璃链 —— 这是 `worst=132ms` 掉帧尖峰最可疑的来源。
        // 处置：角标统一走**原本 MATERIAL 档就有的非玻璃分支**（黑 62% + 白字），
        // iOS+LIQUID 也收口到它；没有新造第三种样式，也没有"为了省性能而变丑"的额外改动。
        // ⇒ `HomeVideoPage.kt` 现在**没有任何玻璃挂载点**（四类扫描均 0 命中）。
        // 想加回来之前请先回答：它由谁消费？面积多大？有没有门控？能不能复用既有源？
        GlassMount(
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt",
            "drawBackdrop", 513,
            surface = "playerControlGlass 工厂（播放控件采样）",
            gating = "!enabled || backdrop==null || material==OFF 早退；videoGlassTick 驱动采样" +
                "（R2 起按绝对时间节流到约 30Hz，见 VideoGlassTickPolicy）；" +
                "AP-25 已接 LocalGlassGuard + resolveGlassBudget（降级档早退为纯底色）"
        ),
        GlassMount(
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt",
            "layerBackdrop", 927,
            surface = "播放页视频层背板（录制源）",
            gating = "videoGlassTick 驱动重录（AP-25：守卫降级时 tick 循环同停；R2 起约 30Hz 而不是逐帧）；" +
                "省电模式（SurfaceView）下整条录制链不挂载 —— 画面是独立图层，采不到也无消费者" +
                "（模糊已按 effectiveControlsBlur 强制关，且 `rememberLayerBackdrop()` 本身也条件化为 null）"
        ),
        GlassMount(
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/glass/LiquidBackdrop.kt",
            "drawBackdrop", 67,
            surface = "liquidGlassSurface 工厂（顶栏/Dock 共用玻璃链）",
            gating = "由调用方守卫（glassOn / homeLiquidOn）决定是否挂载"
        ),
    )

    private data class GlassMount(
        val file: String,
        val kind: String,
        val line: Int,
        val surface: String,
        val gating: String
    )

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效，本类断言此刻全在假绿。",
            mainSources.size > 100
        )
    }

    /**
     * 核心断言：实际挂载点集合 == 登记表集合（双向、含行号）。
     */
    @Test
    fun everyGlassMountIsRegistered() {
        val actual = scanActualMounts()
        val registered = registry
            .groupBy { it.file to it.kind }
            .mapValues { (_, v) -> v.map { it.line }.sorted() }
        assertEquals(
            buildString {
                appendLine("玻璃挂载点与登记表不一致（双向校验）。")
                val missing = actual.keys - registered.keys
                val stale = registered.keys - actual.keys
                if (missing.isNotEmpty()) {
                    appendLine("—— 以下调用点未登记（新增玻璃面必须先登记 surfaceType + 门控）：")
                    missing.forEach { key ->
                        appendLine("    ${key.first} [${key.second}] 行 ${actual[key]}")
                    }
                }
                if (stale.isNotEmpty()) {
                    appendLine("—— 以下登记条目在源码里已找不到（代码删了表也要删）：")
                    stale.forEach { key ->
                        appendLine("    ${key.first} [${key.second}] 登记行 ${registered[key]}")
                    }
                }
                val moved = (actual.keys intersect registered.keys).filter { actual[it] != registered[it] }
                if (moved.isNotEmpty()) {
                    appendLine("—— 以下挂载点行号漂移（照抄实际行号进登记表）：")
                    moved.forEach { key ->
                        appendLine("    ${key.first} [${key.second}] 登记 ${registered[key]} 实际 ${actual[key]}")
                    }
                }
            },
            actual, registered
        )
    }

    /**
     * W21 自检：合成正反例证明扫描器真的能抓到四类调用，并且跳过注释/import/定义行。
     * 正则一旦失效（比如改了调用形态），这条先红，而不是让登记表断言变成永真。
     */
    @Test
    fun scannerCatchesCallSitesAndSkipsDeclarations() {
        val positives = listOf(
            "        .glassEffect(" to "glassEffect",
            "    val x = Modifier.layerBackdrop(b)" to "layerBackdrop",
            "        Modifier.drawBackdrop(" to "drawBackdrop",
            "                .then(if (on) Modifier.hazeSource(s) else Modifier)" to "hazeSource"
        )
        positives.forEach { (line, kind) ->
            val re = regexOf(kind)
            assertTrue("自检失败：$kind 的正则抓不到调用形态「$line」", isCallLine(line, re))
        }
        val negatives = listOf(
            "fun Modifier.glassEffect(",                            // 定义行（W20：定义不是调用）
            "// 上面那行 .drawBackdrop( 是注释",                     // 注释行
            "import dev.chrisbanes.haze.hazeSource",                 // import 行
            "* 引用 [glassEffect] 的 KDoc 行"                        // KDoc 行（无调用括号形态）
        )
        negatives.forEach { line ->
            kinds.forEach { (_, re) ->
                assertTrue(
                    "自检失败：本应跳过的行被当成调用：「$line」命中 ${re.pattern}",
                    !isCallLine(line, re)
                )
            }
        }
    }

    // ── 扫描器 ─────────────────────────────────────────────────────────────

    private fun regexOf(kind: String): Regex = kinds.first { it.first == kind }.second

    /** 行级判定：非注释、非 import、非 `fun` 定义行，且正则命中。 */
    private fun isCallLine(line: String, re: Regex): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        if (trimmed.startsWith("import ")) return false
        if (trimmed.startsWith("fun ")) return false
        return re.containsMatchIn(line)
    }

    /** 返回「relPath -> kind -> 已排序行号列表」。 */
    private fun scanActualMounts(): Map<Pair<String, String>, List<Int>> {
        val result = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        for (file in mainSources) {
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                for ((kind, re) in kinds) {
                    if (isCallLine(line, re)) {
                        result.getOrPut(rel to kind) { mutableListOf() }.add(index + 1)
                    }
                }
            }
        }
        return result.mapValues { (_, v) -> v.sorted() }
    }
}
