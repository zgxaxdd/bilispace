package com.naaammme.bbspace.core.swipe.session

import com.naaammme.bbspace.core.model.VideoSrc
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.swipe.SwipeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态机不变式（任务书 §4.3 / SW-02）：
 * 1. `Bootstrapping` 期间种子随身（类型上不可能"无封面可显"）且锚点流不发布（迁移期
 *    UI 判据不变）；流到达（含失败传空）必落 `Ready`。
 * 2. `retry()` 后必回 `Ready`。
 * 3. `exit()` 清空锚点/队列/尾态/两个幂等键。
 * 4. `restore()` 只灌状态直落 `Ready`（零网络由签名保证，这里断言状态形状）。
 */
class SwipeSessionControllerTest {

    private fun entry(
        aid: Long,
        title: String = "标题$aid",
        cover: String = "https://i0.hdslb.com/bfs/archive/$aid.jpg",
        author: String? = "UP$aid",
        bvid: String? = null,
    ): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = aid, cid = aid * 10, bvid = bvid),
        bvid = bvid.orEmpty(),
        title = title,
        cover = cover,
        author = author,
        durationText = "12:34"
    )

    // ── 不变式 1：Bootstrapping ─────────────────────────────────────────────

    @Test
    fun `bootstrapping carries seed but keeps anchor flow null`() {
        val controller = SwipeSessionController()
        val seed = entry(aid = 1, title = "", cover = "", author = null)
        controller.enterBootstrapping(seed, SwipeEntryVia.StoryFeed)

        val state = controller.state.value
        assertTrue(state is SwipeSessionState.Bootstrapping)
        // 类型保证：Bootstrapping 一定带着种子（"Bootstrapping 无封面可显"不可能发生）
        assertEquals(seed, (state as SwipeSessionState.Bootstrapping).anchor)
        // 迁移期 UI 判据：锚点流在 Ready 前不发布（pager 启用判据依赖锚点非空）
        assertNull(controller.anchor.value)
        assertTrue(controller.queue.value.isEmpty())
    }

    @Test
    fun `seedArrived publishes anchor with stream info and becomes ready`() {
        val controller = SwipeSessionController()
        val seed = entry(aid = 1, title = "", cover = "", author = null, bvid = "BV1xx")
        controller.enterBootstrapping(seed, SwipeEntryVia.StoryFeed)

        val stream = listOf(entry(aid = 1, title = "种子", cover = "c1", author = "UP1", bvid = "BV1xx"), entry(aid = 2))
        controller.seedArrived(stream)

        val state = controller.state.value
        assertTrue(state is SwipeSessionState.Ready)
        assertEquals(0, (state as SwipeSessionState.Ready).page)
        // 锚点信息取流内同视频条目（旧 VM bootstrap 的同款查找语义）
        assertEquals("种子", controller.anchor.value?.title)
        assertEquals("c1", controller.anchor.value?.cover)
        assertEquals(1, controller.queue.value.size)
    }

    @Test
    fun `seedArrived with empty stream degrades to ready with single video`() {
        val controller = SwipeSessionController()
        val seed = entry(aid = 1)
        controller.enterBootstrapping(seed, SwipeEntryVia.StoryFeed)

        controller.seedArrived(emptyList())

        val state = controller.state.value
        assertTrue(state is SwipeSessionState.Ready)
        assertEquals(0, (state as SwipeSessionState.Ready).size)
        // 锚点仍发布（降级为"单视频全屏"，返回可重进——与旧 VM 行为一致）。
        // 流内没有同视频条目 → 展示信息置空（旧版 title ?: "".orEmpty() 的语义）。
        assertEquals(seed.copy(title = "", cover = "", author = null), controller.anchor.value)
    }

    @Test
    fun `seedArrived only applies while bootstrapping`() {
        val controller = SwipeSessionController()
        controller.seedArrived(listOf(entry(aid = 9)))
        assertTrue(controller.state.value is SwipeSessionState.Idle)
        assertNull(controller.anchor.value)
    }

    @Test
    fun `enterReady publishes anchor immediately with seed queue`() {
        val controller = SwipeSessionController()
        val seed = entry(aid = 1)
        controller.enterReady(seed, SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2), entry(aid = 3)))

        assertTrue(controller.state.value is SwipeSessionState.Ready)
        assertEquals(seed, controller.anchor.value)
        assertEquals(2, controller.queue.value.size)
    }

    // ── 接力与补货 ──────────────────────────────────────────────────────────

    @Test
    fun `mergeRelates is no-op without published anchor and idempotent after ready`() {
        val controller = SwipeSessionController()
        controller.mergeRelates(listOf(entry(aid = 1)))
        assertTrue(controller.queue.value.isEmpty())

        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = emptyList())
        val incoming = listOf(entry(aid = 2), entry(aid = 2))
        controller.mergeRelates(incoming)
        val once = controller.queue.value
        assertEquals(1, once.size)
        assertEquals(2L, once.single().target.aid)

        // 幂等：同批重复到达无害（appendSwipeEntries 的去重）
        controller.mergeRelates(incoming)
        assertEquals(once.size, controller.queue.value.size)
    }

    @Test
    fun `refill lifecycle extends exhausts and returns to ready`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2)))
        controller.pageChanged(1)

        controller.refillStarted()
        assertEquals(SwipeSessionState.Extending, controller.state.value)
        assertEquals(com.naaammme.bbspace.core.swipe.SwipeQueueTail.Extending, controller.tail.value)

        // 粘滞态：翻页不清掉（补货尾态跨翻页保留是既有行为）
        controller.pageChanged(0)
        assertEquals(SwipeSessionState.Extending, controller.state.value)

        controller.refillResult(appended = 0)
        assertEquals(SwipeSessionState.Exhausted, controller.state.value)
        assertEquals(com.naaammme.bbspace.core.swipe.SwipeQueueTail.Exhausted, controller.tail.value)

        // 下一轮补到货：回 Ready 并带回页号
        controller.refillStarted()
        controller.refillResult(appended = 3)
        assertEquals(SwipeSessionState.Ready(page = 0, size = 1), controller.state.value)
        assertEquals(com.naaammme.bbspace.core.swipe.SwipeQueueTail.Idle, controller.tail.value)
    }

    @Test
    fun `pageChanged tracks page inside ready`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2)))
        controller.pageChanged(1)
        assertEquals(SwipeSessionState.Ready(page = 1, size = 1), controller.state.value)
        // 负页号忽略
        controller.pageChanged(-1)
        assertEquals(SwipeSessionState.Ready(page = 1, size = 1), controller.state.value)
    }

    // ── 不变式 2：retry ─────────────────────────────────────────────────────

    @Test
    fun `playbackFailed then retry returns to ready regardless of retryable`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2)))
        controller.pageChanged(1)

        controller.playbackFailed(key = "BV1xx", retryable = false)
        assertEquals(SwipeSessionState.PlaybackFailed(key = "BV1xx", retryable = false), controller.state.value)
        controller.retry()
        assertEquals(SwipeSessionState.Ready(page = 1, size = 1), controller.state.value)

        controller.playbackFailed(key = "BV1xx", retryable = true)
        controller.retry()
        assertEquals(SwipeSessionState.Ready(page = 1, size = 1), controller.state.value)
    }

    @Test
    fun `retry without failure is a no-op`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = emptyList())
        controller.retry()
        assertEquals(SwipeSessionState.Ready(page = 0, size = 0), controller.state.value)
    }

    // ── 不变式 3：exit 清空一切 ─────────────────────────────────────────────

    @Test
    fun `exit clears anchor queue tail and both idempotence stores`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2)))
        controller.noteAutoAdvanced("BV1xx")
        controller.refillStarted()

        assertTrue(controller.markPrefetched("1#10"))
        assertFalse(controller.markPrefetched("1#10")) // 同一支只预取一次

        controller.exit()

        assertEquals(SwipeSessionState.Idle, controller.state.value)
        assertNull(controller.anchor.value)
        assertTrue(controller.queue.value.isEmpty())
        assertEquals(com.naaammme.bbspace.core.swipe.SwipeQueueTail.Idle, controller.tail.value)
        assertNull(controller.lastAutoAdvancedKey.value)
        // 预取去重表也清了：同一支在新会话里允许重新预取
        assertTrue(controller.markPrefetched("1#10"))
    }

    @Test
    fun `re-enter resets session scoped keys`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = listOf(entry(aid = 2)))
        controller.noteAutoAdvanced("BV1xx")
        controller.markPrefetched("1#10")

        controller.enterReady(entry(aid = 5), SwipeEntryVia.DetailPullDown, seedQueue = emptyList())

        assertNull(controller.lastAutoAdvancedKey.value)
        assertTrue(controller.markPrefetched("1#10"))
        assertEquals(entry(aid = 5), controller.anchor.value)
    }

    // ── 不变式 4：restore（进程重建恢复）────────────────────────────────────

    @Test
    fun `restore lands directly in ready without bootstrap transit`() {
        val controller = SwipeSessionController()
        val anchor = entry(aid = 1, bvid = "BV1anchor")
        val queue = listOf(entry(aid = 2), entry(aid = 3), entry(aid = 4))

        controller.restore(page = 2, anchor = anchor, queue = queue)

        assertEquals(SwipeSessionState.Ready(page = 2, size = 3), controller.state.value)
        assertEquals(anchor, controller.anchor.value)
        assertEquals(queue, controller.queue.value)
        assertEquals(com.naaammme.bbspace.core.swipe.SwipeQueueTail.Idle, controller.tail.value)
        // 快照可生成（进程重建持久化的数据来源）
        val snap = controller.snapshot()
        assertNotNull(snap)
        assertEquals(2, snap?.page)
        assertEquals(3, snap?.queue?.size)
    }

    @Test
    fun `snapshot is null when no session`() {
        assertNull(SwipeSessionController().snapshot())
    }

    // ── 快照编解码往返 ───────────────────────────────────────────────────────

    @Test
    fun `snapshot json round trip preserves identity fields`() {
        val src = VideoSrc(
            from = "from_feed",
            fromSpmid = "333.999",
            trackId = "track-1",
            reportFlowData = """{"jpg":1}"""
        )
        val anchor = SwipeEntry(
            target = VideoTarget.Ugc(aid = 17, cid = 170, bvid = "BV1anchor", src = src),
            bvid = "BV1anchor",
            title = "标题\"带引号\"与\n换行",
            cover = "https://i0.hdslb.com/bfs/archive/x.jpg@1280w_720h",
            author = null,
            durationText = null
        )
        val queue = listOf(
            SwipeEntry(
                target = VideoTarget.Ugc(aid = 99, cid = 0),
                bvid = "",
                title = "只有 aid 的 relate 卡",
                cover = "c",
                author = "UP",
                durationText = "1:02:03"
            )
        )
        val snapshot = SwipeSessionSnapshot(
            via = SwipeEntryVia.StoryFeed,
            page = 7,
            anchor = anchor,
            queue = queue
        )

        val parsed = parseSwipeSessionSnapshot(swipeSessionSnapshotJson(snapshot))

        assertNotNull(parsed)
        assertEquals(SwipeEntryVia.StoryFeed, parsed?.via)
        assertEquals(7, parsed?.page)
        assertEquals(anchor, parsed?.anchor)
        assertEquals(queue, parsed?.queue)
        // src 无损（openSwipeTarget 的短路判据依赖它）
        assertEquals(src, parsed?.anchor?.target?.src)
    }

    @Test
    fun `snapshot parse rejects garbage and identity-less entries`() {
        assertNull(parseSwipeSessionSnapshot("not json"))
        assertNull(parseSwipeSessionSnapshot("{}"))
        // anchor 缺失 → null
        assertNull(
            parseSwipeSessionSnapshot("""{"via":"StoryFeed","page":0,"queue":[]}""")
        )
        // 队列里身份字段全缺的条目被丢掉，anchor 有效则整体可解析
        val rescued = parseSwipeSessionSnapshot(
            """{"via":"DetailPullDown","page":1,
                "anchor":{"aid":1,"cid":10,"bvid":"BV1","title":"t","cover":"c"},
                "queue":[{"title":"无身份"},{"aid":2,"cid":20,"bvid":"","title":"ok"}]}"""
        )
        assertNotNull(rescued)
        assertEquals(1, rescued?.queue?.size)
        assertEquals(2L, rescued?.queue?.first()?.target?.aid)
    }

    @Test
    fun `snapshot worthiness follows state semantics`() {
        val controller = SwipeSessionController()
        controller.enterReady(entry(aid = 1), SwipeEntryVia.DetailPullDown, seedQueue = emptyList())
        assertTrue(controller.state.value.isSnapshotWorthy())
        controller.exit()
        assertFalse(controller.state.value.isSnapshotWorthy())

        controller.enterBootstrapping(entry(aid = 1), SwipeEntryVia.StoryFeed)
        assertFalse(controller.state.value.isSnapshotWorthy())
        controller.seedArrived(listOf(entry(aid = 2)))
        assertTrue(controller.state.value.isSnapshotWorthy())
        controller.playbackFailed(key = "k", retryable = true)
        assertFalse(controller.state.value.isSnapshotWorthy())
    }

    @Test
    fun `seedArrived anchor lookup uses content identity not string key`() {
        // 回归锚（C7）：seed 的 bvid 与流内条目的 bvid 表示可能不同步，
        // 查找必须走 isCurrentVideo（aid 优先）而不是字符串比较。
        val seed = SwipeEntry(
            target = VideoTarget.Ugc(aid = 42, cid = 420, bvid = null),
            bvid = "", // aid-only 条目（relate 卡形态）
            title = "", cover = "", author = null, durationText = null
        )
        val controller = SwipeSessionController()
        controller.enterBootstrapping(seed, SwipeEntryVia.StoryFeed)
        val streamEntry = entry(aid = 42, title = "同支", bvid = "BV1real")
        controller.seedArrived(listOf(entry(aid = 41, bvid = "BV1other"), streamEntry))

        assertEquals("同支", controller.anchor.value?.title)
        // 服务端补齐的 bvid 不回写进锚点（与旧 VM 一致：锚点 target 原样保留）
        assertNotEquals("BV1real", controller.anchor.value?.target?.bvid)
    }
}
