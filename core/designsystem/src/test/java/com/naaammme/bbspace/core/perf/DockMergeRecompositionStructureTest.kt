package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dock 合并动画期间"整树重组"的结构断言（任务书 T2 的"无设备验收"）。
 *
 * ## 这条链原本为什么贵
 *
 * 合并动画每帧都在改 Dock 的**布局高度**（`LinkedDockPolicy`：
 * `height = top + barHeight`，`top = (barHeight + verticalGap) * (1 - merge)`）。
 * 而旧代码把这个随帧变化的尺寸直接写进了两个组合状态：
 *
 * ```
 * onGloballyPositioned { onOcclusionChanged(coords.size.height / window) }   // 每帧写
 *   → bottomNavOcclusionRatio 变化
 *   → AppNavHost 在 `LocalBottomNavOcclusionRatio provides bottomNavOcclusionRatio`
 *     处**组合期**读它
 *   → 280ms 动画期间每帧重组整个 AppNavHost scope
 * ```
 *
 * `onSizeChanged { hiddenDistancePx = it.height }` + 组合期读它的
 * `animateFloatAsState(targetValue = ...)` 是同一条病的第二个入口。
 *
 * 修法是两件事：**测展开态的固定值**（不再每帧写），以及**把读取从组合期挪走**
 * （provides 一个 provider，点击瞬间才读）。这两件事都能用文本检查钉死，
 * 所以本测试不需要设备。
 */
class DockMergeRecompositionStructureTest {

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

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效，本类断言此刻全在假绿。",
            mainSources.size > 100
        )
    }

    /** 遮挡比必须以 provider 形式下发，而不是在组合期交出数值。 */
    @Test
    fun occlusionRatioIsDeliveredAsProvider() {
        val listCoverRatio = sourceOf("seamless${FS}ListCoverRatio.kt")
        assertTrue(
            "LocalBottomNavOcclusionRatio 的类型必须是 `() -> Float`。" +
                "下发 Float 会迫使提供端在组合期读那个状态，" +
                "于是遮挡比每被写一次就重组一次整棵主框架树。",
            countMatching(listCoverRatio, """compositionLocalOf<\(\)\s*->\s*Float>""") == 1
        )

        val navHost = sourceOf("navigation${FS}AppNavHost.kt")
        assertTrue(
            "AppNavHost 必须以 `provides { bottomNavOcclusionRatio }` 下发 provider。" +
                "写回 `provides bottomNavOcclusionRatio` 就是在组合期读值。",
            countMatching(navHost, """LocalBottomNavOcclusionRatio\s+provides\s*\{\s*bottomNavOcclusionRatio\s*\}""") == 1
        )
    }

    /**
     * 消费者不得在组合期读遮挡比。
     *
     * 三个消费者（首页 feed / 排行榜 / 播放胶囊）都只在 `tryOpen` 那一刻用得到它，
     * 所以读取点必须落在点击路径里。另外还有一条**正确性**理由：遮挡比是 Dock
     * 布局回调异步实测的，常常晚于页面首次组合；把它 remember 住就等于有概率永久
     * 缓存"还没测到"的 0f，表现为返回动画末端卡片被 Dock 切一刀。
     */
    @Test
    fun consumersReadOcclusionAtClickTimeNotComposition() {
        val offenders = mainSources.filter { file ->
            val lines = codeLines(file)
            // 把 ratio 当**值**传给 listCoverRatio（而不是调用 provider）
            countMatching(lines, """bottomOcclusionRatio\s*=\s*bottomNavRatio\s*[,)]""") > 0
        }
        assertTrue(
            "以下文件在组合期把遮挡比当值读走了（应为 bottomNavRatio() 且在点击时算）：" +
                offenders.joinToString { it.name },
            offenders.isEmpty()
        )
        // 每个消费者都必须真的调用 provider
        val callers = mainSources.count { countMatching(codeLines(it), """bottomNavRatio\(\)""") > 0 }
        assertTrue(
            "找不到任何 bottomNavRatio() 调用点 —— provider 下发了却没人读，" +
                "说明接线被改断了（遮挡渐隐会静默失效）。",
            callers >= 3
        )
    }

    /**
     * 布局回调必须"只在展开态变化时写"，不能每帧写。
     *
     * 合并动画里 `coords.size.height` 只会**变小**（`top` 随 merge 收缩），
     * 所以历史最大值就是展开态高度 —— 这既满足"测展开态固定值"的要求，
     * 又让写入在稳态收敛为零。
     */
    @Test
    fun layoutCallbacksGateTheirWrites() {
        val navHost = sourceOf("navigation${FS}AppNavHost.kt")

        val occlusionBlock = lambdaBody(navHost, "onGloballyPositioned")
        assertTrue(
            "onGloballyPositioned 里必须有一条提前 return 作为写入闸门（按展开态最大值去重）。" +
                "没有闸门就意味着合并动画 280ms 内每帧写一次遮挡比 → 每帧重组整棵 AppNavHost。",
            occlusionBlock.any { it.contains("return@onGloballyPositioned") } &&
                occlusionBlock.any { it.contains("expandedDockGeometryPx") }
        )
        val writeAt = occlusionBlock.indexOfFirst { it.contains("onOcclusionChanged(") }
        val guardAt = occlusionBlock.indexOfFirst { it.contains("return@onGloballyPositioned") }
        assertTrue(
            "onOcclusionChanged 之前必须先有去重判断（当前顺序反了或压根没有）。",
            writeAt > guardAt && guardAt >= 0
        )

        val sizeBlock = lambdaBody(navHost, "onSizeChanged")
        assertTrue(
            "onSizeChanged 不该再把 it.height 无条件写进 hiddenDistancePx：" +
                "合并动画每帧都改高度，会带着 animateFloatAsState 的 targetValue 每帧重算。" +
                "应改为只在读到更大值时写（展开态高度）。",
            sizeBlock.any { it.contains("expandedDockGeometryPx") } &&
                !sizeBlock.any { Regex("""hiddenDistancePx\s*=\s*it\.height""").containsMatchIn(it) }
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private val FS: String get() = File.separator

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("结构断言找不到 `$suffix`，此时\"没找到违规\"是假绿。")
        return file.readLines()
    }

    /**
     * 取 [trigger] 那次调用的 lambda 体（从 `xxx {` 行起，按花括号配平到闭合）。
     *
     * 需要跨行才能判断"写入前是否有闸门"这种**顺序**性质，单行正则做不到。
     */
    private fun lambdaBody(lines: List<String>, trigger: String): List<String> {
        val start = lines.indexOfFirst { it.contains("$trigger ") || it.contains("$trigger{") }
        assertTrue("找不到 `$trigger` 调用点，无法验证其内部顺序。", start >= 0)
        var depth = 0
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (i > start && depth <= 0) break
        }
        return body
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
            trimmed.startsWith("/*") || trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { it.isNotBlank() && re.containsMatchIn(it) }
    }
}
