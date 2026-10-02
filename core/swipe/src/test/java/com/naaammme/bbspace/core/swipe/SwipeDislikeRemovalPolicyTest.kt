package com.naaammme.bbspace.core.swipe

import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不感兴趣」退队判据（SW-24 验收：当前页被移除只前进一次、不跳两支；
 * 锚点不可移除；已滑过的前方条目被移除时当前页左移）。
 */
class SwipeDislikeRemovalPolicyTest {

    private fun entry(aid: Long): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = aid, cid = aid * 10),
        bvid = "BV$aid",
        title = "t$aid",
        cover = "c$aid",
        author = "up",
        durationText = null
    )

    @Test
    fun `removing current page keeps page index so it advances exactly one`() {
        val queue = listOf(entry(1), entry(2), entry(3), entry(4))
        // 当前在第 2 页（pages 坐标）= queue[1] = aid 2
        val removal = SwipeDislikeRemovalPolicy.remove(
            queue = queue,
            anchor = entry(0),
            target = entry(2),
            currentPage = 2
        )
        assertTrue(removal.removed)
        assertEquals(listOf(1L, 3L, 4L), removal.queue.map { it.target.aid })
        // 页号不变 → 同一页现在是 aid 3（前进一支，不跳两支）
        assertFalse(removal.shiftCurrentPageDown)
    }

    @Test
    fun `removing an entry ahead keeps things as-is`() {
        val queue = listOf(entry(1), entry(2), entry(3))
        val removal = SwipeDislikeRemovalPolicy.remove(
            queue = queue,
            anchor = entry(0),
            target = entry(3),
            currentPage = 1
        )
        assertTrue(removal.removed)
        assertEquals(listOf(1L, 2L), removal.queue.map { it.target.aid })
        assertFalse(removal.shiftCurrentPageDown)
    }

    @Test
    fun `removing a passed entry shifts current page down`() {
        val queue = listOf(entry(1), entry(2), entry(3))
        // 当前在第 3 页（queue[2]=aid 3），移除已滑过的 aid 1（第 1 页）
        val removal = SwipeDislikeRemovalPolicy.remove(
            queue = queue,
            anchor = entry(0),
            target = entry(1),
            currentPage = 3
        )
        assertTrue(removal.removed)
        assertEquals(listOf(2L, 3L), removal.queue.map { it.target.aid })
        // 当前页左移一位，仍然落在 aid 3 上（画面不跳）
        assertTrue(removal.shiftCurrentPageDown)
    }

    @Test
    fun `anchor cannot be removed and unknown targets are no-ops`() {
        val queue = listOf(entry(1))
        // 锚点：不可移除（摘掉等于换会话）
        val anchorRemoval = SwipeDislikeRemovalPolicy.remove(queue, entry(0), entry(0), currentPage = 0)
        assertFalse(anchorRemoval.removed)
        assertEquals(queue, anchorRemoval.queue)
        // 不在队列里：原样返回
        val missing = SwipeDislikeRemovalPolicy.remove(queue, entry(0), entry(99), currentPage = 1)
        assertFalse(missing.removed)
        assertEquals(queue, missing.queue)
    }
}
