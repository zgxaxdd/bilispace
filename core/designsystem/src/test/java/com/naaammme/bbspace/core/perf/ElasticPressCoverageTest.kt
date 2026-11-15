package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **全局拖拽反馈覆盖率的棘轮**（task-9 D）。
 *
 * ## 为什么必须有这一条
 * 用户第 33 轮原话：「全局拖拽反馈覆盖的太少，我要的是**全面接入**」。
 * 问题的成因不是某处写错，而是**没有一个地方要求"新控件必须接"**：
 * 装饰性反馈天然可省，省一处没人发现，于是从 5 处覆盖逐步退化。到本轮开始时，
 * 全仓只有 5 条挂载（`AppMiuixIconButton` / `AppMiuixActionPrimitives` /
 * `AppMiuixListItem` / `AppNavHost` / `LinkedBottomDock`），而 UI 可点件有数百处。
 *
 * 所以这里钉三件事：
 * 1. **总量棘轮**：文件数 / 调用点数只许升不许降。删一处挂载就红 ——
 *    这与本仓其它棘轮（`DesignTokenLintTest` 的 dp 计数、`FrameBudgetLintTest` 的
 *    反模式计数）同构，只是方向相反：那些数字越小越好，这个越大越好。
 * 2. **原语清单**：任务书 A 列出的每个原语文件都必须**至少一处**挂载。这条抓的是
 *    "某个原语在重构中被换掉、挂载随之消失"—— 总量棘轮在"删一处、另处补一处"
 *    的情况下仍然绿，清单式断言补上这个缺口。
 * 3. **去重机制仍在**：`AppElasticPress.kt` 里的 `foldIn` 必须还在。它保证同一
 *    modifier 链上重复挂载**不叠加**（上游同机制），是"页面层大胆重复挂也不会
 *    双重形变"的结构性前提。有人把它重写成会叠加的版本时，本断言立刻红。
 *
 * ## 为什么是源码扫描而不是 UI 测试
 * 与本仓 `GlassMountRegistryTest` / `FrameBudgetLintTest` 同一惯例：无设备条件下，
 * 能可靠固化的只有**写法**。任何一条真实挂载都必然留下 `.appElasticPress(` 文本，
 * 而"这个控件按下去有没有形变"在 JVM 单测里取不到。
 *
 * ## 扫描口径（与 DesignTokenLintTest 一致，勿简化）
 * - **跳过注释行**：解释"这里为什么挂"的 KDoc 里必然出现 `.appElasticPress(`，
 *   不跳过会让"把理由写清楚"变成计数上升；
 * - **跳过 import 行**：那是声明不是调用；
 * - **排除定义文件本身**：`AppElasticPress.kt` 里有两处 `appElasticPress(` 是
 *   `fun Modifier.xxx()` 的声明与内部委托，不是挂载点；
 * - **按行计数**（不是按文件）：一个文件里给多个按钮各挂一次，是真实的多处覆盖。
 *
 * ## 数值来历（W24：先实测再写死，不凭印象）
 * 本文件里的 [FROZEN_FILES] / [FROZEN_CALLS] 最初是 2026-10-18 task-9 落地后、
 * 2026-10-19 第三十四轮又各用本文件**自己的扫描器**重测过一次；当前基线是后者。
 * 改判据（例如换正则）必须先跑一遍读数，两边对齐后再改。
 *
 * ⚠️ 与交接书里的「274 处 / 92 文件」不一致是**判据不同**，不是覆盖缩水 ——
 * 逐条对账见 [coverageOnlyGrows] 的 KDoc。**校准棘轮只能用它自己的判据**。
 *
 * ## 与 `GlassMountRegistryTest` 的关系
 * 那条测试用**精确行号**钉死了 `AppNavHost` / `LinkedBottomDock` / `HomeScreen` /
 * `VideoPlayerPane` 的玻璃挂载行。本文件只数**数量与文件**、不记行号，因此在这些
 * 文件之外插入任何行、乃至在它们内部改动（只要不删 elastic 挂载）都不会互相干扰。
 */
class ElasticPressCoverageTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    /** 全仓 main 源码（含 app/core/infra/feature 全部模块）。 */
    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private val definitionFile: File by lazy {
        mainSources.firstOrNull { it.name == "AppElasticPress.kt" }
            ?: throw AssertionError(
                "找不到 motion/AppElasticPress.kt（repoRoot=${repoRoot.absolutePath}）——" +
                    "本类所有断言此刻都在假绿。"
            )
    }

    /**
     * 挂载调用：带前导点的 `.appElasticPress(` / `.appSearchElasticPress(`。
     *
     * `(?<!Search)` 让两个正则不会互相重复计数同一个 `appSearchElasticPress` 调用
     * （`appElasticPress` 是它的子串）。定义行 `fun Modifier.appElasticPress(` 前面
     * 没有点，天然不匹配，但 [definitionFile] 仍整体排除，双保险。
     */
    /**
     * 挂载点判据（本文件唯一的"什么算一处挂载"定义）。
     *
     * ## 为什么"行里有 `.appElasticPress(`"不够
     * 函数**声明**行也带前导点：`fun Modifier.appElasticPress(...)` 里那个
     * `Modifier.appElasticPress` 与真实挂载长得一样。第一版判据就是这么写的，
     * 被 [scannerCountsRealMountsAndIgnoresProse] 的正例自检当场抓住（多算 2 处）。
     * 真实扫描里它恰好没出错，只因为定义文件被**按文件名**整体排除了 ——
     * 那是拿另一个补丁盖住判据缺陷，任何新增的 `fun Modifier.xxxElasticPress`
     * 都会让计数虚高。
     *
     * ⇒ 显式排除"声明行"：同一行里出现 `fun Modifier.` 时，那里的 `Modifier.xxxElasticPress`
     * 是声明而不是调用。挂载行则必然是 `modifier = Modifier.appElasticPress(...)`、
     * `modifier.appElasticPress(...)` 或续行的 `.appElasticPress(...)`，
     * 都不会含 `fun Modifier.`。
     */
    private val callRegex = Regex("""\.appSearchElasticPress\(|(?<!Search)\.appElasticPress\(""")

    /** 声明行标记：`fun Modifier.xxxElasticPress(` 的 `Modifier.` 不是挂载。 */
    private val declarationMarker = "fun Modifier."

    /** 这一行算不算一处真实挂载（调用方负责先跳过注释/import 行）。 */
    private fun isMountLine(line: String): Boolean =
        !line.contains(declarationMarker) && callRegex.containsMatchIn(line)

    /** 每个文件的挂载数（不含定义文件，已跳过注释与 import 行）。 */
    private fun mountsPerFile(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (file in mainSources) {
            if (file == definitionFile) continue
            val n = file.readLines().count { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("import ")
                ) {
                    return@count false
                }
                isMountLine(line)
            }
            if (n > 0) {
                result[file.relativeTo(repoRoot).path.replace('\\', '/')] = n
            }
        }
        return result
    }

    // ── 防假绿 ───────────────────────────────────────────────────────────────

    /**
     * 扫描器自检。理由同 `FrameBudgetLintTest.scannerActuallyReadsSources`：
     * 路径推导失效时，下面每条断言都会在"0 处"的漂亮数字上通过。
     */
    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}）。" +
                "少于 200 个说明路径推导失效，本类所有断言此刻都在假绿。",
            mainSources.size > 200
        )
        assertTrue(
            "定义文件 AppElasticPress.kt 找到了，但它里面居然没有 appElasticPress 声明 —— " +
                "本类对它的排除规则（按文件名）已经失效，请同步修改。",
            definitionFile.readText().contains("fun Modifier.appElasticPress(")
        )
    }

    /**
     * 正例自检：把示例源码喂给同一套判据，确认"挂载"能被数到、"注释/import/定义"不会被数到。
     *
     * 这一步不能省 —— 本类的判据里同时有 `(?<!Search)` 前瞻与三种跳过规则，
     * 任何一处写错都会让棘轮在**偏低**的读数上永真。
     */
    @Test
    fun scannerCountsRealMountsAndIgnoresProse() {
        val probe = listOf(
            "        modifier = Modifier.appElasticPress(),",
            "            .appSearchElasticPress(highlightShape = shape),",
            "            .appElasticPress(",
            "    // 这里为什么挂 .appElasticPress() 的说明",
            "     * 同上，`.appElasticPress()` 的 KDoc",
            "import com.naaammme.bbspace.core.designsystem.motion.appElasticPress",
            "fun Modifier.appElasticPress(enabled: Boolean = true): Modifier {",
            "fun Modifier.appSearchElasticPress(enabled: Boolean = true): Modifier = composed {",
        )
        val counted = probe.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                false
            } else {
                isMountLine(line)
            }
        }
        assertEquals(
            "判据应恰好数到 3 处真实挂载（appElasticPress x2 + appSearchElasticPress x1），" +
                "当前数到 $counted 处：注释/import/函数声明必须全部被排除。",
            3,
            counted
        )
    }

    // ── 1. 总量棘轮（只许升不许降）───────────────────────────────────────────

    /**
     * 基线演进（除标注了「交接书 grep 口径」的一行外，每个数字都是当时
     * **用本测试的判据**跑出来的）：
     *
     * | 时间 | 文件 | 挂载 | 说明 |
     * |---|---|---|---|
     * | 本轮开始前 | 5 | 5 | `AppMiuixIconButton` / `AppMiuixActionPrimitives` / `AppMiuixListItem` / `AppNavHost` / `LinkedBottomDock` |
     * | 2026-10-18 task-9 | 55 | 173 | 原语层批量接入 |
     * | 2026-10-18 第二十轮 | 58 | 191 | settings/search/history 等批次 |
     * | 2026-10-19 第三十四轮（**交接书 grep 口径**） | 92 | 274 | 交接时快照，判据不同，见下 |
     * | 2026-10-19 第三十四轮（**本测试判据**） | 89 | 255 | 交接时用本判据重测 |
     * | 2026-10-19 T2 逐点补挂后（**本测试判据**） | **99** | **288** | 补 33 处：裸 M3 按钮 19 + `FilterChip` 4 + 可点 `Card` 7 + 浮条导航 2 + 分段按钮 1；新增 [everyClickableComponentIsCoveredOrJustified] 逐点守卫 |
     *
     * ## ⚠️ 交接书的 274/92 与这里的 255/89 不一致 —— 是**判据不同**，不是覆盖缩水
     *
     * 交接书 §3.2 的读数是
     * `grep -rn 'appElasticPress(\|appSearchElasticPress(' --include=*.kt . | grep -v /build/ | grep -v 'fun Modifier\.'`。
     * 与本测试（[mountsPerFile]）相比，它**多算三样东西**；三者已在同一份工作区上
     * 逐条对账（grep 口径 297 行 / 93 文件 vs 本判据 255 行 / 89 文件，差 42 行 / 4 文件）：
     * 1. **`src/test/` 下的测试源码（38 行 / 3 文件）**：`ElasticPressCoverageTest` 自己
     *    的 KDoc 与 `probe` 样例、`SeamlessAnchorElasticPressGuardTest`、
     *    `GlassMountRegistryTest` —— 它们**引用**这些 API 名，不是挂载点；
     * 2. **注释/KDoc 行（3 行）**：`SearchCapsuleField.kt:47`、`CommentReplyCard.kt:126`、
     *    `AppearanceSettingsScreen.kt:1083`。本仓的修复注释到处在讲 `.appElasticPress()`，
     *    交接书 §4.2 自己就警告过「`grep -c` 会把注释行也算进去」；
     * 3. **定义文件自身的内部委托行（1 行）**：`AppElasticPress.kt` 里
     *    `appSearchElasticPress` 对 `appElasticPress` 的转调，没有前导点，grep 照样计数。
     *
     * ⚠️ 顺带订正一处常见误判：交接书的 grep **已经**带了
     * `grep -v 'fun Modifier\.'`，所以「函数声明行」**不是**这次差异的来源
     * （本仓 main 里同时含 `fun Modifier.` 与 `appElasticPress` 的只有那 1 行，
     * 且两种口径都排除了它）。差异只来自上面三条。
     *
     * 另外 274/92 是**交接时点的快照**：同一个 grep 口径今天读数是 297/93，
     * 多出的行是交接之后新增的挂载。因此不要把 274 与本表的 255 直接相减当作"退化"。
     *
     * **一律以本表的判据为准** —— 棘轮读的就是这套判据。拿 grep 数字当基线会虚高，
     * 而虚高的基线会让"删掉一整片挂载"变成静默通过，这正是棘轮要防的。
     *
     * 上游 BiliPai 同一机制是 121 处 / 37 个文件 —— 本仓 UI 可点件总量更大
     * （多出 CI/CDN/播放诊断等自有页面），因此数量高于上游是合理的，
     * 不是照抄。
     *
     * ## 为什么两个数都要钉
     * 只钉调用点数时，"把 3 个文件的挂载删掉、在 1 个文件里补 5 处"仍然绿，
     * 但覆盖面其实塌了（那 3 个页面的所有控件失去反馈）。文件数单独钉住广度，
     * 调用点数钉住密度。
     */
    @Test
    fun coverageOnlyGrows() {
        val perFile = mountsPerFile()
        val fileCount = perFile.size
        val callCount = perFile.values.sum()
        assertTrue(
            "全局拖拽反馈的覆盖文件数从 $FROZEN_FILES 掉到了 $fileCount。" +
                "本棘轮**只许升不许降**：用户明确要求「全面接入拖拽反馈功能」，" +
                "删掉一处理论上也意味着某个页面的所有可点件同时失去反馈。" +
                "若确实要删（例如该控件被移除），请连同这里的数字一起改小，并说明原因。\n" +
                "当前覆盖：${perFile.entries.sortedBy { it.key }.joinToString { "${it.key}×${it.value}" }}",
            fileCount >= FROZEN_FILES
        )
        assertTrue(
            "全局拖拽反馈的挂载点从 $FROZEN_CALLS 掉到了 $callCount。" +
                "本棘轮**只许升不许降**。新增可点件时请顺手挂 `.appElasticPress()`；" +
                "若是把某处换成了原语出口（例如 IconButton → AppMiuixIconButton），" +
                "调用点数会下降，这属于**面积变大但计数变小**，应当同步调低此值并在报告里说明。",
            callCount >= FROZEN_CALLS
        )
    }

    // ── 2. 原语清单（删一处即红）──────────────────────────────────────────────

    /**
     * 任务书 A 列出的原语文件，每个都必须**至少一处**挂载。
     *
     * ## 为什么清单式断言不能省
     * 总量棘轮是聚合指标：`AppMiuixSwitch` 的挂载被删、同时在别处多挂一个按钮，
     * 总数不变 —— 但"所有开关失去反馈"这个**功能性缺口**完全隐形。
     * 清单把"哪个原语必须挂"从隐含约定变成可失败的断言。
     *
     * ## 为什么是 `>= 1` 而不是 `== 1`
     * `FilledTabRow` 按任务书要求给**选中 Card + 未选中 Box 两处**都挂
     * （两态都是可点件，只挂一处会读作"只有选中的那颗是按钮"）；
     * 其余原语当前恰好 1 处。断言写成"至少一处"是为了不把
     * "该原语内多挂一处合理挂载"判红，但不允许为 0。
     */
    @Test
    fun everyListedPrimitiveHasAtLeastOneMount() {
        val perFile = mountsPerFile()
        val missing = REQUIRED_PRIMITIVES.filter { suffix ->
            perFile.keys.none { it.endsWith(suffix) }
        }
        assertTrue(
            "以下原语文件里已经没有 appElasticPress 挂载了：\n" +
                missing.joinToString("\n") { "  $it" } +
                "\n\n这些是 task-9 A 明确要求的原语出口（一次挂载全仓继承）。" +
                "若是文件被移动/改名，请同步更新本清单；若是挂载被删，请补回去 —— " +
                "删一处等于该原语的所有消费页面同时失去拖拽反馈。",
            missing.isEmpty()
        )
    }

    /**
     * 反向断言：**未列清单的文件挂了也不会红，但清单本身必须都是真实存在的文件**。
     *
     * 这条防的是"清单写的路径已经不存在了、断言却因为 `endsWith` 匹配不到而永远
     * 在 missing 里"——那种情况下上面那条会红得莫名其妙，但更糟的是有人可能
     * 直接把缺失项从清单里删掉来"修好"它。先确认文件都在。
     */
    @Test
    fun everyListedPrimitiveFileExists() {
        val absent = REQUIRED_PRIMITIVES.filter { suffix ->
            mainSources.none { it.path.replace('\\', '/').endsWith(suffix) }
        }
        assertTrue(
            "清单里的原语文件不存在（路径已变）：\n" + absent.joinToString("\n") { "  $it" } +
                "\n请改正清单路径，不要删条目。",
            absent.isEmpty()
        )
    }

    // ── 3. 去重机制仍在 ──────────────────────────────────────────────────────

    /**
     * `foldIn` 去重必须还在。
     *
     * ## 它守的是什么
     * `appElasticPress` 的实现里有：
     * ```
     * if (foldIn(false) { found, element -> found || element === ElasticPressMarker }) return this
     * ```
     * 同一 modifier 链上重复挂载只生效一份。这是**页面层可以大胆重复挂**的前提：
     * 原语已经挂了（例如 `AppMiuixIconButton`），调用方再挂一次也不会双重形变、
     * 不会两个 `pointerInput` 抢同一个手势。
     *
     * ## 为什么这条要在覆盖率测试里
     * 覆盖率棘轮鼓励"到处挂"。如果去重被去掉，这个鼓励立刻变成"到处双重形变"——
     * 两个问题必须成对守卫，否则这个棘轮会**制造**缺陷。
     *
     * 断言的是**三件事同时存在**：`foldIn(`、`ElasticPressMarker`、早退 `return this`。
     * 只查 `foldIn(` 不够 —— 有人可能把判据改成"永不命中"来绕过它（那仍会编译过）。
     */
    @Test
    fun dedupMarkerStillGuardsRepeatedMounts() {
        val body = definitionFile.readText()
        assertTrue(
            "AppElasticPress.kt 里找不到 `foldIn(` —— 重复挂载的去重机制被删了。" +
                "它保证同一 modifier 链上重复挂 `.appElasticPress()` 不叠加，" +
                "也是本覆盖率棘轮允许「页面层大胆重复挂」的前提。",
            body.contains("foldIn(")
        )
        assertTrue(
            "AppElasticPress.kt 里找不到 `ElasticPressMarker` —— 去重标记被改名/删除了。" +
                "若是有意重命名，请同步本断言（不要只是删掉这条测试）。",
            body.contains("ElasticPressMarker")
        )
        assertTrue(
            "AppElasticPress.kt 里找不到去重命中后的早退 `return this`。" +
                "`foldIn` 命中时若不早退，重复挂载仍会叠加 —— 那正是本条要防的。",
            Regex("""foldIn\([^)]*\)[^\n]*\n?\s*return this""").containsMatchIn(body)
        )
        assertTrue(
            "`private object ElasticPressMarker : Modifier.Element` 不见了 —— " +
                "标记必须是 Modifier.Element 才能被 `foldIn` 遍历找到。",
            Regex("""private object ElasticPressMarker\s*:\s*Modifier\.Element""").containsMatchIn(body)
        )
    }

    // ── 4. 逐点覆盖：每个可点件要么挂了反馈，要么在豁免清单里 ────────────────────

    /**
     * 用户第 34 轮 T2 原话：「全局拖拽反馈接入点不足，请**将所有可接入的位置全部接入**，
     * 但不得影响 oppo 的动画」。
     *
     * ## 为什么总量棘轮不够
     * [coverageOnlyGrows] 只保证「总数不降」。新写一个裸 `TextButton(` / 可点 `Card(`
     * 却忘了挂反馈时，总数不变 —— 缺口在聚合指标里完全隐形。本断言把**每一个调用点**
     * 摊开：仓内每个原生 M3 按钮（含 `FilterChip`）与每个**带 `onClick` 的 `Card`**，
     * 其实参里必须出现 `.appElasticPress()` / `.appSearchElasticPress()`，
     * 除非它属于 [ELASTIC_PRESS_EXEMPT_FILES] 里写明了理由的宿主。
     *
     * ## 为什么只扫"按钮 + 可点卡"，不扫 Switch / Slider / Checkbox / RadioButton
     * 这个功能的官方文案是「统一控制**按钮与胶囊**的拖动形变」（见 `AppElasticPress.kt`
     * 头部注释）。开关 / 滑杆 / 勾选框是**自带拖拽语义的原生控件**：`Switch` 的拇指拖动、
     * `Slider` 的轨道拖动都要独占手势，给它们套 `graphicsLayer` 跟手位移会让拇指与轨道
     * 错位。这条边界是判据的一部分，不是遗漏。
     *
     * ## 判据细节（三条都踩过坑）
     * 1. **按调用点、不按行**：`TextButton(` 的实参常跨十几行，`modifier` 在最后一行。
     *    只扫"本行 + 后 12 行"会把 19 处已挂载误判成未挂（本文件第一版就这么错的）。
     * 2. **`onClick` 只在实参**：可点卡的判据不能拿"调用文本里出现过 `onClick =`" ——
     *    卡片的 trailing lambda 里常嵌着别的按钮，那会把普通容器卡误判成可点卡。
     *    这里只取**深度 1**（直接实参）的文本。
     * 3. **豁免清单必须"还活着"**：文件必须在、且里面确实还有未挂载点。否则"加一行豁免"
     *    就能静默吞掉新缺口，条目也会在文件被删/补挂后变成僵尸。
     */
    @Test
    fun everyClickableComponentIsCoveredOrJustified() {
        val uncovered = scanUncoveredSites(rawButtonRegex) +
            scanUncoveredSites(rawClickableCardRegex, requireOnClick = true)
        val unexpected = uncovered.filterNot { it.file in ELASTIC_PRESS_EXEMPT_FILES.keys }
        assertTrue(
            "以下可点件没有接全局拖拽反馈（用户要求「所有可接入的位置全部接入」）：\n" +
                unexpected.joinToString("\n") { "  ${it.file}:${it.line}  ${it.component}" } +
                "\n\n修法：在该调用点的 `modifier` 链尾接 `.appElasticPress()`" +
                "（已有 `modifier` 就继续链下去，不要再写一个 `modifier` 实参）。\n" +
                "确实不能挂（例如 OPPO 无缝锚点宿主）时，才把整个文件加进 " +
                "ELASTIC_PRESS_EXEMPT_FILES 并写明理由。",
            unexpected.isEmpty()
        )
        val missingFiles = ELASTIC_PRESS_EXEMPT_FILES.keys.filter { exemptPath ->
            mainSources.none { it.relativeTo(repoRoot).path.replace('\\', '/') == exemptPath }
        }
        assertTrue(
            "豁免清单里的文件已经不存在了：\n" + missingFiles.joinToString("\n") { "  $it" } +
                "\n请改正路径或删除条目。",
            missingFiles.isEmpty()
        )
        val staleEntries = ELASTIC_PRESS_EXEMPT_FILES.filterKeys { exemptPath ->
            uncovered.none { it.file == exemptPath }
        }
        assertTrue(
            "以下豁免条目已经不再对应任何未挂载点（文件里全挂上了，或调用点被删）：\n" +
                staleEntries.entries.joinToString("\n") { "  ${it.key} —— ${it.value}" } +
                "\n请把该条目从 ELASTIC_PRESS_EXEMPT_FILES 删掉，让通用扫描接管。",
            staleEntries.isEmpty()
        )
    }

    /**
     * [everyClickableComponentIsCoveredOrJustified] 的判据自检。
     *
     * 这一步不能省：判据里有「多行实参配平」「字符串里的括号」「深度 1 才认 `onClick`」
     * 「声明不是调用」四件事，任何一件写错，主断言都会在**偏低或偏高**的读数上永真/永红。
     */
    @Test
    fun clickableScannerHandlesRealCallShapes() {
        val probe = listOf(
            "Button(onClick = onDismiss) { Text(\"取消\") }",
            "TextButton(",
            "    onClick = { onPick(\"a(b)\") },",
            "    modifier = Modifier.appElasticPress()",
            ") {",
            "IconButton(onClick = onBack) {",
            "SegmentedButtonDefaults.itemShape(index = 0, count = 3)",
            "FilledIconButton(onClick = onTab) {",
            "Card(",
            "    onClick = onOpen,",
            "    modifier = Modifier.fillMaxWidth()",
            ") {"
        )
        assertEquals(
            "按钮判据应恰好数到 0/5/7 三处未挂（1-4 是多行已挂载，6 不是按钮调用）：",
            listOf(0, 5, 7),
            scanUncoveredLines(probe, rawButtonRegex)
        )
        assertEquals(
            "可点卡判据应只数到第 8 行（`onClick` 在直接实参里）：",
            listOf(8),
            scanUncoveredLines(probe, rawClickableCardRegex, requireOnClick = true)
        )
        assertEquals(
            "`Card(` 没有 `onClick` 时不是可点件，必须整条跳过：",
            emptyList<Int>(),
            scanUncoveredLines(listOf("Card(modifier = Modifier.fillMaxWidth()) {"), rawClickableCardRegex, requireOnClick = true)
        )
    }

    /** 一个未挂载的可点件调用点。 */
    private data class RawSite(val file: String, val line: Int, val component: String)

    /** 仓内扫描：逐文件走 [scanUncoveredLines]。 */
    private fun scanUncoveredSites(nameRegex: Regex, requireOnClick: Boolean = false): List<RawSite> {
        val result = mutableListOf<RawSite>()
        for (file in mainSources) {
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            val lines = file.readLines()
            for (index in scanUncoveredLines(lines, nameRegex, requireOnClick)) {
                val component = nameRegex.find(lines[index])?.groupValues?.get(1).orEmpty()
                result += RawSite(rel, index + 1, component)
            }
        }
        return result
    }

    /** 判据本体：返回**未挂载**的调用点行下标（0-based）。 */
    private fun scanUncoveredLines(
        lines: List<String>,
        nameRegex: Regex,
        requireOnClick: Boolean = false
    ): List<Int> {
        val result = mutableListOf<Int>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                return@forEachIndexed
            }
            nameRegex.find(line) ?: return@forEachIndexed
            if (requireOnClick &&
                !onClickArgumentRegex.containsMatchIn(callArgumentText(lines, index, onlyTopLevel = true))
            ) {
                return@forEachIndexed
            }
            val args = callArgumentText(lines, index, onlyTopLevel = false)
            if (!args.contains("appElasticPress(") && !args.contains("appSearchElasticPress(")) {
                result += index
            }
        }
        return result
    }

    /**
     * 从 `startIndex` 行起取出调用的实参文本（含括号配平、跳过字符串与行注释）。
     *
     * @param onlyTopLevel true 时只保留**直接实参**那一层（深度 1）的字符 ——
     *   用来判断"这层实参里有没有 `onClick`"，避免把 trailing lambda 里嵌套按钮的
     *   `onClick` 算到外层容器上。
     */
    private fun callArgumentText(lines: List<String>, startIndex: Int, onlyTopLevel: Boolean): String {
        val sb = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var rawString = false
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i]
            var j = 0
            while (j < line.length) {
                val ch = line[j]
                if (rawString) {
                    if (ch == '"' && j + 2 < line.length && line[j + 1] == '"' && line[j + 2] == '"') {
                        rawString = false
                        j += 3
                        continue
                    }
                    j++
                    continue
                }
                if (quote != null) {
                    if (ch == '\\') {
                        j += 2
                        continue
                    }
                    if (ch == quote) quote = null
                    j++
                    continue
                }
                when {
                    ch == '"' && j + 2 < line.length && line[j + 1] == '"' && line[j + 2] == '"' -> {
                        rawString = true
                        j += 3
                        continue
                    }
                    ch == '"' -> {
                        quote = '"'
                        if (depth > 0) sb.append(ch)
                    }
                    ch == '\'' -> {
                        quote = '\''
                        if (depth > 0) sb.append(ch)
                    }
                    ch == '/' && j + 1 < line.length && line[j + 1] == '/' -> break
                    ch == '(' -> {
                        depth++
                        if (depth > 0 && (!onlyTopLevel || depth == 1)) sb.append(ch)
                    }
                    ch == ')' -> {
                        depth--
                        if (depth == 0) return sb.toString()
                        if (!onlyTopLevel || depth == 1) sb.append(ch)
                    }
                    depth > 0 && (!onlyTopLevel || depth == 1) -> sb.append(ch)
                }
                j++
            }
            if (depth > 0 && !onlyTopLevel) sb.append('\n')
            i++
        }
        return sb.toString()
    }

    private companion object {
        /**
         * 2026-10-19 第三十四轮重测（**用本测试自己的判据**跑出来的）。
         * **只许升不许降** —— 见 [coverageOnlyGrows]，以及那里关于
         * "交接书的 274/92 是 grep 口径、不能拿来当基线"的说明。
         */
        const val FROZEN_FILES = 99
        const val FROZEN_CALLS = 288

        /**
         * 原生 M3 按钮调用（含 `FilterChip`）。**必须按行首**匹配 ——
         * 声明行（`private fun TextButton(`）不以组件名开头，天然被排除。
         */
        val rawButtonRegex = Regex(
            """^\s*(FilledIconButton|FilledTonalIconButton|OutlinedIconButton|IconToggleButton|""" +
                """TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|FloatingActionButton|""" +
                """SegmentedButton|IconButton|FilterChip|Button)\s*\("""
        )

        /** 可点卡容器（只认带 `onClick` 实参的，见 [callArgumentText] 的 `onlyTopLevel`）。 */
        val rawClickableCardRegex = Regex("""^\s*(Card|ElevatedCard|OutlinedCard)\s*\(""")

        /** 直接实参里出现 `onClick =` 才算"可点件"。 */
        val onClickArgumentRegex = Regex("""\bonClick\s*=""")

        /** 唯一允许不挂反馈的宿主（每条都必须写清"为什么不是漏了"）。 */
        val ELASTIC_PRESS_EXEMPT_FILES = mapOf(
            "app/src/main/java/com/naaammme/bbspace/playback/capsule/CapsuleIndicator.kt" to
                "OPPO 无缝锚点宿主：小横条本身就是系统过渡的源 View，锚点矩形 = 胶囊几何；" +
                "给它内部按钮挂绘制期形变会让框架取到错误矩形（第 34 轮任务 1 的根因）。" +
                "守卫见 `SeamlessAnchorElasticPressGuardTest`。"
        )

        /** task-9 A 的原语清单（路径后缀，避免依赖绝对路径）。 */
        val REQUIRED_PRIMITIVES = listOf(
            "component/miuix/AppMiuixSwitch.kt",
            "component/miuix/AppMiuixCard.kt",
            "component/CapsuleGroup.kt",
            "component/BiliCardSurface.kt",
            "component/BiliBackButton.kt",
            "component/SearchCapsuleField.kt",
            "component/UpListRow.kt",
            "component/StateMessageCard.kt",
            "component/IOSActionSheet.kt",
            "component/IOSAlertDialog.kt",
            "component/FilledTabRow.kt",
            // 本轮之前就已挂好的三个（列入清单同样有效：删掉同样即红）
            "component/miuix/AppMiuixIconButton.kt",
            "component/miuix/AppMiuixActionPrimitives.kt",
            "component/miuix/AppMiuixListItem.kt",
        )
    }
}
