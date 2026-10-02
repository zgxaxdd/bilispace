package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 列表 key 完整性守卫（AP-11 ②）：凡是 `key = { … }` 直接使用**可能重复 / 可能为 0**
 * 字段的地方，必须有去重或兜底，且四处修复不许被悄悄拔线。
 *
 * ## 缺陷回顾（崩溃类）
 *
 * LazyColumn 遇到重复 key 直接抛 IllegalArgumentException —— 只在"翻页窗口重叠 /
 * 重连回放 / msgKey≤0"这些时序上发作，真机几乎无法复现。全站四处：
 * 1. 历史搜索：页拼接无去重 × key 含秒级 viewAt → 页间重叠必崩；
 * 2. IM 会话：翻页拼接无去重（同源缺陷，ImViewModel/MsgFeedViewModel 早已修好）；
 * 3. ImRepository：裸 `key = msg.msgKey`（同文件 toSentMessage 自证 msgKey 可能 ≤0）；
 * 4. 直播消息流：重连回放同 msg_id → 重复 key（入库处无 msgId 去重）。
 *
 * ## 判定（W20：锚在调用上；W7：跳过注释行 —— 注释掉的修复等于没修）
 */
class ListKeyIntegrityTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(suffix: String): List<String> {
        val file = File(repoRoot, suffix.replace('/', File.separatorChar))
        assertTrue("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。", file.isFile)
        return file.readLines()
    }

    private fun codeLines(body: List<String>): List<String> = body.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
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

    /** 1) 历史搜索：页拼接必须经 mergePagesUnique（或等价 distinctBy）。 */
    @Test
    fun historySearchMergesPagesWithDedup() {
        val lines = sourceOf("feature/history/src/main/java/com/naaammme/bbspace/feature/history/search/HistorySearchViewModel.kt")
        assertTrue(
            "HistorySearchViewModel 的页拼接必须有 mergePagesUnique/ distinctBy 去重 —— " +
                "历史持续写入导致翻页窗口重叠，重复 key 会让 LazyColumn 崩溃（AP-11）。",
            codeLines(lines).any {
                it.contains("mergePagesUnique(") || (it.contains("it.items + page.items") && it.contains("distinctBy"))
            }
        )
    }

    /** 2) IM 会话：翻页合并必须去重。 */
    @Test
    fun imConversationMergesPagesWithDedup() {
        val lines = sourceOf("feature/im/src/main/java/com/naaammme/bbspace/feature/im/conversation/ImConversationViewModel.kt")
        val body = codeLines(blockBody(lines, "private fun applyOlderMessages("))
        assertTrue(
            "ImConversationViewModel.applyOlderMessages 必须去重（ImViewModel.mergeSessions 范式，AP-11）。",
            body.any { it.contains("mergePagesUnique(") || it.contains("distinctBy") }
        )
    }

    /** 3) ImRepository：msgKey ≤0 必须有兜底键（不许裸用）。 */
    @Test
    fun imRepositoryKeysHaveZeroFallback() {
        val lines = sourceOf("core/im/src/main/java/com/naaammme/bbspace/core/im/ImRepository.kt")
        val body = codeLines(blockBody(lines, "private fun mapMessage("))
        assertTrue(
            "ImRepository.mapMessage 的 key 不得裸用 msg.msgKey（可能 ≤0，重复 key 崩溃）—— " +
                "必须 takeIf { it > 0L } 回落（AP-11；toSentMessage 已有同款处理）。",
            body.any { it.contains("takeIf { it > 0L }") && it.contains("?: -msg.msgSeqno") }
        )
    }

    /** 4) 直播消息入库：重连回放同 msg_id 必须在入口去重。 */
    @Test
    fun liveMessagesDedupeReplayedIds() {
        val lines = sourceOf("core/live/src/main/java/com/naaammme/bbspace/core/live/LiveRoomMessageRepository.kt")
        val body = codeLines(blockBody(lines, "private fun appendMessage("))
        assertTrue(
            "LiveRoomMessageRepository.appendMessage 必须按 msgId 去重（重连回放同 msg_id → " +
                "LazyColumn 重复 key 崩溃，AP-11）。",
            body.any { it.contains("it.msgId == incomingId") } &&
                body.any { it.contains("return messages") }
        )
    }
}
