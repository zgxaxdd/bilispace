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
     */
    private val registry: List<GlassMount> = listOf(
        GlassMount(
            "app/src/main/java/com/naaammme/bbspace/navigation/AppNavHost.kt", "hazeSource", 828,
            surface = "根 haze 源（Crossfade 全屏录制）",
            gating = "hazeGlassOn = glassOn && !liquidGlassOn（LIQUID 档互斥，GlassRecordChainStructureTest 钉死）"
        ),
        GlassMount(
            "app/src/main/java/com/naaammme/bbspace/playback/capsule/LinkedBottomDock.kt", "glassEffect", 421,
            surface = "Dock 玻璃面（BOTTOM_BAR，预算值来源见 AP-27）",
            gating = "glassOn（材质≠OFF）；消费端 glassFrameRefresh 重采样"
        ),
        GlassMount(
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/glass/ChromeBackdropSource.kt",
            "layerBackdrop", 61,
            surface = "根背板录制源（Dock/胶囊/播放控件的采样来源）",
            gating = "无门控（Dock 与胶囊在顶栏滚出后仍可见、仍采样，GlassRecordChainStructureTest 有反向断言）"
        ),
        GlassMount(
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/HomeScreen.kt", "hazeSource", 246,
            surface = "首页私有 haze 源",
            gating = "homeHazeOn = homeGlassOn && !homeLiquidOn（与根源互斥）"
        ),
        GlassMount(
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/HomeScreen.kt", "layerBackdrop", 247,
            surface = "首页私有 LIQUID 源",
            gating = "homeLiquidOn；tick 门控 topBarOffsetPx < topBarHeight（顶栏滚出即停）"
        ),
        GlassMount(
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/HomeScreen.kt", "glassEffect", 459,
            surface = "首页顶栏玻璃面（HEADER）",
            gating = "isIOS && topGlassOn；消费端 glassFrameRefresh（FL-06 起带可见性谓词）"
        ),
        GlassMount(
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/video/HomeVideoPage.kt",
            "layerBackdrop", 755,
            surface = "每卡角标背板（录制源，N 卡 = N 份）",
            gating = "badgeGlassOn 前置（iOS+LIQUID 才创建；默认 MATERIAL 下为 null，见 coverBackdrop 注释）"
        ),
        GlassMount(
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/video/HomeVideoPage.kt",
            "drawBackdrop", 930,
            surface = "glassBadgeSurface 工厂（角标采样，每卡最多 2 角标）",
            gating = "badgeGlassOn 前置；remember 住整条链。" +
                "**AP-26 ② 合并评估结论：两个角标必须两次采样** —— 左下（时长）/右下（播放量）" +
                "同 shape（CoverBadgeShape）同背板，但位置不同；miuix 的 drawBackdrop 只作用于" +
                "节点自身 bounds 的那一个圆角矩形，合并成一个跨两者的矩形会把中间的封面区域" +
                "一起采样（观感错误）。除非改成『单节点自绘两个圆角矩形』的定制绘制（miuix " +
                "modifier 不支持），否则两次采样是结构必需 —— 只登记不合并，方案列入待真机/后续" +
                "评估（AP-T6）。"
        ),
        GlassMount(
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt",
            "drawBackdrop", 412,
            surface = "playerControlGlass 工厂（播放控件采样）",
            gating = "!enabled || backdrop==null || material==OFF 早退；videoGlassTick 逐帧；" +
                "AP-25 已接 LocalGlassGuard + resolveGlassBudget（降级档早退为纯底色）"
        ),
        GlassMount(
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt",
            "layerBackdrop", 780,
            surface = "播放页视频层背板（录制源）",
            gating = "videoGlassTick 驱动重录（AP-25：守卫降级时 tick 循环同停）；" +
                "省电模式（SurfaceView）下整条录制链不挂载 —— 画面是独立图层，采不到也无消费者" +
                "（模糊已按 effectiveControlsBlur 强制关）"
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
