package com.naaammme.bbspace.video

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打开新视频」两条路径必须等价的守卫（BACKLOG ST-08）。
 *
 * ## 病灶
 * `VideoDetailActivity` 是 `singleTop`（`AndroidManifest.xml:79`）。详情页里再点开一个视频
 * （相关推荐 / UP 主投稿 / 评论区跳转）走的是 `onNewIntent`，**不会重跑 onCreate** ——
 * 于是 onCreate 里那句 `hostState.cancelPendingClose()` 被跳过。
 * `closeAfterReturnAnimation()` 的契约就写在 `PlaybackHostState` 里：
 * "期间重新打开视频由 cancelPendingClose 取消，避免误杀新会话"。
 * 没取消的话，那个等返回动画收尾的 job 一落地，close 掉的是刚接管的新会话。
 *
 * ## 为什么只钉这一条，不钉"两条路径完全等价"
 * onCreate 里的 `capsuleState.deactivate()`（:110-112）**刻意没有**同步到 onNewIntent：
 * 它背后是"无缝打开时锚点 View 不能提前 detach，否则系统放弃 morph 退回 alpha"的
 * 真机对比实验（注释里引了 OEM 日志），在无设备条件下无法判断复用实例路径上该怎么走。
 * 那条留在 `L4-待真机清单.md`，不在这儿假装等价。
 *
 * ## 防假绿
 * 先断言真读到源码、且目标文件在扫描结果里；注释行与 import 行清空后再匹配。
 */
class VideoDetailHandoffGuardTest {

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

    private fun activityLines(): List<String> {
        val file = mainSources.firstOrNull { it.name == "VideoDetailActivity.kt" }
            ?: throw AssertionError("没扫到 VideoDetailActivity.kt —— 路径或文件名变了，本守卫已失效。")
        return file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
                trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
        // 触达一次，文件读不到就直接抛，避免后面拿空集合永真
        assertTrue(activityLines().size > 100)
    }

    /** 两条打开路径都必须取消上一次遗留的延迟关停。 */
    @Test
    fun bothOpenPathsCancelThePendingClose() {
        val lines = activityLines()
        for (signature in listOf("override fun onCreate(", "override fun onNewIntent(")) {
            val body = blockBody(lines, signature)
            assertTrue(
                "`$signature` 必须调用 hostState.cancelPendingClose() —— singleTop 复用实例时" +
                    "漏掉它，等返回动画收尾的 job 会 close 掉刚接管的新会话。",
                body.any { it.contains("hostState.cancelPendingClose()") }
            )
            assertTrue(
                "`$signature` 必须消费新 intent（consumeIntent），否则 pendingTarget 还是旧视频。",
                body.any { it.contains("consumeIntent(") }
            )
        }
    }

    /** onNewIntent 里必须先解析成功、再 setIntent（否则解析失败会毁掉可用 intent）。 */
    @Test
    fun newIntentIsOnlyAdoptedAfterItParses() {
        val body = blockBody(activityLines(), "override fun onNewIntent(")
        val guardAt = body.indexOfFirst { it.contains("if (!consumeIntent(intent)) return") }
        val adoptAt = body.indexOfFirst { it.contains("setIntent(intent)") }
        assertTrue(
            "onNewIntent 必须先 `if (!consumeIntent(intent)) return` 再 `setIntent(intent)`：" +
                "解析失败时 consumeIntent 不登记任何字段，而 setIntent 已把 intent 换成" +
                "带不了目标的那一个，之后任何再读 intent 的路径全部失真。",
            guardAt in 0 until adoptAt
        )
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> { depth++; opened = true }
                    '}' -> depth--
                }
            }
            if (opened && depth == 0) break
        }
        return body
    }
}
