package com.naaammme.bbspace.feature.video.blocked

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-06 的接线守卫：
 * 1. **gRPC 而非 REST**：拉黑走 bbspace 既有的 `SpaceRepository.modifyBlacklist`
 *    （core:space 经 `UpBlocker` 接口暴露）；`feature:video` 内**禁止出现**
 *    上游 REST `x/relation/modify` 字面量（移植任务书 §0.1 X1：移植 REST = 重复造路）。
 * 2. **ownerMid > 0 守卫**（验收⑤）：无 mid 的来源（story/relate）不做假入口。
 * 3. **二次确认**：确认框必须存在（拉黑对账号实际生效）。
 * 4. **本地先行**：blockSwipeUp 先写本地名单再发 gRPC。
 */
class BlockedUpWiringTest {

    private val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(relative: String): List<String> {
        val file = File(
            repoRoot,
            relative.replace('/', File.separatorChar)
        )
        assertTrue("找不到 `$relative`（repoRoot=${repoRoot.absolutePath}）", file.isFile)
        return file.readLines()
    }

    private fun codeLines(lines: List<String>): List<String> = lines.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import "))
    }

    /**
     * 刷视频会话的**全部源码**（第 34 轮拆分后，拉黑入口/确认框落在两个文件里）。
     *
     * `PortraitSwipePager.kt` = 动作清单与确认框的**调用点**；
     * `SwipeMoreSheet.kt` = `SwipeBlockUpConfirmDialog` 与 `SwipeMoreSheetContent` 的**定义**。
     * 只读主文件会让"二次确认存在"这类断言变成假红（源码是对的，只是搬了家）。
     */
    private fun swipeSessionCode(): List<String> =
        listOf(
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt",
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/SwipeSessionChrome.kt",
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/SwipeSessionLeaves.kt",
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/SwipeMoreSheet.kt",
        ).flatMap { codeOf(it) }

    @Test
    fun blacklistGoesThroughExistingGrpcDelegate() {
        val delegate = codeOf("core/space/src/main/java/com/naaammme/bbspace/core/space/SpaceUpBlocker.kt")
        assertTrue(
            "core:space 的 SpaceUpBlocker 必须委托 SpaceRepository.modifyBlacklist" +
                "（PT-06：复用既有 gRPC，不移植上游 REST）",
            codeLines(delegate).any { it.contains("repo.modifyBlacklist(") }
        )
        assertTrue(
            "SpaceUpBlocker 必须实现 core:common 的 UpBlocker 接口（Hilt 桥接，不加依赖边）",
            delegate.any { it.contains("class SpaceUpBlocker") } && delegate.any { it.contains(": UpBlocker") }
        )
    }

    @Test
    fun featureVideoHasNoRestBlacklistLiteral() {
        val restSites = File(repoRoot, "feature/video").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .flatMap { file ->
                codeLines(file.readLines()).filter { it.contains("x/relation/modify") }
            }
            .toList()
        assertTrue(
            "feature:video 的 main 源码内不许出现 REST 拉黑字面量 x/relation/modify" +
                "（PT-06：bbspace 已有 gRPC 等价实现；REST 是重复造路）。命中：$restSites",
            restSites.isEmpty()
        )
    }

    @Test
    fun blockFlowGuardsNonPositiveMidAndWritesLocalFirst() {
        val vm = codeOf("feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoViewModel.kt")
        val blockAt = vm.indexOfFirst { it.trimStart().startsWith("fun blockSwipeUp(") }
        assertTrue("找不到 blockSwipeUp", blockAt >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in blockAt until vm.size) {
            body += vm[i]
            for (ch in vm[i]) {
                when (ch) {
                    '{' -> {
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        val code = codeLines(body)
        assertTrue(
            "blockSwipeUp 必须有 mid <= 0 守卫（验收⑤：无 mid 的来源不做假入口）——" +
                "破坏性验证口径：删掉该守卫 → 本测试必须红",
            code.any { it.contains("mid <= 0L") }
        )
        val localAt = code.indexOfFirst { it.contains("blockedUpStore.add(mid, name)") }
        val grpcAt = code.indexOfFirst { it.contains("upBlocker.modifyBlacklist(") }
        assertTrue(
            "必须**先写本地名单**（blockedUpStore.add(mid, name)）再发 gRPC（PT-06 要点①：本地先生效）" +
                "local@$localAt grpc@$grpcAt",
            localAt in 0 until grpcAt
        )
    }

    @Test
    fun doubleConfirmDialogIsPresent() {
        val pager = swipeSessionCode()
        assertTrue(
            "拉黑必须过**二次确认**（AlertDialog；拉黑对账号实际生效，PT-06 要点⑤）",
            pager.any { it.contains("IOSAlertDialog(") && it.contains("pendingBlockUp") } ||
                (
                    pager.any { it.contains("IOSAlertDialog(") } &&
                        pager.any { it.contains("pendingBlockUp") } &&
                        pager.count { it.contains("pendingBlockUp") } >= 3
                    )
        )
        assertTrue(
            "确认文案必须说明对账号实际生效（PT-06 要点⑤：二次确认的可理解性）",
            pager.any { it.contains("实际生效") }
        )
    }

    /**
     * 入口守卫（任务书 PT-06 验收⑤）：**`ownerMid > 0` 是"更多"面板出现「拉黑该 UP 主」的唯一条件**。
     *
     * 为什么必须钉在**调用点**（W20）而不是只钉 `SwipeMoreSheetContent` 内部的 `if (canBlockUp)`：
     * 面板本身对所有人可见，真正决定"这个来源能不能拉黑"的是调用方传进去的那个布尔表达式。
     * story / relate 来源的 `ownerMid` 为 0（`PortraitSwipeQueue`），没有 mid 就没有可拉黑的对象 ——
     * 点了只会静默失败，是"假入口"。破坏性验证口径：把这里改成 `true`（或删掉 `> 0L`）→ 本测试必须红。
     */
    @Test
    fun blockEntryOnlyAppearsForPositiveOwnerMid() {
        val pager = swipeSessionCode()
        assertTrue(
            "调用点必须用 `(currentEntry?.ownerMid ?: 0L) > 0L` 门控「拉黑该 UP 主」" +
                "（缺 mid 的来源不做假入口）。",
            pager.any { it.contains("if ((currentEntry?.ownerMid ?: 0L) > 0L) {") }
        )
        assertTrue(
            "确认后取 mid 也必须过 `takeIf { it > 0L }`（同一判据的第二道，防止 0 传下去）。",
            pager.any { it.contains("takeIf { it > 0L }") } &&
                pager.any { it.contains("pendingBlockUp = ") }
        )
        // ⚠️ **形态更新 · 第 34 轮接手**：动作清单不再是 `SwipeMoreSheetContent` 的
        // `canBlockUp: Boolean = false` 具名形参（那个 composable 是本轮删掉的死代码，
        // 它接不进 `IOSActionSheet`，见 SwipeMoreSheet.kt 的 KDoc）。现在清单在 pager 里
        // 用 `buildList { add(IOSActionSheetAction(...)) }` 内联构造，
        // 门控形态变为「`if ((currentEntry?.ownerMid ?: 0L) > 0L) { add(...) }`」——
        // 上面第一条断言钉的就是它。
        //
        // 实质约束**不变**：入口自身必须有门控，不能只依赖调用方；
        // 「拉黑该 UP 主」这一项必须在条件块**内部**，不能被提到外层无条件展示。
        assertTrue(
            "「拉黑该 UP 主」这一项必须落在 `if ((currentEntry?.ownerMid ?: 0L) > 0L)` 的**块内**" +
                "（入口自身也要门控，不只靠调用方）；不许无条件 add。",
            run {
                val gateAt = pager.indexOfFirst { it.trimStart().startsWith("if ((currentEntry?.ownerMid ?: 0L) > 0L) {") }
                gateAt >= 0 && pager.drop(gateAt).take(8).any { it.contains("拉黑该 UP 主") }
            }
        )
    }

    /** false 断言的守卫自检（W21 反例）：正常文件不应命中"禁止字面量"。 */
    @Test
    fun scannerSelfCheck() {
        val probe = listOf(
            "    // x/relation/modify 是注释",
            "import x.y.z",
            "    val ok = 1"
        )
        val hits = codeLines(probe).filter { it.contains("x/relation/modify") }
        assertTrue("自检失败：注释行应被过滤", hits.isEmpty())
    }
}
