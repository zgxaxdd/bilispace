package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 列表滚动位置持久化的结构断言（任务书 T7 第 2 条：把上游那类
 * `HomeFeedScrollStatePersistenceStructureTest` 补到 BBSpace 的页面上）。
 *
 * ## 这里真正会踩的坑是什么
 *
 * `rememberLazyListState()` / `rememberLazyStaggeredGridState()` 内部用的就是
 * `rememberSaveable` + `LazyListState.Saver`，所以**滚动位置本来就能跨配置变更/进程
 * 恢复**。而直接 `LazyListState()` 构造出来的对象没有 Saver —— 写起来一样、编译通过、
 * 当下行为也一模一样，只在"转屏后/进程被杀后回来"那一刻才发现位置没了。
 * 这类缺陷没有任何静态报错，所以它需要一个测试。
 *
 * ## 为什么详情页这几处直接构造是**允许**的
 *
 * 它们落在 `remember(aidKey) { ... }` 里：换一条视频就必须重置位置，这是需求而不是疏忽；
 * 而 `VideoDetailActivity` 在 manifest 里声明了
 * `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`，
 * 转屏不会重建 Activity，所以这里拿不到"saveable"也不是缺陷。
 * 本测试把它们钉成**恰好三处、且必须带 aidKey**：多一处、或脱离 aidKey 用在别处，
 * 就是在别的地方重新引入那个静默缺陷。
 *
 * 2026-09-27 变更记录：详情列表状态从 `VideoDetailPage` **提升到 VideoScreen**
 * （下滑进全屏手势要在父层查询「列表还能不能向下滚」，见
 * `VideoScreen.pullDownToFullscreen` 的 canListScrollDown）。豁免随之拆成两半：
 * VideoScreen 1 处（详情）+ VideoDetailPage 2 处（评论 / 楼中评论），合计仍是 3 处、
 * 仍全部按 aid 归键 —— 不变式（换视频即重置位置）原样保留，只是搬了家。
 */
class ScrollStatePersistenceStructureTest {

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

    /**
     * 绕过 saveable 工厂、直接构造列表状态的位置。
     *
     * 这条负向断言的**全部价值**在于区分两种写法：
     * `rememberLazyListState()` 带 Saver，`LazyListState()` 不带。所以模式必须用
     * `(?<!remember)` 排除掉前者 —— 否则 `rememberLazyListState()` 这种完全正确的
     * 写法会被算成违规，测试第一次跑就红，然后被人顺手放宽、从此失去意义。
     */
    @Test
    fun rawListStateConstructionIsContained() {
        val offenders = mainSources
            .filter { it.name != "VideoDetailPage.kt" && it.name != "VideoScreen.kt" }
            .flatMap { file ->
                codeLines(file).mapIndexedNotNull { index, line ->
                    if (RAW_LIST_STATE.containsMatchIn(line)) "${file.name}:${index + 1}" else null
                }
            }
        assertTrue(
            "以下位置直接构造了列表状态，绕过了 rememberLazyListState()/rememberLazyStaggeredGridState()：" +
                "${offenders.joinToString()}。这些工厂内部带 Saver，直接构造的没有 —— " +
                "差别只在转屏/进程重启后才显现，所以既不报错也难在走查里被发现。" +
                "（唯一豁免：VideoDetailPage 与 VideoScreen 里按 aid 归键的那几处，见下条断言。）",
            offenders.isEmpty()
        )
    }

    /**
     * 豁免清单本身也要被钉住：恰好三处，且都在 `remember(aidKey)` 里。
     *
     * 三处的分布（2026-09-27 起）：VideoScreen 1 处（详情列表，供下滑进全屏手势
     * 查询可滚状态）+ VideoDetailPage 2 处（评论 / 楼中评论）。提升前后合计不变，
     * 都必须按 aid 归键 —— 换视频即重置位置。
     */
    @Test
    fun videoDetailExemptionStaysNarrow() {
        val screen = sourceOf("VideoScreen.kt")
        val page = sourceOf("detail${FS}VideoDetailPage.kt")
        val raw = countMatching(screen, RAW_LIST_STATE.pattern) +
            countMatching(page, RAW_LIST_STATE.pattern)
        val keyed = countMatching(
            screen,
            """remember\(videoState\.ids\.aid\.takeIf\s*\{\s*it\s*>\s*0L\s*\}\s*\)\s*\{\s*LazyListState\(\s*\)"""
        ) + countMatching(page, """remember\(aidKey\)\s*\{\s*LazyListState\(\s*\)""")
        assertTrue(
            "详情/评论列表的直接构造应为 3 处（VideoScreen 详情 1 + VideoDetailPage 评论/楼中评论 2），" +
                "实际 $raw 处。多了要说清楚为什么，少了要同步这条断言。",
            raw == 3
        )
        assertTrue(
            "三处直接构造都必须按 aid 归键（换视频即重置位置），实际带 key 的只有 $keyed 处。",
            keyed == 3
        )
    }

    /**
     * 任务书点名的几个页面必须用带 Saver 的工厂。
     *
     * 逐页点名而不是全局正则，是因为"这一页到底该不该记住位置"是产品决定；
     * 点名之后，有人把某页改成不持久化时会在这里失败，而不是悄悄变得不一致。
     */
    @Test
    fun namedPagesUseSaveableStateFactories() {
        val expectations = listOf(
            // 首页信息流（双列瀑布流）
            "video${FS}HomeVideoPage.kt" to """rememberLazyStaggeredGridState\(""",
            // 评论搜索
            "commentsearch${FS}CommentSearchPane.kt" to """rememberLazyListState\(""",
            // 发布记录
            "publishedrecord${FS}PublishedRecordPane.kt" to """rememberLazyListState\(""",
            //  Season/分P 列表
            "detail${FS}VideoDetailSeasonPage.kt" to """rememberLazyListState\("""
        )
        for ((suffix, pattern) in expectations) {
            val lines = sourceOf(suffix)
            assertTrue(
                "`$suffix` 应经 saveable 工厂创建列表状态（模式 $pattern）。",
                countMatching(lines, pattern) >= 1
            )
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private companion object {
        /**
         * 匹配 `LazyListState()` / `LazyStaggeredGridState()` 的**直接构造**。
         *
         * `(?<!remember)` 是这条断言能不能成立的关键：`rememberLazyListState()` 的
         * 结尾同样是 `LazyListState()`，没有这个负向断言就会把完全正确的写法算成违规，
         * 测试第一次跑就红、然后被人顺手放宽、从此失去意义。
         */
        val RAW_LIST_STATE = Regex("""(?<!remember)Lazy(?:StaggeredGrid|List)?State\(\s*\)""")
    }

    private val FS: String get() = File.separator

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("结构断言找不到 `$suffix`，\"没找到违规\"此时是假绿。")
        return file.readLines()
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
