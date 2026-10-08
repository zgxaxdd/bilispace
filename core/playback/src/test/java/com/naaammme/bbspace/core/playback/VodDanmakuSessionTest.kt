package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.model.DanmakuItem
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.VodDanmakuRequest
import com.naaammme.bbspace.core.model.VodDanmakuSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕分段会话（[VodDanmakuSession]）的确定性单测。
 *
 * 为什么以前没有：取段直接依赖 `VodDanmakuRepository`（final class，无法 mock），
 * 于是整条"跨段不空窗"的链路**只有编译和会话测试兜底**（见旧版 04/06 文档的"无单测"）。
 * 现在会话通过构造参数注入 `fetchSegment`，这里用假取段器把时序钉死。
 *
 * 覆盖的**用户可见行为**（对齐 BiliPai `DanmakuSegmentWindowPolicy` 的 ±1 窗口语义）：
 * 1. 进入一段时会取"锚点 ±1"三段（不是只取当前段）；
 * 2. 跨段时该段早已在内存 ⇒ **同步上屏**（不出现空窗）；
 * 3. 同一个窗口反复 tick 不重复发请求；
 * 4. 换窗口必须撤销指向旧窗口的在途批次；
 * 5. 锚点段失败要暴露，邻段失败静默（预取是优化）；
 * 6. 换视频清空内存；
 * 7. 总段数由时长决定，末尾不请求越界段。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VodDanmakuSessionTest {

    @Test
    fun `firstWindowRequestsAnchorPlusNeighbours`() = runTest {
        val fetcher = FakeDanmakuFetcher()
        val session = newSession(fetcher)

        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()

        assertEquals(
            "diag state=${session.state.value} requested=${fetcher.requested}",
            setOf(1L, 2L),
            fetcher.requested.toSet()
        )
        assertEquals(1L, session.state.value.window?.id)
        assertEquals(listOf("dm-1"), session.state.value.window?.items?.map { it.content })
    }

    @Test
    fun `crossSegmentHitDisplaysSynchronously`() = runTest {
        val fetcher = FakeDanmakuFetcher()
        val session = newSession(fetcher)
        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()

        session.onProgress(SEGMENT_MS)

        // 关键：不推进调度器，窗口也必须已经换到第 2 段 —— 命中内存 = 零等待（不空窗）
        assertEquals(2L, session.state.value.window?.id)
        assertEquals(listOf("dm-2"), session.state.value.window?.items?.map { it.content })

        advanceUntilIdle()
        assertTrue("新窗口的邻段 3 才需要去取", 3L in fetcher.requested)
    }

    @Test
    fun `repeatedTicksDoNotRefetchTheSameWindow`() = runTest {
        val fetcher = FakeDanmakuFetcher()
        val session = newSession(fetcher)
        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()

        repeat(5) { session.onProgress(0L) }
        advanceUntilIdle()

        assertEquals(setOf(1L, 2L), fetcher.requested.toSet())
        assertEquals(1, fetcher.requested.count { it == 1L })
        assertEquals(1, fetcher.requested.count { it == 2L })
    }

    @Test
    fun `windowChangeCancelsPendingBatchOfOldWindow`() = runTest {
        val fetcher = FakeDanmakuFetcher(hanging = setOf(3L))
        val session = newSession(fetcher)
        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()

        session.onProgress(SEGMENT_MS)
        advanceUntilIdle()
        assertTrue("锚点 2 的邻段 3 应已发出", 3L in fetcher.requested)
        assertTrue("还在途，不该被撤", fetcher.cancelled.isEmpty())

        // 锚点 3 ⇒ 目标窗口 {2,3,4} ≠ 在途批次 {1,2,3} ⇒ 撤销旧批次
        session.onProgress(2 * SEGMENT_MS)
        advanceUntilIdle()

        assertTrue(
            "换窗口必须撤销指向旧窗口的在途批次，实际 cancelled=${fetcher.cancelled}",
            3L in fetcher.cancelled
        )
        // 收掉新的挂起批次（否则测试作用域会等它）
        session.clear()
        advanceUntilIdle()
    }

    @Test
    fun `anchorFailureSurfacesErrorWhileNeighbourFailureStaysSilent`() = runTest {
        val neighbourFails = FakeDanmakuFetcher(failing = setOf(2L))
        val s1 = newSession(neighbourFails)
        s1.setSource(VIDEO, DURATION_20_MIN)
        s1.onProgress(0L)
        advanceUntilIdle()
        assertEquals(1L, s1.state.value.window?.id)
        assertNull("邻段预取失败必须静默", s1.state.value.lastError)

        val anchorFails = FakeDanmakuFetcher(failing = setOf(1L))
        val s2 = newSession(anchorFails)
        s2.setSource(VIDEO, DURATION_20_MIN)
        s2.onProgress(0L)
        advanceUntilIdle()
        assertNotNull("当前锚点段失败必须暴露给 UI", s2.state.value.lastError)
    }

    @Test
    fun `failedSegmentIsRetriedOnNextTick`() = runTest {
        val fetcher = FakeDanmakuFetcher(failuresBeforeSuccess = mutableMapOf(1L to 1))
        val session = newSession(fetcher)
        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()
        assertNull(session.state.value.window)

        session.onProgress(0L)
        advanceUntilIdle()

        assertEquals("失败后下一次 tick 要重试（源 shouldRequestDanmakuWindow 的 hasMissingSegments 分支）",
            1L, session.state.value.window?.id)
    }

    @Test
    fun `newSourceClearsCachedSegments`() = runTest {
        val fetcher = FakeDanmakuFetcher()
        val session = newSession(fetcher)
        session.setSource(VIDEO, DURATION_20_MIN)
        session.onProgress(0L)
        advanceUntilIdle()

        session.setSource(VIDEO.copy(cid = 99L), DURATION_20_MIN)
        assertNull("换集必须清空已缓存的分段", session.state.value.window)

        val before = fetcher.requested.size
        session.onProgress(0L)
        advanceUntilIdle()
        assertTrue("换集后要重新取第 1 段", fetcher.requested.drop(before).contains(1L))
    }

    @Test
    fun `shortVideoNeverRequestsOutOfRangeSegment`() = runTest {
        val fetcher = FakeDanmakuFetcher()
        val session = newSession(fetcher)
        // 6分40秒 ⇒ 2 段
        session.setSource(VIDEO, 400_000L)
        session.onProgress(0L)
        advanceUntilIdle()
        session.onProgress(399_000L)
        advanceUntilIdle()

        assertEquals(setOf(1L, 2L), fetcher.requested.toSet())
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private fun TestScope.newSession(fetcher: FakeDanmakuFetcher) = VodDanmakuSession(
        // 会话的协程作用域直接用测试作用域（`backgroundScope` 在本版 coroutines 下不被
        // `advanceUntilIdle()` 推进，实测确认）；挂起的假取段器在用例结束前用 `clear()` 收掉。
        scope = this,
        fetchSegment = fetcher::fetch
    )

    private class FakeDanmakuFetcher(
        private val hanging: Set<Long> = emptySet(),
        private val failing: Set<Long> = emptySet(),
        private val failuresBeforeSuccess: MutableMap<Long, Int> = mutableMapOf()
    ) {
        val requested = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()

        suspend fun fetch(request: VodDanmakuRequest): VodDanmakuSegment {
            val id = request.segmentIndex
            requested += id
            val remainingFailures = failuresBeforeSuccess[id] ?: 0
            if (remainingFailures > 0) {
                failuresBeforeSuccess[id] = remainingFailures - 1
                throw IllegalStateException("fake failure seg=$id")
            }
            if (id in failing) throw IllegalStateException("fake failure seg=$id")
            if (id in hanging) {
                try {
                    awaitCancellation()
                } catch (error: CancellationException) {
                    cancelled += id
                    throw error
                }
            }
            return VodDanmakuSegment(
                request = request,
                items = listOf(item(id)),
                state = 0,
                segmentRules = emptyList(),
                colorfulSources = emptyMap(),
                contextSrc = ""
            )
        }
    }

    private companion object {
        val VIDEO = ResolvedVideoIds(aid = 1L, cid = 2L)
        const val SEGMENT_MS = 360_000L
        const val DURATION_20_MIN = 20L * 60_000L

        fun item(windowId: Long) = DanmakuItem(
            id = windowId,
            idStr = "d$windowId",
            progressMs = ((windowId - 1L) * SEGMENT_MS).toInt(),
            mode = 1,
            fontSize = 25,
            color = 0xFFFFFFFF.toInt(),
            midHash = "m",
            content = "dm-$windowId",
            createdAtEpochSecond = 0L,
            weight = 0,
            action = "",
            pool = 0,
            attr = 0,
            likeCount = 0L,
            animation = "",
            extra = "",
            colorfulType = 0,
            type = 0,
            oid = 0L,
            dmFromType = 0
        )
    }
}
