package com.naaammme.bbspace.feature.comment

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A12 接线的结构守卫：`CommentViewModel.refresh()` 的 stale-while-revalidate 时序。
 *
 * ## 为什么是源码结构断言，而不是 ViewModel 单测
 *
 * 要单测 `CommentViewModel` 得 mock 3 个依赖（`CommentRepository` /
 * `ImageAttachmentHandler` / `AuthRepository`），而本仓**没有任何 mock 库**
 * （`libs.versions.toml` 无 mockk / mockito，也无 Robolectric）。为这一处接线引入
 * 一个 mock 依赖，代价大于收益。仓库对这类"时序/所有权不变量"的既有手段就是源码
 * 结构守卫（`FrameBudgetLintTest` / `DesignTokenLintTest`
 * 都是这一路），本条沿用同一惯例。
 *
 * ## 钉的是**语义不变量**，不是行号
 *
 * 行号钉不得（改一行注释全线飘），本条全部按"锚点行的先后顺序 + 出现次数"表述：
 *
 * 1. **读缓存必须早于发网络**：否则 stale-while-revalidate 退化成"只有写没有读"，
 *    缓存永远是死代码，而测试全绿。
 * 2. **读写共用同一个键变量**：写侧若另算一次键，账号/筛选项在途变化就会读写打偏。
 * 3. **键必须带观看者 mid**：`CommentReply.liked/disliked` 随账号变（跨账号隔离）。
 * 4. **恢复必须走 `copy(` 而不是整体重建**：`refresh()` 开头的 `CommentUiState(...)`
 *    重建**必须**保留 `loading = true`。这一条同时服务两件事 ——
 *    (a) `CommentPanel.isInitLoading = loading && items.isEmpty()`，items 非空 ⇒ 骨架
 *        不出来，用户看到的是旧内容而不是转圈；
 *    (b) `CommentPanel.shouldLoadMore` 含 `!uiState.loading`，保持 true ⇒ 列表底部
 *        的自动翻页不会在刷新途中抢跑、把这次首页请求的 reqId 顶掉。
 *    所以整条 `refresh()` 里 `loading =` **只允许出现一次**（就是开头那次）。
 * 5. **写缓存只许发生在 `onSuccess`**：失败路径写进去，下次进来先铺错误页的旧内容。
 * 6. **写缓存必须受 mid 比对保护**：请求在途时换账号，这批 items 的点赞态属于旧账号，
 *    不设防就会把旧账号状态存进旧账号的桶、下次同账号重进照样命中。
 * 7. **`loadMore()` 不许写缓存**：只缓存首页（缓存整段会让内存迅速膨胀，且重进时从
 *    首页 + nextOffset 继续翻就能补齐）。
 * 8. **缓存命中后刷新失败必须把 `error` 置空**：这是 A12 自己引入的新情形的收尾 ——
 *    本仓错误卡只在 `items.isEmpty()` 时渲染，有旧内容在场时写了也看不见，
 *    只会变成暗雷字段。上游同场景同样置空。
 *
 * ## 合成正反例（W21）
 *
 * 四条反例全部**编译通过**后才算有效（编译失败的红是编译错误、不是断言失败，证明力为零）：
 *
 * | 反例 | 打中的断言 |
 * |---|---|
 * | 把「读缓存 + 恢复」整段挪到 `viewModelScope.launch` **之后** | `refreshReadsCacheBeforeLaunchingNetwork` + 另 2 条 |
 * | `if (mid == currentMid)` → `if (true)` | `cacheWriteOnlyOnSuccessAndOnlyWhenMidUnchanged` |
 * | 恢复的 `copy(` 里加一行 `loading = false` | `cacheRestoreLeavesLoadingAlone` |
 * | `error = if (cachedSnapshot == null)` → `if (true)` | `staleContentRefreshFailureSuppressesStaleError` |
 *
 * 脚本 `_r7/verify-a12-guard.sh`，四条还原后 diff 均 `IDENTICAL`。
 *
 * ⚠️ 反例 1 我改过两版：第一版"删掉 `.get(cacheKey)?.let {` 那一行"花括号失衡、编译失败；
 * 第二版"整段删除"又因为 `onFailure` 还引用 `cachedSnapshot` 而编译失败。
 * 最后改成"**整段挪到 launch 之后**"才同时满足"仍能编译"与"真的违反时序不变量"。
 */
class CommentPageCacheWiringGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val viewModelFile: File by lazy {
        File(
            repoRoot,
            "feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/CommentViewModel.kt"
        )
    }

    private val lines: List<String> by lazy {
        assertTrue(
            "找不到 CommentViewModel.kt（${viewModelFile.path}）—— 此时断言无意义。",
            viewModelFile.isFile
        )
        viewModelFile.readLines()
    }

    /**
     * 取一个成员函数的函数体：从声明行起，到下一个**同级成员声明**之前为止。
     *
     * 刻意不做花括号配对 —— 本文件里有字符串模板，naive 数括号会错；而"下一个同级
     * `fun`"这个边界对本文件足够稳定，且取错时下面每条断言都会因找不到锚点而**显式**
     * 失败（不会假绿）。
     */
    private fun memberBody(name: String): List<String> {
        val declPattern = Regex("""^\s{4}(?:private\s+|internal\s+)?fun\s+$name\s*\(""")
        val start = lines.indexOfFirst { declPattern.containsMatchIn(it) }
        assertTrue("CommentViewModel.kt 里找不到 fun $name( —— 此时断言无意义。", start >= 0)
        val nextDecl = Regex("""^\s{4}(?:private\s+|internal\s+)?(?:fun|val|var)\s""")
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (nextDecl.containsMatchIn(lines[i])) {
                end = i
                break
            }
        }
        return lines.subList(start, end).toList()
    }

    private fun List<String>.indexOfAnchor(predicate: (String) -> Boolean): Int =
        indexOfFirst { predicate(it) }

    @Test
    fun refreshReadsCacheBeforeLaunchingNetwork() {
        val body = memberBody("refresh")

        val keyAt = body.indexOfAnchor { it.contains("commentPageCacheKey(") }
        val getAt = body.indexOfAnchor { it.contains("sharedCommentPageCache.get(") }
        val launchAt = body.indexOfAnchor { it.contains("viewModelScope.launch") }
        val fetchAt = body.indexOfAnchor { it.contains("repo.fetchMainPage(") }

        assertTrue("refresh() 里必须算缓存键（A12 接线缺失）", keyAt >= 0)
        assertTrue("refresh() 里必须读缓存（缺失 ⇒ 缓存是死代码）", getAt >= 0)
        assertTrue("refresh() 里必须发网络请求（stale-while-revalidate 不是只读缓存）", launchAt >= 0)
        assertTrue("refresh() 里必须调 repo.fetchMainPage", fetchAt >= 0)

        assertTrue(
            "读缓存必须早于发起网络请求（key@$keyAt get@$getAt launch@$launchAt）—— " +
                "晚于 launch 就退化成「只有写没有读」，缓存永远打不中而测试全绿。",
            keyAt < launchAt && getAt < launchAt
        )
        assertTrue("fetchMainPage 必须在 launch 体内", launchAt < fetchAt)
    }

    @Test
    fun readAndWriteShareOneKeyVariable() {
        val body = memberBody("refresh")

        assertTrue(
            "读侧必须用 cacheKey 这个变量（写成字面量/另算一次都会读写打偏）",
            body.any { it.contains("sharedCommentPageCache.get(cacheKey)") }
        )
        assertTrue(
            "写侧必须复用同一个 cacheKey 变量",
            body.any { it.contains("sharedCommentPageCache.put(") } &&
                body.any { it.contains("key = cacheKey") }
        )
        assertTrue(
            "缓存键必须带上观看者 mid（CommentReply.liked/disliked 随账号变，跨账号隔离）",
            body.any { it.contains("viewerMid = currentMid") }
        )
    }

    /**
     * `loading` 的三段语义，逐段钉：
     *
     * ① `launch` 之前：`loading = true`（stale-while-revalidate 的前提，见下）；
     * ② 读缓存 → `launch` 之间的恢复段：**一个 `loading` 都不许碰**；
     * ③ `onFailure` 里：`loading = false`（本来就有的，不算违规）。
     *
     * ② 是关键。保持 `loading = true` 同时服务两件事：
     * - `CommentPanel.isInitLoading = loading && items.isEmpty()` —— items 非空时骨架
     *   本来就不会出来，用户看到的是旧内容而不是转圈；
     * - `CommentPanel.shouldLoadMore` 含 `!uiState.loading` —— 提前置 false 会让列表
     *   底部的自动翻页在刷新途中抢跑、把这次首页请求的 reqId 顶掉。
     *
     * 这条断言同时钉住"恢复走 `copy(` 而不是整体重建"：整体重建必然要重写 `loading`。
     */
    @Test
    fun cacheRestoreLeavesLoadingAlone() {
        val body = memberBody("refresh")

        val getAt = body.indexOfAnchor { it.contains("sharedCommentPageCache.get(cacheKey)") }
        val launchAt = body.indexOfAnchor { it.contains("viewModelScope.launch") }
        assertTrue("refresh() 必须读缓存", getAt >= 0)
        assertTrue("refresh() 必须用 launch 发请求", launchAt >= 0)

        val preLaunch = body.subList(0, launchAt)
        assertTrue(
            "refresh() 在发起网络前必须把 loading 置 true —— 置 false 会让" +
                "CommentPanel.shouldLoadMore 立刻放行底部自动翻页，把这次首页请求顶掉。",
            preLaunch.any { it.contains("loading = true") }
        )

        val restoreWindow = body.subList(getAt, launchAt)
        val leaked = restoreWindow.filter { it.contains("loading =") }
        assertTrue(
            "恢复缓存那一段不许碰 loading（实测命中 ${leaked.size} 处：${leaked}）—— " +
                "① CommentPanel.isInitLoading = loading && items.isEmpty()，items 非空时" +
                "骨架本来就不会出来，无需置 false；" +
                "② CommentPanel.shouldLoadMore 含 !uiState.loading，提前置 false 会让" +
                "列表底部的自动翻页在刷新途中抢跑、把这次首页请求的 reqId 顶掉。",
            leaked.isEmpty()
        )
    }

    /** 恢复必须用 `_uiState.update { cur -> cur.copy( … ) }`，夹在读缓存与 launch 之间。 */
    @Test
    fun cacheRestoreUsesCopyBetweenReadAndLaunch() {
        val body = memberBody("refresh")

        val getAt = body.indexOfAnchor { it.contains("sharedCommentPageCache.get(cacheKey)") }
        val copyAt = body.indexOfAnchor { it.contains("cur.copy(") }
        val launchAt = body.indexOfAnchor { it.contains("viewModelScope.launch") }
        assertTrue("refresh() 必须读缓存", getAt >= 0)
        assertTrue("refresh() 必须用 launch 发请求", launchAt >= 0)

        assertTrue(
            "恢复缓存必须走 cur.copy(（copy@$copyAt）—— 夹在读缓存(get@$getAt)与 " +
                "launch(@$launchAt) 之间才是「读到时立刻铺上去」。",
            copyAt > getAt && copyAt < launchAt
        )
        // 快照的每个首屏字段都要被写回，缺一个就是一次静默回退
        val restoreWindow = body.subList(getAt, launchAt)
        listOf(
            "title = cached.title",
            "count = cached.count",
            "canSwitchSort = cached.canSwitchSort",
            "filterTags = cached.filterTags",
            "selectedFilter = cached.selectedFilter",
            "items = cached.items",
            "nextOffset = cached.nextOffset",
            "endText = cached.endText"
        ).forEach { field ->
            assertTrue(
                "恢复缓存时漏写 $field —— 缺字段不会报错，只会让重进评论区时那一项" +
                    "悄悄回到 CommentUiState 默认值（用户看得见的回退）。",
                restoreWindow.any { it.contains(field) }
            )
        }
    }

    /** 写缓存只许发生在 onSuccess 内，且必须受 mid 比对保护。 */
    @Test
    fun cacheWriteOnlyOnSuccessAndOnlyWhenMidUnchanged() {
        val body = memberBody("refresh")

        val successAt = body.indexOfAnchor { it.contains("onSuccess = { page ->") }
        val putAt = body.indexOfAnchor { it.contains("sharedCommentPageCache.put(") }
        val failureAt = body.indexOfAnchor { it.contains("onFailure =") }
        assertTrue("refresh() 的成功分支必须存在", successAt >= 0)
        assertTrue("refresh() 必须写缓存（只读不写 ⇒ 缓存永远是空的）", putAt >= 0)
        assertTrue("refresh() 的失败分支必须存在", failureAt >= 0)

        assertTrue(
            "写缓存必须发生在 onSuccess 内（success@$successAt put@$putAt failure@$failureAt）—— " +
                "失败路径写进去，下次重进会先铺一个错误页的旧内容。",
            successAt < putAt && putAt < failureAt
        )

        val guardWindow = body.subList(successAt, putAt)
        assertTrue(
            "写缓存必须受 mid 比对保护（if (mid == currentMid)）：请求在途时换账号，" +
                "这批 items 的 liked/disliked 属于旧账号，不设防就会把旧账号状态存进桶里、" +
                "下次同账号重进照样命中。",
            guardWindow.any { it.contains("mid == currentMid") }
        )
    }

    /**
     * 缓存命中后刷新失败：`error` 必须置空。
     *
     * 这条是 A12 **自己引入的新情形**的收尾，不是上游有的：本仓
     * `CommentPanel.kt:361` 的错误卡只在 `items.isEmpty()` 时渲染 ——
     * 缓存已把旧内容铺上时它**根本不会显示**。此时若照旧把 `error` 写进去，
     * 它就变成一个暗雷字段：哪一天 `items` 变空，这次早已过期的刷新失败会突然冒出来。
     * 上游同场景（`repliesError = if (cached != null) null else …`）也是显式置空。
     *
     * 注意这条**不是**"刷新失败不提示"——无缓存命中时（`cachedSnapshot == null`）
     * 错误照旧写入并显示，行为与 A12 之前完全一致。
     */
    @Test
    fun staleContentRefreshFailureSuppressesStaleError() {
        val body = memberBody("refresh")

        val failureAt = body.indexOfAnchor { it.contains("onFailure =") }
        assertTrue("refresh() 的失败分支必须存在", failureAt >= 0)
        val failureWindow = body.subList(failureAt, body.size)

        assertTrue(
            "缓存命中后刷新失败必须把 error 置空（条件锚在 cachedSnapshot == null）—— " +
                "CommentPanel 的错误卡只在 items.isEmpty() 时渲染（:361），" +
                "有旧内容在场时写了也看不到，只会变成暗雷字段。",
            failureWindow.any { it.contains("cachedSnapshot == null") }
        )
        assertTrue(
            "置空的那一支必须是裸 null（不是空串 —— 空串会让 isNullOrBlank() 也当成无错误，" +
                "但 StateMessageCard 那条路走的是 error.orEmpty()，语义不一致）。",
            failureWindow.any { it.trim() == "null" }
        )
        // 反假绿：确认失败分支里真的还在给 error 赋值，不是把整行删掉了事
        assertTrue(
            "onFailure 仍必须给 error 赋值（无缓存命中时错误照旧要显示）",
            failureWindow.any { it.contains("error =") }
        )
    }

    /** 只缓存首页：翻页路径不许写缓存。 */
    @Test
    fun loadMoreDoesNotWriteCache() {
        val body = memberBody("loadMore")
        assertTrue(
            "loadMore() 不许写首页缓存 —— 只缓存首页（缓存整段会让内存迅速膨胀，" +
                "且重进时从首页 + nextOffset 继续翻就能补齐）。",
            body.none { it.contains("sharedCommentPageCache") }
        )
        // 反假绿：确认真的取到了 loadMore 的函数体
        assertTrue("loadMore() 函数体应含 repo.fetchMainPage", body.any { it.contains("repo.fetchMainPage(") })
    }
}
