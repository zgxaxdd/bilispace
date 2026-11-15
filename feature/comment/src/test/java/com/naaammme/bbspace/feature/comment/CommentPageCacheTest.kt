package com.naaammme.bbspace.feature.comment

import com.naaammme.bbspace.core.model.COMMENT_FILTER_ALL
import com.naaammme.bbspace.core.model.CommentFilterTag
import com.naaammme.bbspace.core.model.CommentPage
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentSort
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A12 评论首页进程级 LRU 缓存 + stale-while-revalidate 的纯逻辑测试。
 *
 * 覆盖三块：
 * ① 分桶键（含**跨账号隔离**与空筛选项归一）；
 * ② 快照字段覆盖（缺字段不会报错、只会让重进时那一项悄悄回默认值，所以逐字段钉）；
 * ③ LRU / 淘汰 / 拒绝空列表 / 清除。
 *
 * ViewModel 接线（读在 launch 前、写只在 onSuccess、写受 mid 比对保护）单独由
 * `CommentPageCacheWiringGuardTest` 钉 —— 那需要 3 个 mock 依赖，本仓没有 mock 库，
 * 按仓库既有惯例走源码结构断言。
 */
class CommentPageCacheTest {

    private val subject = CommentSubject(oid = 1001L, type = 1L)

    private fun reply(rpid: Long, liked: Boolean = false) = CommentReply(
        rpid = rpid,
        message = "评论 $rpid",
        user = CommentUser(mid = 7L, name = "up"),
        liked = liked,
        disliked = false
    )

    private fun page(
        subject: CommentSubject = this.subject,
        title: String = "评论 42",
        count: Long = 42L,
        sort: CommentSort = CommentSort.HOT,
        canSwitchSort: Boolean = true,
        filterTags: List<CommentFilterTag> = listOf(CommentFilterTag(COMMENT_FILTER_ALL)),
        selectedFilter: String = COMMENT_FILTER_ALL,
        items: List<CommentReply> = listOf(reply(1L), reply(2L)),
        nextOffset: String? = "offset-2",
        endText: String? = null
    ) = CommentPage(
        subject = subject,
        title = title,
        count = count,
        sort = sort,
        canSwitchSort = canSwitchSort,
        filterTags = filterTags,
        selectedFilter = selectedFilter,
        items = items,
        nextOffset = nextOffset,
        endText = endText
    )

    private fun snapshot(
        title: String = "评论 42",
        count: Long = 42L,
        sort: CommentSort = CommentSort.HOT,
        canSwitchSort: Boolean = true,
        filterTags: List<CommentFilterTag> = listOf(CommentFilterTag(COMMENT_FILTER_ALL)),
        selectedFilter: String = COMMENT_FILTER_ALL,
        items: List<CommentReply> = listOf(reply(1L)),
        nextOffset: String? = "offset-2",
        endText: String? = null
    ) = CommentPageSnapshot(
        title = title,
        count = count,
        sort = sort,
        canSwitchSort = canSwitchSort,
        filterTags = filterTags,
        selectedFilter = selectedFilter,
        items = items,
        nextOffset = nextOffset,
        endText = endText
    )

    // ── ① 分桶键 ────────────────────────────────────────────────────────────

