package com.naaammme.bbspace.feature.video.player

import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #892（上游 `40690bd19`）判据用例，**整体移植**（9 条上游用例逐条保留）。
 *
 * 上游用 `kotlin.test`，本仓统一 JUnit4 —— 断言语义不变，只换注解/断言类型。
 * 用例按上游原顺序排列，便于日后与上游 diff 对齐。
 */
class PlaybackPageOwnershipTest {

    // ── PlaybackPageOwnership（上游 3 条）────────────────────────────────────

    @Test
    fun returningFromChildKeepsParentPlayerRetained() {
        val ownership = PlaybackPageOwnership()
        val parent = Any()
        val child = Any()
        ownership.retain(parent)
        ownership.retain(child)

        ownership.release(child)

        assertTrue(ownership.isRetained(parent))
        assertFalse(ownership.isRetained(child))
        ownership.release(parent)
        assertFalse(ownership.isRetained(parent))
    }

    @Test
    fun reusedPlayerCannotBeReleasedUntilItsLastPageIsDisposed() {
        val ownership = PlaybackPageOwnership()
        val player = Any()
        ownership.retain(player)
        ownership.retain(player)

        ownership.release(player)
        assertTrue(ownership.isRetained(player))

        ownership.release(player)
        assertFalse(ownership.isRetained(player))
    }

    @Test
    fun separateInstancesWithEqualValuesHaveIndependentOwners() {
        data class PlayerIdentity(val bvid: String)
        val parent = PlayerIdentity("BV_same")
        val child = PlayerIdentity("BV_same")
        val ownership = PlaybackPageOwnership()
        ownership.retain(parent)
        ownership.retain(child)

        ownership.release(child)

        // IdentityHashMap 语义：值相等的两个实例是两个持有者，不是同一个。
        assertTrue(ownership.isRetained(parent))
        assertFalse(ownership.isRetained(child))
    }

    // ── shouldReclaimVideoPlaybackSession（上游 6 条）────────────────────────

    @Test
    fun returningParentReclaimsSessionFromRecommendedVideo() {
        assertTrue(
            shouldReclaimVideoPlaybackSession(
                isVisible = true,
                isSessionActive = true,
                currentPlayer = Any(),
                currentBvid = "BV_child",
                currentCid = 20L,
                pagePlayer = Any(),
                pageBvid = "BV_parent",
                pageCid = 10L,
            )
        )
    }

    @Test
    fun coveredParentCannotStealChildSession() {
        assertFalse(
            shouldReclaimVideoPlaybackSession(
                isVisible = false,
                isSessionActive = true,
                currentPlayer = Any(),
                currentBvid = "BV_child",
                currentCid = 20L,
                pagePlayer = Any(),
                pageBvid = "BV_parent",
                pageCid = 10L,
            )
        )
    }

    @Test
    fun sameVideoWithDifferentPlayerStillNeedsRebinding() {
        assertTrue(
            shouldReclaimVideoPlaybackSession(
                isVisible = true,
                isSessionActive = true,
                currentPlayer = Any(),
                currentBvid = "BV_parent",
                currentCid = 10L,
                pagePlayer = Any(),
                pageBvid = "BV_parent",
                pageCid = 10L,
            )
        )
    }

    @Test
    fun clearedSessionMustBeReclaimedByVisiblePage() {
        assertTrue(
            shouldReclaimVideoPlaybackSession(
                isVisible = true,
                isSessionActive = false,
                currentPlayer = null,
                currentBvid = null,
                currentCid = 0L,
                pagePlayer = Any(),
                pageBvid = "BV_parent",
                pageCid = 10L,
            )
        )
    }

    @Test
    fun anotherPageOfTheSameVideoMustRefreshSessionIdentity() {
        val player = Any()
        assertTrue(
            shouldReclaimVideoPlaybackSession(
                isVisible = true,
                isSessionActive = true,
                currentPlayer = player,
                currentBvid = "BV_parent",
                currentCid = 10L,
                pagePlayer = player,
                pageBvid = "BV_parent",
                pageCid = 11L,
            )
        )
    }

    @Test
    fun currentPageDoesNotRebindItsHealthySession() {
        val player = Any()
        assertFalse(
            shouldReclaimVideoPlaybackSession(
                isVisible = true,
                isSessionActive = true,
                currentPlayer = player,
                currentBvid = "BV_parent",
                currentCid = 10L,
                pagePlayer = player,
                pageBvid = "BV_parent",
                pageCid = 10L,
            )
        )
    }

    // ── 本仓适配：会话快照 → 页面 target（3 条）────────────────────────────

    @Test
    fun ugcSnapshotRestoresUgcTarget() {
        val target = playbackPageTargetOf(
            biz = PlayBiz.UGC,
            ids = ResolvedVideoIds(aid = 42L, cid = 7L, bvid = "BV1xx411c7mD")
        )
        assertTrue(target is VideoTarget.Ugc)
        target as VideoTarget.Ugc
        assertEquals(42L, target.aid)
        assertEquals(7L, target.cid)
        assertEquals("BV1xx411c7mD", target.bvid)
    }

    @Test
    fun pgcSnapshotRestoresPgcTargetWithEpisodeIdentity() {
        val target = playbackPageTargetOf(
            biz = PlayBiz.PGC,
            ids = ResolvedVideoIds(aid = 1L, cid = 2L, epId = 3L, seasonId = 4L)
        )
        assertTrue(target is VideoTarget.Pgc)
        target as VideoTarget.Pgc
        assertEquals(3L, target.epId)
        assertEquals(4L, target.seasonId)
    }

    @Test
    fun emptySnapshotCannotBeAdoptedAsPageIdentity() {
        // 进程刚起 / 会话已 close：ids 全空时认领会造出一个 aid=0 的假页面。
        assertNull(playbackPageTargetOf(biz = PlayBiz.UGC, ids = ResolvedVideoIds()))
        assertNull(playbackPageTargetOf(biz = PlayBiz.PGC, ids = ResolvedVideoIds()))
    }
}
