package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 卡片**密度口径单源**守卫（2026-10-01，阶段三 U1）。
 *
 * ## 它守的东西
 * 首页信息流卡、排行页卡与全站栅格默认值原先各自内联同一批 `8/6/10/12 dp`。
 * 三处注释都点名过同一句话："两处不同步就会出现首页贴边、排行页留白这种不一致" ——
 * 也就是说这个漂移**早就发生过**，只是当时靠人工对齐修回去了。
 * 现在 `BiliCardDensity` 是那批数值的唯一定义处，本守卫钉三件事：
 *
 * 1. 三个消费方**都**引用它（少一个就等于留下第二份真值源）；
 * 2. 被收编的那批内联形态**不许回来**（按代码形态匹配，不按名字，见下方说明）；
 * 3. `BiliCardDensity` 自己**不含任何 dp 字面量** —— 它只允许指回 `BiliSpacing` 档位，
 *    否则"改一处全站同步"会变成"多一处可以偷偷改数值的地方"。
 *
 * ## 锚点纪律（本仓反复兑现过的教训 W21）
 * 判据只认**代码形态**（`.padding(horizontal = 8.dp, vertical = 6.dp)` 这种带调用形状的串），
 * 不认裸数字 —— 裸 `8.dp` 在注释里必然出现，用它当锚点断言会永真/永假。
 * 扫描时注释与 import 行已被清空，所以形态匹配只可能命中真实代码。
 */
class CardDensitySingleSourceTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(vararg parts: String): List<String> = codeOf(parts.toList())

    private fun codeOf(parts: List<String>): List<String> {
        val suffix = parts.joinToString(File.separator)
        val file = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .firstOrNull { it.path.endsWith(suffix) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}），断言无意义。")
        return file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) "" else line
        }
    }

    @Test
    fun everyCardSurfaceReadsTheSharedDensity() {
        val consumers = listOf(
            listOf("component", "AdaptiveMediaGrid.kt"),
            listOf("video", "HomeVideoPage.kt"),
            listOf("rank", "RankScreen.kt")
        )
        for (parts in consumers) {
            val lines = codeOf(parts)
            assertTrue(
                "${parts.last()} 必须引用 BiliCardDensity（栅格/卡片密度只允许有一处定义）",
                lines.any { it.contains("BiliCardDensity.") }
            )
        }
    }

    @Test
    fun inlinedCardPaddingShapesStayRemoved() {
        val forbidden = Regex("""padding\(horizontal = 8\.dp, vertical = 6\.dp\)""")
        val offenders = listOf(
            listOf("video", "HomeVideoPage.kt"),
            listOf("rank", "RankScreen.kt")
        ).flatMap { parts ->
            codeOf(parts).filter { forbidden.containsMatchIn(it) }
                .map { "${parts.last()}: $it" }
        }
        assertTrue(
            "卡信息区内边距必须走 BiliCardDensity.infoHorizontal/infoVertical，" +
                "内联 8/6 是会漂移的第二份真值源。命中：$offenders",
            offenders.isEmpty()
        )

        val pagePadding = Regex("""start = 10\.dp,""")
        val pageOffenders = listOf(
            listOf("component", "AdaptiveMediaGrid.kt"),
            listOf("video", "HomeVideoPage.kt"),
            listOf("rank", "RankScreen.kt")
        ).flatMap { parts ->
            codeOf(parts).filter { pagePadding.containsMatchIn(it) }
                .map { "${parts.last()}: $it" }
        }
        assertTrue(
            "页边距必须走 BiliCardDensity.pageHorizontal（首页/排行/栅格默认三处同值，" +
                "历史上就同步过一次）。命中：$pageOffenders",
            pageOffenders.isEmpty()
        )
    }

    @Test
    fun densityObjectDefinesNoRawDpLiterals() {
        val body = run {
            val lines = codeOf("theme", "Spacing.kt")
            val start = lines.indexOfFirst { it.trimStart().startsWith("object BiliCardDensity") }
            assertTrue("找不到 object BiliCardDensity —— 它被改名了？", start >= 0)
            var depth = 0
            var opened = false
            val out = mutableListOf<String>()
            for (i in start until lines.size) {
                out += lines[i]
                for (ch in lines[i]) {
                    when (ch) {
                        '{' -> { depth++; opened = true }
                        '}' -> depth--
                    }
                }
                if (opened && depth <= 0) break
            }
            out
        }
        val raw = body.filter { Regex("""=\s*\d+(\.\d+)?\.dp""").containsMatchIn(it) }
        assertTrue(
            "BiliCardDensity 只允许指回 BiliSpacing 档位（写死 dp 就等于又开了一处可以" +
                "偷偷改密度的地方，棘轮也管不到它）。命中：$raw",
            raw.isEmpty()
        )
        assertTrue(
            "每个密度值都必须来自 BiliSpacing（保证与全站间距档位同源）",
            body.count { it.contains("BiliSpacing.") } >= 7
        )
    }
}
