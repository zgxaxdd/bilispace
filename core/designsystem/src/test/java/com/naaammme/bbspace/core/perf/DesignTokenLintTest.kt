package com.naaammme.bbspace.core.perf

import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计 token 落地程度棘轮（任务书 T5 的"无设备验收"）。
 *
 * ## 这个测试要解决的不是"错"，而是**漂移**
 *
 * 圆角/间距/字重已经收口到 [BiliRadius] / `BiliSpacing` / `iOSTypography`，
 * 但 theme 包外仍有上千个 `N.dp` 字面量。字面量本身不是 bug（一次性尺寸就该写字面量），
 * **高频字面量**才是：同一个 16.dp 出现 175 次意味着"间距语言"其实没有单一改动点,
 * 想统一调一次就要改 175 处，于是要么不改、要么改漏 —— 观感就是"这里 16、那里 14"。
 *
 * 所以本文件做三件事，按可信度从高到低：
 * 1. **行为断言**（[cardAndPlayerRadiiStayEqual]）：token 之间的不变关系，最硬。
 * 2. **结构断言**（[halfRoundedShapesUseHelpers]）：某个具体写法必须存在/不存在。
 * 3. **计数棘轮**（下面的 `*_isFrozen`）：总量与高频值各钉一个上限，只许减。
 *    计数是**趋势指标**而不是正确性证明 —— 它变小说明在收口，变大必须写明理由。
 *
 * ## 为什么不一把全部替换掉
 *
 * `BiliSpacing` 自己的 KDoc 就写着"仅在新代码与 UI 改造中逐步采用，
 * 不要求全局替换既有内联 dp"。1000+ 处盲替换会把语义完全不同的尺寸（内边距、
 * 图标尺寸、分隔线粗细、约束值）统统塞进同一个 token，一旦某个 token 值调整就
 * 连带改掉几十个不相干的视觉尺寸 —— 那是**制造回归**而不是消除回归。
 * 本轮只做语义无歧义的收敛（见 [sheetTopLiteralIsNotDuplicated] 的 7 处），
 * 其余用棘轮钉住现状、禁止扩散。
 */
class DesignTokenLintTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    /**
     * theme 包**外**的 main 源码 —— 即"应当使用 token、而不是写字面量"的范围。
     *
     * theme 包内允许字面量：token 的定义本来就得写字面量，禁了等于逼人绕过本测试。
     */
    private val nonThemeMainSources: List<File> by lazy {
        val sep = File.separator
        val themeDir = "${sep}core${sep}designsystem${sep}src${sep}main${sep}java" +
            "${sep}com${sep}naaammme${sep}bbspace${sep}core${sep}designsystem${sep}theme${sep}"
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${sep}src${sep}main${sep}") }
            .filterNot { it.path.contains("${sep}build${sep}") }
            .filterNot { it.path.contains(themeDir) }
            .toList()
    }

    /**
     * 防假绿（照抄 FrameBudgetLintTest 的设计）。
     *
     * 本类所有棘轮都是 `actual <= limit` 形态：若扫描根目录变了、一个文件都没扫到，
     * `actual` 就是 0，于是**每一条都漂亮地通过**。这比没有测试更糟——它给改动者
     * 发了"token 收口在进步"的假证明。
     */
    @Test
    fun scannerActuallyReadsNonThemeSources() {
        assertTrue(
            "theme 包外的 main 源码只扫到 ${nonThemeMainSources.size} 个文件" +
                "（repoRoot=${repoRoot.absolutePath}）。少于 200 个说明路径推导或 " +
                "themeDir 排除规则已经失真，本类所有棘轮此刻都在假绿。",
            nonThemeMainSources.size > 200
        )
    }

    // ── 1. 行为断言 ──────────────────────────────────────────────────────────

    /**
     * 卡片圆角与播放器圆角**必须相等**，这是刻意的，不是巧合。
     *
     * 视频详情是从列表卡片**无缝 morph** 过去的（OPPO View Seamless）。两端圆角不同
     * 时，动画过程中形状会跳一下 —— 用户看到的就是"打开时四个角抖了一下"。
     * 因此 [BiliRadius.player] 定义成 `= card`。
     *
     * 本断言防的是：有人 review 时觉得"两处都 16dp 是重复，应该各自独立可调"，
     * 于是把它"优化"成两个值 —— 那是直接破坏无缝动画。改这里之前请先确认
     * `docs/2026-09-20-液态玻璃与视频小横条-工作交接书.md` 里的 morph 结论仍成立。
     */
    @Test
    fun cardAndPlayerRadiiStayEqual() {
        assertEquals(
            "BiliRadius.card 与 BiliRadius.player 必须同值：无缝 morph 两端同值才不跳变。" +
                "若确实要解耦，必须先确认无缝动画不再依赖这个等式。",
            BiliRadius.card,
            BiliRadius.player
        )
    }

    // ── 2. 结构断言 ──────────────────────────────────────────────────────────

    /**
     * 弹层顶部圆角不得再以字面量形式散落。
     *
     * 制定本断言时全仓有 **7 个文件**各写了一份
     * `RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)`，而
     * [BiliRadius.sheetTop] 早就存在、注释里还点名说"这就是 sheetTop 语义"却没用它；
     * 7 处注释同时把值写成"20dp"（实际 28dp）。这就是"字面量散落"的真实代价：
     * token 改了，7 处一个都不跟。
     *
     * 顺带记一个查证结论（否则这条替换本身就是一次视觉赌博）：
     * `RoundedCornerShape(topStart=, topEnd=)` 省略下两角时，下两角是 **0dp**，
     * **不会**从顶角继承 —— 用 javap 反查 foundation 1.12.0 的
     * `RoundedCornerShape$default(FFFF, int, Object)` 确认每个省略位都是 `fconst_0`。
     * 因此换成 `BiliRadius.topRounded(...)` 是像素等价的。
     */
    @Test
    fun sheetTopLiteralIsNotDuplicated() {
        val hits = nonThemeMainSources.filter {
            countMatching(it.readLines(), """topStart\s*=\s*28\.dp""") > 0
        }
        assertTrue(
            "theme 包外仍有 ${hits.size} 个文件把弹层圆角写成 `topStart = 28.dp`：" +
                hits.joinToString { it.name } +
                "。请改用 BiliRadius.topRounded(BiliRadius.sheetTop)。",
            hits.isEmpty()
        )
    }

    /**
     * "卡片上下两半分角"必须走配对助手，不许手写四角。
     *
     * 半半分角的正确写法是 [BiliRadius.topRounded] / [BiliRadius.bottomRounded] 这一对。
     * 手写成 `topStart = r, topEnd = r, bottomStart = 0.dp, bottomEnd = 0.dp` 的问题
     * 不是此刻错，而是**依赖默认值的写法**和**四角要写全**这两件事混在一起，
     * 改的人分不清哪个角被漏掉了（见 [sheetTopLiteralIsNotDuplicated] 注释里那个
     * 必须反编译才能确定的问题）。
     */
    @Test
    fun halfRoundedShapesUseHelpers() {
        val capsuleGroup = nonThemeMainSources.firstOrNull { it.name == "CapsuleGroup.kt" }
        assertTrue(
            "找不到 CapsuleGroup.kt —— 本断言会在'没扫到'的状态下假绿，先修扫描路径。",
            capsuleGroup != null
        )
        val lines = capsuleGroup!!.readLines()
        assertTrue(
            "CapsuleGroup 的分组列表上下两半分角应使用 BiliRadius.topRounded/bottomRounded" +
                "（与首页卡片同一套写法）。",
            countMatching(lines, """BiliRadius\.topRounded\(""") >= 1 &&
                countMatching(lines, """BiliRadius\.bottomRounded\(""") >= 1
        )
        val manualCorners = countMatching(lines, """bottomStart\s*=\s*0\.dp""")
        assertTrue(
            "CapsuleGroup 里不该再有手写四角的 `bottomStart = 0.dp`，现在有 $manualCorners 处。" +
                "改用 BiliRadius.topRounded/bottomRounded。",
            manualCorners == 0
        )
    }

    // ── 3. 计数棘轮 ──────────────────────────────────────────────────────────
    //
    // 制定日（2026-09-22）由**本类自己的扫描器**实测（393 个 theme 包外 main 文件、
    // 已跳过注释行与 import 行）。注意这几个数是校准出来的，不是 grep 出来的：
    // grep 会把"16.dp 是 BiliSpacing.lg 的语义位"这类注释也数进去，实测比代码值高约 10%。
    //   dp 字面量合计 961，降序：
    //   16.dp×174   8.dp×149   12.dp×143   6.dp×111   4.dp×94   10.dp×80
    //   2.dp×52     24.dp×33   20.dp×18
    //   sp 字面量合计仅 4 —— 字号早已收口进 typography。
    //
    // 注意 BiliSpacing 只有 4/8/12/16/20/24/32，而 **6.dp(111 次) 和 10.dp(80 次
    // 是高频值却没有 token)** —— 收口进展慢的直接原因之一就是标度缺一档。
    // 补齐标度之前，先把它们的计数钉住，避免继续长。

    @Test
    fun dpLiteralsOutsideTheme_isFrozen() {
        assertRatchet(
            actual = sumOf("""\b\d+(\.\d+)?\.dp\b"""),
            // 上限 961 → 962 的变更记录（2026-09-22，T6）：
            // 新增的是 VideoDetailSummary 操作栏的 `defaultMinSize(minHeight = 48.dp)`。
            // 48dp 是 Material 触控目标**规范常量**而不是设计标度里的一个档位
            // （BiliSpacing 没有 48，也不该有：把它做成"间距 token"会在哪天调间距时
            // 顺手改掉无障碍下限），所以刻意保留字面量、把上限 +1。
            // 该改动本身不改变任何像素（那列原本按内容就有约 56dp）。
            // 上限 962 → 980 的变更记录（2026-09-26，iOS 阶段 C）：
            // 新增 IOSActionSheet / IOSAlertDialog / BiliSheetGrabber 三个自包含组件，
            // 其中的 36×5dp、2.5dp、270dp、44dp、56dp、14dp、0.5dp 等是
            // HIG / compose-cupertino 的**规格常量**（组件固有尺寸，同 48dp 的处理逻辑：
            // 不做成间距 token，避免日后调间距时顺手改掉组件规格），刻意保留字面量；
            // 语义性间距（8/12/16/20dp）已全部收敛进 BiliSpacing。规格常量清单已被
            // IOSComponentStructureTest 钉住，改动会红。
            // 上限 980 → 981（2026-09-26，iOS 深色增强）：+1 为纯黑深色下卡片
            // 发丝描边的 0.5.dp 线宽（BiliCardSurface.biliDarkHairlineBorder）——
            // 描边粗细是规格常量，同 48dp 触控下限的处理逻辑，不做间距 token。
            // 上限 981 → 980（2026-09-26，B20 形状收口）：`VideoPlayerPane` 里那处
            // 非全屏播放窗口的 `RoundedCornerShape(16.dp)` 提到了文件级常量，字面量少一处。
            // 这是"棘轮该有的方向性"——数字变小就把上限一起降下来，否则它拦不住未来的增长。
            // 上限 980 → 981（2026-09-29，刷视频会话覆盖层）：+1 为互动栏圆盘的
            // `1.dp` 白色发丝描边（VideoPlayerPane.SwipeRailAction 的玻璃观感圆盘）——
            // 描边粗细是规格常量，同 0.5.dp 发丝线的处理逻辑，不做间距 token。
            // 同批净变化备注：评论悬浮卡片 v3 玻璃描边的 1.dp 已随实底重构删除（-1），
            // 但它发生在同两次棘轮运行之间，未计入本上限。
            // 上限 981 → 960（2026-10-01，阶段三 U1 卡片密度单源）：净 −21 处。
            // 首页信息流卡、排行页卡与 `AdaptiveMediaGrid` 的全站默认值原本各自内联
            // 同一批 8/6/10/12/4 dp（三处注释都点名过"两处不同步就会首页贴边、排行页留白"，
            // 也就是**漂移已经发生过**）。现在它们引用 `BiliCardDensity` 的语义常量，
            // 数值仍全部指回 `BiliSpacing` 档位（该对象本身零 dp 字面量，
            // 见 CardDensitySingleSourceTest）。逐像素无变化，收的是"改动点数量"。
            // 上限 960 → 957（2026-10-03，PT-11 收口；实测 actual = 957）：
            // 移植批次（PT-06/08/10）的 UI 新增一度让本值升到 962（+5 处：推荐来源设置页
            // 的 16/8/8/12/4 dp），**当批即按 token 纪律全部改回 `BiliSpacing.lg/xs/sm/md`**，
            // 回落 957；顺手把此前残留的 3 处余量收掉，棘轮恢复"贴着实测值"的状态。
            // 读法照 W24：把 assertRatchet 临时改成恒失败，从失败消息里读 actual，再还原。
            limit = 957,
            what = "theme 包外的 `N.dp` 字面量",
            why = "同一语义的尺寸散在各处就没有单一改动点。要降它就把语义明确的" +
                "重复值收敛进 BiliRadius/BiliSpacing；一次性尺寸（图标、分隔线粗细、" +
                "约束）本来就该写字面量，不必为了数字好看硬凑 token。"
        )
    }

    /** 最高频的那个值单独钉：16.dp 出现 175 次，它就是"间距语言"本体。 */
    @Test
    fun sixteenDpLiterals_isFrozen() {
        assertRatchet(
            actual = sumOf("""\b16\.dp\b"""),
            // 上限 174 → 176（2026-09-26，iOS 阶段 C）：+2 为 IOSActionSheet /
            // IOSAlertDialog 内 ActionRow/按钮区的固有内边距（56dp 行高规格的一部分），
            // 语义上属于组件规格而非可调间距，同上条记录处理。
            limit = 176,
            what = "`16.dp` 字面量",
            why = "16.dp 是 BiliSpacing.lg 的语义位。新增 16.dp 前先问一句：" +
                "这里是间距/内边距（该用 token），还是某个组件的固有尺寸（该写字面量）？"
        )
    }

    @Test
    fun twelveDpLiterals_isFrozen() {
        // **上限 143 → 146 的变更记录（2026-09-22，T4 UI 收口）**：
        // 一次性 +3，全部来自同一处有意的版式调整 —— 把信息流的行距与页边距
        // 从"能塞就塞"的 6/4dp 改成有呼吸感的 10/12dp（AdaptiveMediaGrid 的默认值
        // 与 HomeVideoPage 的显式 contentPadding）。这是**故意增加**的 12.dp 用量，
        // 不是漂移。理由与真机截图对比记录在 BiliRadius.card 与 AdaptiveMediaGrid 的注释里。
        // **146 → 148（2026-09-26，iOS 阶段 C）**：+2 为 IOSActionSheet 标题/说明
        // 卡片的 padding 规格（对照 compose-cupertino ActionSheetTitlePaddingValues）。
        // **148 → 149（2026-09-29，排行卡同步推荐 UI）**：+1 为 RankScreen 网格
        // contentPadding bottom 从 4.dp 改成 12.dp（与推荐页同口径，见 10.dp 档的记录）。
        // **149 → 144（2026-10-01，阶段三 U1）**：−5 为排行页角标/页边与首页、栅格默认值
        // 的 12.dp 收进 BiliCardDensity（badgeHorizontal / pageBottom / rowSpacing）。
        assertRatchet(actual = sumOf("""\b12\.dp\b"""), limit = 144, what = "`12.dp` 字面量", why = "同上，对应 BiliSpacing.md。")
    }

    @Test
    fun eightDpLiterals_isFrozen() {
        // **149 → 150（2026-09-26，iOS 阶段 C）**：+1 为 IOSAlertDialog 卡片底部
        // padding 规格（对照 compose-cupertino ActionSheetWindowInsets 的 bottom 8dp）。
        // **150 → 145（2026-10-01，阶段三 U1）**：−5 为卡信息区左右内边距收进
        // BiliCardDensity.infoHorizontal（首页 1 处 + 排行 3 处 + 其它 1 处）。
        assertRatchet(actual = sumOf("""\b8\.dp\b"""), limit = 145, what = "`8.dp` 字面量", why = "同上，对应 BiliSpacing.sm。")
    }

    /**
     * 6.dp / 10.dp 两档：token 早在 2026-09-25 就补齐了（`BiliSpacing.xsPlus` / `.smPlus`），
     * 所以这里守的不再是"没有标度"，而是"调用点还没收口"。
     */
    @Test
    fun sixAndTenDpLiterals_isFrozen() {
        // 6.dp 反而降了（栅格列距 6 → 10），上限保持不动，这就是棘轮该有的方向性。
        // **111 → 104（2026-10-01，阶段三 U1）**：−7 为卡信息区上下内边距/角标内边距
        // 收进 BiliCardDensity.infoVertical / badgeVertical（排行 3 处 + 首页 1 处 + 其余 3 处）。
        assertRatchet(actual = sumOf("""\b6\.dp\b"""), limit = 104, what = "`6.dp` 字面量", why = "对应 BiliSpacing.xsPlus；标度已补齐，剩的是调用点收口。")
        // **上限 80 → 85 的变更记录（2026-09-22，T4 UI 收口）**：+5 全部来自那次
        // 版式调整（列距与屏幕边距改用 10dp）。仍是"没有 token 的高频值"，
        // 等 T4.1 的语义归类结论出来再决定要不要给 10 补一档 BiliSpacing。
        // **85 → 87（2026-09-29，排行卡同步推荐 UI）**：+2 为 RankScreen 的网格
        // contentPadding start/end 从 4.dp 改成 10.dp——推荐页注释里点名过
        // "两处不同步就会出现首页贴边、排行页留白这种不一致"，页面边距必须同口径。
        // **87 → 80（2026-10-01，阶段三 U1）**：−7。那"两处不同步"的隐患已由
        // BiliCardDensity.pageHorizontal / gridSpacing 收成一个定义点（首页、排行页、
        // 栅格默认值三处同源），字面量随之减少。
        assertRatchet(actual = sumOf("""\b10\.dp\b"""), limit = 80, what = "`10.dp` 字面量", why = "对应 BiliSpacing.smPlus；同上，语义位已有 token。")
    }

    /**
     * 字号字面量。
     *
     * 制定日实测只有 4 处，说明字号早就基本收口进 typography 了 —— 这条钉在 4
     * 是**防回潮**（往 Text 上直接写 `fontSize = 13.sp` 比引 typography 容易得多，
     * 所以它一定会被人顺手做）。
     */
    @Test
    fun spLiteralsOutsideTheme_isFrozen() {
        assertRatchet(
            actual = sumOf("""\b\d+(\.\d+)?\.sp\b"""),
            limit = 4,
            what = "theme 包外的 `N.sp` 字面量",
            why = "字号走 iOSTypography/MaterialTheme.typography。直接写 sp 会绕开" +
                "字重、行高、字体族这一整套配套值，改主题时不会跟着变。"
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun sumOf(regex: String): Int = nonThemeMainSources.sumOf { countMatching(it.readLines(), regex) }

    /**
     * 统计匹配 [regex] 的行数，**跳过注释行与 import 行**
     * （理由见 FrameBudgetLintTest：解释"为什么这么写"的注释不该让计数上升，
     * 而本类的注释里必然会出现 `16.dp`、`28.dp` 这些词）。
     */
    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@count false
            if (trimmed.startsWith("import ")) return@count false
            re.containsMatchIn(line)
        }
    }

    private fun assertRatchet(actual: Int, limit: Int, what: String, why: String) {
        assertTrue(
            "「$what」当前 $actual 处，超过冻结上限 $limit 处。\n" +
                "原因：$why\n" +
                "要新增请连同上限一起调大并写明理由（这个动作是刻意做得显眼的）；" +
                "数字变小了请把上限一起降下来，棘轮才有方向性。",
            actual <= limit
        )
    }
}