    @Test
    fun keySeparatesSubjectSortFilterAndViewer() {
        val base = commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, 1L)
        assertNotEquals(base, commentPageCacheKey(subject.copy(oid = 1002L), CommentSort.HOT, COMMENT_FILTER_ALL, 1L))
        assertNotEquals(base, commentPageCacheKey(subject.copy(type = 17L), CommentSort.HOT, COMMENT_FILTER_ALL, 1L))
        assertNotEquals(base, commentPageCacheKey(subject, CommentSort.TIME, COMMENT_FILTER_ALL, 1L))
        assertNotEquals(base, commentPageCacheKey(subject, CommentSort.HOT, "视频", 1L))
        assertNotEquals(base, commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, 2L))
    }

    /**
     * 跨账号隔离是**正确性**要求，不是洁癖：`CommentReply.liked` / `disliked` 随账号变，
     * 键里不带 mid 就会让换账号后先看到上一个账号的点赞态。
     *
     * 这里不只比键字符串，还真的按桶存取一遍 —— 键不同但缓存不隔离，一样是泄漏。
     */
    @Test
    fun keyIsolatesAccounts() {
        val cache = CommentPageCache()
        val accountA = commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, viewerMid = 111L)
        val accountB = commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, viewerMid = 222L)
        val loggedOut = commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, viewerMid = 0L)

        assertNotEquals(accountA, accountB)
        assertNotEquals(accountA, loggedOut)
        assertNotEquals(accountB, loggedOut)

        cache.put(accountA, snapshot(items = listOf(reply(1L, liked = true))))
        // 换账号 / 未登录都必须读不到上一个账号那一桶
        assertNull("换账号后不得读到上一个账号的点赞态", cache.get(accountB))
        assertNull("未登录桶必须独立", cache.get(loggedOut))
        assertTrue(cache.get(accountA)?.items?.single()?.liked == true)
    }

    /**
     * 空筛选项必须归一到「全部」：`CommentRepository.mapPage` 写回
     * `selectedFilter = filterTag.ifBlank { COMMENT_FILTER_ALL }`，读侧若不归一，
     * 「传空串」与「传全部」会裂成两个桶，缓存永远打不中。
     */
    @Test
    fun keyNormalizesBlankFilter() {
        assertEquals(
            commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, 1L),
            commentPageCacheKey(subject, CommentSort.HOT, "", 1L)
        )
        assertEquals(
            commentPageCacheKey(subject, CommentSort.HOT, COMMENT_FILTER_ALL, 1L),
            commentPageCacheKey(subject, CommentSort.HOT, "   ", 1L)
        )
    }

    // ── ② 快照字段覆盖 ──────────────────────────────────────────────────────

    /**
     * 快照必须把 `CommentPage` 的首屏字段**一个不漏**地搬过去。
     *
     * 这条断言防的是"缺字段不报错"这类静默回退：少搬一项，重进评论区时那一项就悄悄
     * 回到 `CommentUiState` 的默认值（标题变"评论"、排序表头重置、筛选项标签消失、
     * 到底文案不见）—— 全是用户看得见的，且没有任何测试会因此报错。
     */
    @Test
    fun snapshotCopiesEveryFirstPageField() {
        val src = page(
            title = "评论 999",
            count = 999L,
            sort = CommentSort.TIME,
            canSwitchSort = false,
            filterTags = listOf(CommentFilterTag(COMMENT_FILTER_ALL), CommentFilterTag("视频")),
            selectedFilter = "视频",
            items = listOf(reply(11L, liked = true), reply(12L, liked = false)),
            nextOffset = "next-offset",
            endText = "没有更多评论"
        )
        val snap = src.toSnapshot()

        assertEquals("评论 999", snap.title)
        assertEquals(999L, snap.count)
        assertEquals(CommentSort.TIME, snap.sort)
        assertFalse(snap.canSwitchSort)
        assertEquals(listOf(CommentFilterTag(COMMENT_FILTER_ALL), CommentFilterTag("视频")), snap.filterTags)
        assertEquals("视频", snap.selectedFilter)
        assertEquals(listOf(reply(11L, liked = true), reply(12L, liked = false)), snap.items)
        assertEquals("next-offset", snap.nextOffset)
        assertEquals("没有更多评论", snap.endText)
    }

    /** `endText` / `nextOffset` 可为 null（到底了 / 还有下一页），不得被塞成空串。 */
    @Test
    fun snapshotKeepsNullableFieldsNull() {
        val snap = page(nextOffset = null, endText = null).toSnapshot()
        assertNull(snap.nextOffset)
        assertNull(snap.endText)
    }

    // ── ③ LRU / 淘汰 / 拒绝写入 ─────────────────────────────────────────────

    @Test
    fun getReturnsWrittenSnapshot() {
        val cache = CommentPageCache()
        val snap = snapshot()
        cache.put("k", snap)
        assertEquals(snap, cache.get("k"))
        assertEquals(1, cache.size())
    }

    @Test
    fun getReturnsNullForMissingKey() {
        assertNull(CommentPageCache().get("nope"))
    }

    @Test
    fun getPromotesRecency() {
        val cache = CommentPageCache(maxEntries = 2)
        cache.put("a", snapshot(title = "a"))
        cache.put("b", snapshot(title = "b"))
        // 读一次 a，把 a 提到队尾 → 下次淘汰的应该是 b
        assertEquals("a", cache.get("a")?.title)

        cache.put("c", snapshot(title = "c"))
        assertEquals(2, cache.size())
        assertNull("b 应已被淘汰（最久未用）", cache.get("b"))
        assertEquals("a", cache.get("a")?.title)
        assertEquals("c", cache.get("c")?.title)
    }

    @Test
    fun putEvictsOldestUntilWithinCapacity() {
        val cache = CommentPageCache(maxEntries = 3)
        for (i in 1..5) cache.put("k$i", snapshot(title = "k$i"))
        assertEquals(3, cache.size())
        // 只留最后 3 个
        assertNull(cache.get("k1"))
        assertNull(cache.get("k2"))
        assertEquals("k3", cache.get("k3")?.title)
        assertEquals("k4", cache.get("k4")?.title)
        assertEquals("k5", cache.get("k5")?.title)
    }

    @Test
    fun putOverwritesSameKeyInsteadOfGrowing() {
        val cache = CommentPageCache()
        cache.put("k", snapshot(title = "first"))
        cache.put("k", snapshot(title = "second"))
        assertEquals(1, cache.size())
        assertEquals("second", cache.get("k")?.title)
    }

    /**
     * 空列表不写：`refresh()` 失败或返回空页时写进去，下次进来会先铺一个空列表再走
     * 网络，比直接骨架更差 —— 用户会看到"评论区是空的"这一瞬间的误判。
     */
    @Test
    fun putRejectsEmptyItems() {
        val cache = CommentPageCache()
        cache.put("k", snapshot(items = emptyList()))
        assertEquals(0, cache.size())
        assertNull(cache.get("k"))
    }

    @Test
    fun putRejectsBlankKey() {
        val cache = CommentPageCache()
        cache.put("", snapshot())
        cache.put("   ", snapshot())
        assertEquals(0, cache.size())
    }

    @Test
    fun clearEmptiesEverything() {
        val cache = CommentPageCache()
        cache.put("a", snapshot())
        cache.put("b", snapshot())
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    /** 默认容量就是类注释里承诺的那个数（改常量必须改这里，防止注释与实现漂移）。 */
    @Test
    fun defaultCapacityMatchesDocumentedConstant() {
        val cache = CommentPageCache()
        for (i in 1..COMMENT_PAGE_CACHE_MAX_ENTRIES + 3) {
            cache.put("k$i", snapshot(title = "k$i"))
        }
        assertEquals(COMMENT_PAGE_CACHE_MAX_ENTRIES, cache.size())
    }

    /** 并发读写不应抛异常也不应破坏容量上界（多目的地 ViewModel 是真实并发路径）。 */
    @Test
    fun concurrentPutStaysWithinCapacity() {
        val cache = CommentPageCache(maxEntries = 8)
        val threads = (1..8).map { t ->
            Thread {
                for (i in 0 until 200) {
                    cache.put("t$t-$i", snapshot(title = "t$t-$i"))
                    cache.get("t${t}-${i / 2}")
                    cache.size()
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertTrue("容量上界不得被并发写突破", cache.size() <= 8)
    }
}
