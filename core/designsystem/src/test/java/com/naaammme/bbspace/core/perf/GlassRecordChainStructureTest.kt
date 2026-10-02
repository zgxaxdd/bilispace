package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃录制链的结构断言（任务书 T1 的"无设备验收"）。
 *
 * ## 背景：为什么"每帧录几遍全屏"是个架构级问题
 *
 * 玻璃要采样"自己身后的内容"，就得有人把那部分内容**录进一个离屏层**。
 * 本项目里有两套录制器：haze 的 `hazeSource` 与 miuix 的 `layerBackdrop`
 * （后者内部就是 `contentLayer.record { drawContent() }` + `drawLayer`）。
 * 它们在 1272×2800 的屏上各录一遍，就是每帧两遍约 3.5M 像素的离屏录制——
 * 而且这个成本**只在绘制阶段显现**，组合期看不出来，profile 里也容易被归到
 * "Issue Draw Commands" 一类的笼统桶里。
 *
 * ## 本测试固定的是什么
 *
 * 不是"只剩一处 tick"（那还没做到，见 [glassTickSites_currentlyTwo]），
 * 而是**已经做到的那条不变式**：同一个节点不得同时挂 haze 源与背板源。
 * 这条一旦被人"顺手简化"掉（比如把 `hazeGlassOn` 又写回 `glassOn`），
 * 默认 LIQUID 档下就会重新出现"每帧白录一整屏"，而且**没有任何报错**、
 * 只是掉帧。所以它必须有一个会响亮失败的测试。
 */
class GlassRecordChainStructureTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile

    private val appNavHost: List<String> get() = sourceOf("navigation${FS}AppNavHost.kt")
    private val homeScreen: List<String> get() = sourceOf("home${FS}HomeScreen.kt")

    private val FS: String get() = File.separator

    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效，本类断言此刻全在假绿。",
            mainSources.size > 100
        )
    }

    /**
     * **核心不变式**：haze 源与 miuix 背板源必须互斥地挂载。
     *
     * 依据（不是猜测，是读出来的控制流）：`GlassEffect.kt` 的 LIQUID 分支在拿到
     * 背板后 `return this.liquidGlassSurface(...)` **提前返回**，走不到 haze 那段。
     * 所以 LIQUID 档下 `hazeSource` 录的内容没有任何消费者，是纯粹的一整屏白录。
     *
     * 若有人把守卫写回 `glassOn` / `homeGlassOn`，本测试失败。
     */
    @Test
    fun hazeAndBackdropSourcesAreMutuallyExclusive() {
        // AppNavHost（根，影响四个 Tab）
        assertTrue(
            "AppNavHost 必须有 `val hazeGlassOn = glassOn && !liquidGlassOn` 这个互斥守卫，" +
                "并在挂 hazeSource 时用它。写回 glassOn 等于在默认 LIQUID 档下每帧多录一整屏。",
            countMatching(appNavHost, """val\s+hazeGlassOn\s*=\s*glassOn\s*&&\s*!liquidGlassOn""") == 1 &&
                countMatching(appNavHost, """hazeGlassOn\)\s*Modifier\.hazeSource\(""") == 1
        )
        // HomeScreen（首页顶栏那份私有源）
        assertTrue(
            "HomeScreen 必须有 `val homeHazeOn = homeGlassOn && !homeLiquidOn` 并用它挂 hazeSource。" +
                "首页整块屏幕本来就已被根 Crossfade 录了一遍，这里再录第二遍时" +
                "两套录制器叠加，等于同一帧两遍全屏离屏。",
            countMatching(homeScreen, """val\s+homeHazeOn\s*=\s*homeGlassOn\s*&&\s*!homeLiquidOn""") == 1 &&
                countMatching(homeScreen, """homeHazeOn\)\s*Modifier\.hazeSource\(""") == 1
        )
        // 反向保险：不许再出现"用非互斥守卫挂 hazeSource"的写法
        val sloppy = mainSources.filter { file ->
            countMatching(file.readLines(), """(if\s*\(\s*(glassOn|homeGlassOn)\s*\)\s*Modifier\.hazeSource)""") > 0
        }
        assertTrue(
            "以下文件又用**没有排除 LIQUID** 的守卫挂了 hazeSource：" +
                sloppy.joinToString { it.name } + "。请改用 hazeGlassOn / homeHazeOn。",
            sloppy.isEmpty()
        )
    }

    /**
     * 顶栏完全滚出屏幕后，首页那份私有源没有消费者，tick 必须停。
     *
     * 为什么这一条值得单独钉：滚动时顶栏**本来就是隐藏的**（这是常态而非例外），
     * 所以门控砍掉的不是边角情况，而是滚动路径上大部分时间的第二份全屏重录。
     */
    @Test
    fun homeGlassTickIsGatedOnBarVisibility() {
        assertTrue(
            "HomeScreen 构造 GlassSourceRefresher 时必须传入门控（顶栏完全滚出后不再 tick）。" +
                "GlassSourceRefresher 的默认门控恒为 true，漏传就等于每滚动帧都重录一整屏。",
            countMatching(homeScreen, """GlassSourceRefresher\s*\{""") == 1 &&
                countMatching(homeScreen, """topBarOffsetPx\s*<\s*topBarHeightState\.value""") == 1
        )
        // 根节点**不该**被门控：Dock/胶囊在顶栏隐藏时仍然可见、仍在采样。
        assertTrue(
            "AppNavHost 的玻璃 tick 不应加门控——Dock 与胶囊在首页顶栏滚出后依然可见，" +
                "它们才是这份背板的消费者。",
            countMatching(appNavHost, """GlassSourceRefresher\s*\{""") == 0
        )
    }

    /**
     * 源侧 tick 点的数量。**当前 2，目标是 1，但本轮到不了 1**。
     *
     * 上限只许往下调。做到 1 处需要把各 Tab 的悬浮顶栏从 Crossfade 的 hazeSource
     * 子树里**提出来**（提到与 Dock 同级），否则顶栏会采样到含自身的背板 → 自采样
     * 鬼影，那正是当初建私有源的原因。那是架构级改动、且必须真机走查玻璃观感，
     * 本轮无设备，已列入交付报告的"待设备验证"项。
     *
     * 计数用 `[Gg]lassRefresher\.frame` 而不是 `.frame`：消费端 helper
     * （GlassRefresher.kt 里的 `refresher.frame`）读的是同一个 tick，但它是
     * **重采样**观察、不是源侧重录，混进来会让这个数字失去意义。
     *
     * **AP-33 复核（2026-10-02）：上限 2 保持不变，书面理由** —— FL-05（源侧 2→1：
     * 把各 Tab 悬浮顶栏提为与 Dock 同级、删首页私有源）本轮**未执行**：它是架构级改动，
     * 且自采样鬼影必须真机走查（HomeScreen 记录过两次踩坑）。实测仍 2 处，故维持。
     * 另：AP-25 给播放控件链补上守卫/预算后，**消费端**的无效重采样已由可见性谓词与
     * 预算收紧覆盖，源侧那份的收益待 FL-05 立项后一并验证。
     */
    @Test
    fun glassTickSites_currentlyTwo() {
        val sites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@mapIndexedNotNull null
                if (!Regex("""[Gg]lassRefresher\.frame""").containsMatchIn(line)) return@mapIndexedNotNull null
                "${file.name}:${index + 1}"
            }
        }
        assertTrue(
            "源侧玻璃 tick 读取点有 ${sites.size} 处（$sites），超过冻结上限 2 处。" +
                "每多一处就是每滚动帧多一遍全屏离屏重录。要新增必须先说明为什么不能" +
                "复用已有的那一份；降到 1 的路径见本方法注释。",
            sites.size <= 2
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError(
                "结构断言找不到 `$suffix`。本类断言全是\"某文件里必须有某写法\"，" +
                    "文件读不到时会被误当成通过——必须先修扫描路径。"
            )
        return file.readLines()
    }

    /** 跳过注释行与 import 行（理由见 FrameBudgetLintTest：注释不该让计数变化）。 */
    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@count false
            if (trimmed.startsWith("import ")) return@count false
            re.containsMatchIn(line)
        }
    }
}
