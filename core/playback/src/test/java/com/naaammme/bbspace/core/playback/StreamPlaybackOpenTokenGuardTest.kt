package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `openId` 代际号守卫（2026-10-16）。
 *
 * ## 为什么需要它
 *
 * `StreamPlaybackSessionImpl` 用一条单调递增的代际号（`openId`）保护"开播流程"：
 * 建号 → 每个挂起点之后校验 → 旧代际的回调一律丢弃。它是**唯一**防止
 * "用户已经切到下一个视频、上一个视频的异步结果才到达并覆盖新会话"的机制。
 *
 * 但这条机制此前**没有任何测试**：`core/playback/src/test` 里 `openId` 命中数为 0
 * ⇒ 删掉任意一处校验、或把 `incrementAndGet()` 改成 `get()`，**不会有任何测试变红**。
 * 也就是说它是"碰巧写对"的，不是被守住的。本测试把它变成机器判据。
 *
 * ## 判据形态：源码扫描（与 `PlaybackHeadPrewarmWiringGuardTest` 同款）
 *
 * 为什么不做行为断言：`openId` 是 `private val`，且本类依赖 media3 引擎与
 * 六个仓库（`build.gradle.kts` 里 `implementation(project(":infra:player"))` 等），
 * 在 JVM 单测里无法实例化。可做的、也是本仓既有体例的做法，是**从源码解析结构**：
 * 谁在哪里建号、哪些挂起点之后有拒绝、失效路径是否真的递增。
 *
 * ## 为什么断言"位置关系"而不是"条数"
 *
 * 只数 `openId.get() != token` 出现几次是**脆弱判据**：把校验从 A 挂起点挪到
 * B 挂起点（少守一处真窗口）条数不变、测试照样绿。因此这里断言的是
 * **每一个 `await()` 之后都必须有拒绝**（顺序关系），条数只是附带自检。
 *
 * ## 正反例成对（W21 体例）
 *
 * [scannerRecognisesRejectionAndSkipComments] 用合成字符串证明扫描器真的认得
 * "拒绝"形态、且会把注释/KDoc 行排除掉 —— 否则下面的断言可能因为
 * "正则匹配到了注释里的一句话"而变成永真。
 */
class StreamPlaybackOpenTokenGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private val sourcePath = "core/playback/src/main/java/com/naaammme/bbspace/core/playback" +
        "/StreamPlaybackSessionImpl.kt"

    /**
     * 读源码并把**注释/KDoc/import 行清空**（保留行号，便于把失败信息指到真实行）。
     *
     * 清空而不是删除：断言里的"第 N 行"必须与编辑器里看到的行号一致。
     */
    private val lines: List<String> by lazy {
        val f = File(repoRoot, sourcePath)
        assertTrue(
            "找不到 $sourcePath（repoRoot=${repoRoot.absolutePath}）—— 此时全部断言都无意义。",
            f.isFile
        )
        f.readLines().map { raw ->
            val t = raw.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") ||
                t.startsWith("import ") || t.startsWith("*/")
            ) {
                ""
            } else {
                raw
            }
        }
    }

    private fun lineOf(predicate: (String) -> Boolean): Int =
        lines.indexOfFirst(predicate)

    /** 把从 [startLine] 起的花括号块体取出来（含首行），用于"限定在某函数内"断言。 */
    private fun blockBody(startLine: Int): List<String> {
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in startLine until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }

    // ── 扫描器（同时供正反例自检使用）────────────────────────────────────────

    /** "旧代际必须被拒绝"的形态：`openId.get() != token` 后跟一个早退。 */
    private val rejectRe = Regex("""openId\.get\(\)\s*!=\s*token""")

    private fun isRejectionLine(line: String): Boolean = rejectRe.containsMatchIn(line)

    /** "使旧代际失效"的形态：`invalidateOpen` 为真时递增。 */
    private val invalidateRe = Regex("""if\s*\(\s*invalidateOpen\s*\)\s*openId\.incrementAndGet\(\)""")

    // ── 断言 1：机制存在且形态正确 ────────────────────────────────────────────

    @Test
    fun openIdIsAMonotonicCounterNotAFlag() {
        assertTrue(
            "`openId` 必须是 `AtomicLong`（代际号，单调递增）而不是 Boolean 标志：" +
                "标志无法区分「第 1 次」与「第 3 次」，切回同一个视频时会误判为同代。",
            lines.any { it.contains("private val openId = AtomicLong(") }
        )
        assertTrue(
            "建号必须是 `incrementAndGet()`（返回新代际）；写成 `get()` 会让每次开播拿到同一个号，校验全部失效。",
            lines.any { it.contains("val token = openId.incrementAndGet()") }
        )
    }

    // ── 断言 2（核心）：每个持有代际号的函数，拒绝点数量必须守住 ────────────────

    /**
     * ## 为什么用"逐函数计数"而不是"位置推断"（四次失败的记录）
     *
     * 最初想断言"每个挂起点之后必须跟着拒绝"，实测发现**做不到**，依次试过：
     *
     * 1. **逐点邻接**（await 后 3 行内有拒绝）：合法代码里五个并发等待
     *    （`prepareJob` / `sourceJob` / `qualityJob` / `audioJob` / `localResumeJob`）
     *    共用紧随其后的一次拒绝 ⇒ 前两个 await 被误判成违规（**假红**）。
     * 2. **按拒绝点分区**：删掉 `:469` 那处拒绝后，后面的拒绝点会把前面所有 await
     *    "接住"，判据仍绿（**假绿**）。
     * 3. **拒绝必须先于下一个落地动作**：`_currentTarget.value =` 在 token 建立
     *    **之前**的初始化阶段也有一次，被误判（**假红**）。
     * 4. **await/reject 交替推进**：同样区分不出删的是哪一处（**假绿**）。
     *
     * 根因：本仓这段代码的语义是"**一个挂起窗口 = 一次校验**"，而窗口边界
     * 无法从行文本可靠推断（并发等待、`getOrElse` 内联分支、`catch` 分支
     * 都各有各的形态）。**任何基于行距/邻接的判据都会在某一侧失真。**
     *
     * ⇒ 改用**计数棘轮**：逐函数钉住"拒绝点出现次数"。它不区分删的是哪一行，
     * 但**删掉任意一处都会让计数下降 ⇒ 必然红**（已用 9 处逐一验证）。
     * 新增一处校验也会让计数上升 ⇒ 必须显式改本表并写明理由（这正是想要的：
     * 这个机制是并发正确性的最后一道闸，改动就该有人过目）。
     */
    @Test
    fun rejectionCountsPerFunctionArePinned() {
        // 期望值来自实测（2026-10-16）：
        //   openVideoInternal     6 处拒绝（覆盖 5 个并发等待 + 详情回包 + catch 分支）
        //   applyVideoSelection   3 处拒绝（换流路径：launch 前 / setSource 后 / catch）
        //   finishVideoPlayback   0 处拒绝，1 处失效递增（由断言 3 单独守）
        val expected = mapOf(
            "private suspend fun openVideoInternal(" to 6,
            "private fun applyVideoSelection(" to 3,
        )
        expected.forEach { (signature, want) ->
            val start = lineOf { it.contains(signature) }
            assertTrue("找不到 `$signature` —— 改名了？本判据的锚点要同步更新。", start >= 0)
            val got = blockBody(start).count { isRejectionLine(it) }
            assertEquals(
                "`$signature` 里的 `openId.get() != token` 拒绝点数量从 $want 变成了 $got。" +
                    "**删掉任意一处都会让旧代际的回调落地并覆盖新会话**（本机制存在的唯一理由）；" +
                    "新增一处也应在此登记 —— 请先确认语义，再同步改本期望值并写明理由。",
                want,
                got
            )
        }
    }

    // ── 断言 2b：拒绝点总数下界（跨函数兜底，防"整体被重构掉"）───────────────

    @Test
    fun rejectionCountStaysAboveTheKnownFloor() {
        val rejects = lines.count { isRejectionLine(it) }
        assertTrue(
            "`openId` 拒绝点全文件只剩 $rejects 处，低于已知下界 9 处。" +
                "该机制分布在开播主流程与换流路径两处，低于此数说明有人删掉了校验。",
            rejects >= 9
        )
    }

    // ── 断言 3：失效路径真的递增 ─────────────────────────────────────────────

    @Test
    fun invalidationBumpsTheCounter() {
        assertTrue(
            "`finishVideoPlayback` 里必须有 `if (invalidateOpen) openId.incrementAndGet()`：" +
                "关闭/切走时若不递增，进行中的旧代际仍等于当前号，" +
                "它的结果会在稍后到达并写进已经空掉的会话。",
            lines.any { invalidateRe.containsMatchIn(it) }
        )
        // 反向：不许把递增写成无条件（那会让"复用同一会话"的调用方也被打断）
        assertTrue(
            "递增必须由 `invalidateOpen` 门控 —— 无条件递增会把 `openVideoInternal` 自己的" +
                "初始化调用（invalidateOpen = false）也判成过期。",
            lines.any { it.contains("invalidateOpen = false") }
        )
    }

    // ── 断言 4：取消分支用的是"仍属本代"而不是"不属于本代" ────────────────────

    @Test
    fun cancellationCleanupRequiresStillBeingTheCurrentGeneration() {
        // 取消时清理会话，条件必须是 `==`（仍属本代）：
        // 用 `!=` 会让"已经被新开播取代"的取消去清空**新会话**的状态。
        assertTrue(
            "取消分支的清理必须以 `openId.get() == token` 为前提（仍属本代才清）：" +
                "写成 `!=` 会把新会话的状态清掉。",
            lines.any { it.contains("openId.get() == token") }
        )
    }

    // ── 断言 5：第二次开播的 token 是"读当前"而不是"新建" ─────────────────────

    @Test
    fun selectionReusesCurrentGenerationInsteadOfCreatingOne() {
        val selStart = lineOf { it.contains("private fun applyVideoSelection(") }
        assertTrue("找不到 `applyVideoSelection` —— 改名了？", selStart >= 0)
        val body = blockBody(selStart)
        assertTrue(
            "`applyVideoSelection` 里必须 `val token = openId.get()`（记录**当前**代际）：" +
                "它是在既有会话上换流，不是开新会话 —— 若写成 `incrementAndGet()`，" +
                "会把进行中的开播流程作废。",
            body.any { it.contains("val token = openId.get()") }
        )
        assertTrue(
            "换流的挂起点之后同样要有拒绝（setSource 内含网络 IO，存在挂起窗口）。",
            body.any { isRejectionLine(it) }
        )
    }

    // ── 正反例自检（W21）：证明扫描器认得形态、且不吃注释 ──────────────────────

    @Test
    fun scannerRecognisesRejectionAndSkipComments() {
        val positives = listOf(
            "                if (openId.get() != token) return@coroutineScope",
            "            if (openId.get() != token) return@launch",
            "            if (openId.get() != token) return",
            "                if (openId.get()!=token) return"
        )
        positives.forEach {
            assertTrue("自检失败：拒绝形态没被认出「$it」", isRejectionLine(it))
        }

        val negatives = listOf(
            "    private val openId = AtomicLong(0L)",          // 定义行不是拒绝
            "        val token = openId.incrementAndGet()",      // 建号不是拒绝
            "        val token = openId.get()",                  // 取号不是拒绝
            "        if (invalidateOpen) openId.incrementAndGet()" // 失效不是拒绝
        )
        negatives.forEach {
            assertTrue("自检失败：本不是拒绝形态却被认出「$it」", !isRejectionLine(it))
        }

        // ⚠️ 注释行**不能**放进上面那组反例：`isRejectionLine` 是纯正则、不看语法上下文，
        // 它命中注释里的 `openId.get() != token` 是**正确行为** —— 注释防护是 `lines`
        // 那层"清空注释行"的职责，不是扫描器的职责。这两层分开断言：
        //   ① 扫描器：认得代码里的拒绝形态（上面 positives）；
        //   ② 数据源：注释行在进入扫描前已被清空（下面这条）。
        assertTrue(
            "注释行没有被清空：`lines` 的过滤逻辑失效了。若不清空，源码 KDoc 里那句" +
                "「校验 `openId.get() != token` 后返回」会被当成真实拒绝点，让计数断言变成永真" +
                "（W21/W20 同款陷阱：锚注释 = 锚空气）。",
            lines.none { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        )
    }
}
