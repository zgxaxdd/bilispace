package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分页"增长守卫"接线守卫（AP-10 ②）：全站每个"预取下一页"入口都必须经过
 * [com.naaammme.bbspace.core.designsystem.component.LoadMoreGrowthGuard]。
 *
 * ## 缺陷回顾
 *
 * 预取谓词随 `isLoadingMore` 翻转（直接写或经 `canLoadMore` getter 折算）时，
 * 服务端返回空页/全重复页 → effect 反复重启 → 无界请求风暴。全站 9 个预取入口里
 * 只有 AdaptiveMediaGrid 自带增长守卫（范式出处），其余 8 个全部裸奔 ——
 * 不是"没人知道"，是"没机器拦"（本守卫即那台机器）。
 *
 * ## 判定（W20：锚在预取 effect 的调用形态上，不做文件级禁令）
 *
 * 每个登记入口：预取 effect 体（跳过注释行）必须同时含
 * `loadMoreGuard.onVisibility(` 与 `loadMoreGuard.tryAcquire()`，且文件内存在
 * `onItemLineChanged(` 调用。拔掉任何一段即红。
 */
class LoadMoreGrowthStructureTest {

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

    /** 8 个普通 LazyColumn 预取入口（AdaptiveMediaGrid 是内联范式本体，不在此列）。 */
    private val entries: List<Pair<String, String>> = listOf(
        "feature/search/src/main/java/com/naaammme/bbspace/feature/search/SearchScreen.kt" to
            "LaunchedEffect(shouldLoadMore, viewModel.isLoading",
        "feature/dynamic/src/main/java/com/naaammme/bbspace/feature/dynamic/DynamicScreen.kt" to
            "LaunchedEffect(shouldLoadMore, state.isLoading",
        "feature/im/src/main/java/com/naaammme/bbspace/feature/im/conversation/ImConversationScreen.kt" to
            "LaunchedEffect(shouldLoadMore, state.isLoadingMore",
        "feature/favorite/src/main/java/com/naaammme/bbspace/feature/favorite/item/FavoriteContentList.kt" to
            "LaunchedEffect(listState)",
        "feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/CommentPanel.kt" to
            "LaunchedEffect(shouldLoadMore, uiState.loading",
        "feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/thread/CommentThreadPane.kt" to
            "LaunchedEffect(shouldLoadMore, stateHolder.loading",
        "feature/bbspace/src/main/java/com/naaammme/bbspace/feature/bbspace/commentsearch/CommentSearchPane.kt" to
            "LaunchedEffect(shouldLoadMore, state.isLoading",
        "feature/bbspace/src/main/java/com/naaammme/bbspace/feature/bbspace/publishedrecord/PublishedRecordPane.kt" to
            "LaunchedEffect(shouldLoadMore, state.isLoading",
    )

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件，路径推导失效则全在假绿。",
            mainSources.size > 100
        )
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

    @Test
    fun everyPrefetchEntryHasGrowthGuard() {
        val failures = mutableListOf<String>()
        for ((rel, anchor) in entries) {
            val lines = mainSources.firstOrNull { it.path.replace('\\', '/').endsWith(rel) }
            if (lines == null) {
                failures.add("$rel —— 文件找不到（移动/改名后请同步本登记表）")
                continue
            }
            val content = lines.readLines()
            val effect = codeLines(blockBody(content, anchor))
            if (effect.none { it.contains("loadMoreGuard.onVisibility(") } ||
                effect.none { it.contains("loadMoreGuard.tryAcquire()") }
            ) {
                failures.add("$rel —— 预取 effect 缺增长守卫接线（onVisibility + tryAcquire）")
            }
            if (codeLines(content).none { it.contains("loadMoreGuard.onItemLineChanged(") }) {
                failures.add("$rel —— 缺条数变化复位（onItemLineChanged）")
            }
        }
        assertTrue(
            buildString {
                append("以下预取入口的增长守卫接线缺失或被拔线（AP-10）：")
                failures.forEach { append("\n    $it") }
            },
            failures.isEmpty()
        )
    }

    /** W21 自检：守卫调用的形态必须能被抓到；注释掉的接线不算接线。 */
    @Test
    fun scannerSelfCheck() {
        val positive = "        if (shouldLoadMore && loadMoreGuard.tryAcquire()) {"
        val commented = "        // if (shouldLoadMore && loadMoreGuard.tryAcquire()) {"
        val positiveBlock = listOf(
            "    LaunchedEffect(shouldLoadMore, x) {",
            "        loadMoreGuard.onVisibility(shouldLoadMore, busy = false)",
            positive,
            "    }"
        )
        val body = codeLines(blockBody(positiveBlock, "LaunchedEffect(shouldLoadMore, x)"))
        assertEquals(
            "自检失败：守卫调用应被块体抓到",
            1,
            body.count { it.contains("loadMoreGuard.tryAcquire()") }
        )
        val commentedBlock = listOf(
            "    LaunchedEffect(shouldLoadMore, x) {",
            "        // loadMoreGuard.onVisibility(shouldLoadMore, busy = false)",
            commented,
            "    }"
        )
        val commentedBody = codeLines(blockBody(commentedBlock, "LaunchedEffect(shouldLoadMore, x)"))
        assertTrue(
            "自检失败：注释掉的接线不得算作接线（W7：被注释的调用等于没有调用）",
            commentedBody.none { it.contains("loadMoreGuard") }
        )
    }
}
