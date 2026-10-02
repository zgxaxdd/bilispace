package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃守卫接线的结构断言（AP-25 ② / AP-28 ② / AP-29 ①）。
 *
 * - **AP-25**：`playerControlGlass` 必须读 `LocalGlassGuard` 并走 `resolveGlassBudget`
 *   （此前它是出厂默认配置下唯一不受守卫管辖的玻璃链）；`videoGlassActive` 必须
 *   含守卫降级条件（降级期间逐帧 tick 循环要停）。
 * - **AP-28 ②**：`GlassEffect` 必须把用户 reduceMotion 折叠进预算档位。
 * - **AP-29 ①**：`LiquidEdgeShader` 已删除（被两份文档双重实证否决的
 *   `RuntimeShader + img.eval(uv)` 邻域采样），防复活守卫：全仓 main 源码 `img.eval` 必须 0 处。
 */
class GlassGuardIntegrationTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private fun sourceOf(suffix: String): List<String> {
        val file = mainSources.firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }
            ?: throw AssertionError("找不到 `$suffix` —— 此时断言无意义。")
        return file.readLines()
    }

    private fun codeLines(lines: List<String>): List<String> = lines.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import "))
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（W23）。"
        }
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }

    @Test
    fun playerControlGlassReadsGuardAndBudget() {
        val lines = sourceOf("feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt")
        val body = codeLines(blockBody(lines, "internal fun Modifier.playerControlGlass("))
        assertTrue(
            "playerControlGlass 必须读 LocalGlassGuard（AP-25：它是默认配置下唯一不受守卫" +
                "管辖的玻璃链，降级时 Dock/顶栏停了它还在逐帧重录）。",
            body.any { it.contains("LocalGlassGuard.current") }
        )
        assertTrue(
            "playerControlGlass 必须走 resolveGlassBudget 并在 maxBlurLevel <= 0 时早退为纯底色。",
            body.any { it.contains("resolveGlassBudget(") } &&
                body.any { it.contains("budget.maxBlurLevel <= 0") }
        )
    }

    @Test
    fun videoGlassTickLoopStopsUnderGuardDowngrade() {
        val lines = sourceOf("feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt")
        val text = codeLines(lines).joinToString("\n")
        assertTrue(
            "videoGlassActive 必须含守卫降级条件（AP-25：降级期间消费端已早退，" +
                "逐帧 tick 循环继续唤醒帧时钟是纯浪费）。",
            text.contains("glassGuard.effectiveMotionTier != MotionTier.Reduced") &&
                text.contains("!glassGuard.forceLowBlurBudget")
        )
    }

    @Test
    fun glassEffectFoldsReduceMotionIntoBudgetTier() {
        val lines = sourceOf("core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/glass/GlassEffect.kt")
        val body = codeLines(blockBody(lines, "fun Modifier.glassEffect("))
        assertTrue(
            "glassEffect 必须把用户 reduceMotion 折叠进预算档位（AP-28 ②：关闭动效 = " +
                "不跑实时重录，与骨架呼吸同一口径）。",
            body.any { it.contains("LocalAnimations.current") } &&
                body.any { it.contains("reduceMotion") } &&
                body.any { it.contains("minMotionTier(") }
        )
    }

    @Test
    fun liquidEdgeShaderStaysDeleted() {
        val sites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@mapIndexedNotNull null
                if (line.contains("img.eval(") || line.contains("liquidGlassEdge(")) "${file.name}:${index + 1}" else null
            }
        }
        assertEquals(
            "LiquidEdgeShader 的 `img.eval(uv)` 邻域采样已被两份文档双重实证否定" +
                "（Adreno/ColorOS 上无效），文件已删除 —— 不许复活。实际命中：$sites",
            0,
            sites.size
        )
    }
}
