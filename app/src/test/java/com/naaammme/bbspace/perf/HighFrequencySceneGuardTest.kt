package com.naaammme.bbspace.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 高频场景（评论 / 直播 / IM / 附件）修复的接线守卫（AP-17 ④ / AP-18 ② / AP-19 ②）。
 *
 * 每条都锚在**调用形态**上（W20），且跳过注释行（W7：被注释的修复等于没修）：
 * - AP-17：附图读取必须走有界 `readAtMost`，整流 `readBytes()` 读法不许回潮；
 * - AP-18：`LiveScreen` 顶层不得再收集 popularCount / roomPanel（补丁驱动整页重组）；
 * - AP-19：评论卡片不得再收 `busyReplyIds: Set<Long>`（不稳定实参 → 全可见卡重组），
 *   必须走稳定闭包 + 卡内派生读。
 */
class HighFrequencySceneGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(suffix: String): List<String> {
        val file = File(repoRoot, suffix.replace('/', File.separatorChar))
        assertTrue("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。", file.isFile)
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

    /** AP-17：附图读取必须有界；无界 readBytes 流式全读不许回潮。 */
    @Test
    fun attachmentReadingIsBounded() {
        val lines = codeLines(
            sourceOf("core/comment/src/main/java/com/naaammme/bbspace/core/comment/ImageAttachmentHandler.kt")
        )
        assertTrue(
            "ImageAttachmentHandler 必须经 readAtMost 有界读取（AP-17：整流读入是 OOM 面）。",
            lines.any { it.contains("readAtMost(it, limit") }
        )
        assertTrue(
            "无界 `it.readBytes()` 全流读法不许回潮（AP-17 病灶）。",
            lines.none { it.contains("it.readBytes()") }
        )
    }

    /** AP-18：LiveScreen 顶层不得收集 popularCount / roomPanel。 */
    @Test
    fun liveScreenDoesNotCollectPanelPatchesAtTopLevel() {
        val lines = sourceOf("feature/live/src/main/java/com/naaammme/bbspace/feature/live/LiveScreen.kt")
        val screenBody = codeLines(blockBody(lines, "fun LiveScreen("))
        val offenders = screenBody.filter {
            it.contains("viewModel.popularCount.collectAsStateWithLifecycle") ||
                it.contains("viewModel.roomPanel.collectAsStateWithLifecycle")
        }
        assertTrue(
            "LiveScreen 顶层出现了 popularCount/roomPanel 的收集（AP-18 病灶回潮：每次人气/" +
                "在线榜补丁重组整页，连带播放器与 200 项消息列表）。必须下推到 TopBarPanel 叶子内。" +
                "违规行：$offenders",
            offenders.isEmpty()
        )
        val panelBody = codeLines(blockBody(lines, "private fun TopBarPanel("))
        assertTrue(
            "TopBarPanel 必须在叶子内收集两个 StateFlow（AP-18 的修复形态）。",
            panelBody.count { it.contains("collectAsStateWithLifecycle") } >= 2
        )
    }

    /** AP-19：评论卡片不得再收 Set 实参。 */
    @Test
    fun commentCardsTakeStableProbeNotSet() {
        val cardLines = codeLines(
            sourceOf("feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/component/CommentReplyCard.kt")
        )
        assertTrue(
            "CommentReplyCard 不得再有 `busyReplyIds: Set<Long>` 参数（AP-19：不稳定实参 → " +
                "每次动作两轮全可见卡重组）。必须走 isReplyBusy 稳定闭包 + 卡内 derivedStateOf。",
            cardLines.none { it.contains("busyReplyIds: Set<Long>") }
        )
        assertTrue(
            "ReplyBody 必须有卡内派生读（derivedStateOf 包 isReplyBusy）——否则集合每次变化" +
                "仍会波及所有读卡。",
            cardLines.any { it.contains("derivedStateOf { isReplyBusy(reply.rpid) }") }
        )
        val vmLines = codeLines(
            sourceOf("feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/CommentViewModel.kt")
        )
        assertTrue(
            "CommentViewModel 必须暴露稳定的 isReplyBusy 闭包（实例终身一次，可作组合参数）。",
            vmLines.any { it.contains("val isReplyBusy: (Long) -> Boolean = { rpid -> rpid in busyReplyIds.value }") }
        )
    }
}
