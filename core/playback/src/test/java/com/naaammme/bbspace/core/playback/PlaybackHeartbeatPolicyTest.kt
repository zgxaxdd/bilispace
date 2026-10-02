package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放心搏判据的状态测（PT-04，语义移植自上游 `PlaybackAncillaryTaskPolicy.kt:24-100`）。
 *
 * 覆盖任务书验收 ① 的五个行为：播放中触发 / 暂停不触发 / 后台不触发 /
 * 身份缺失不触发 / 结束后不触发；外加快照进展判据与周期常量。
 * 循环本体（delay+读状态）在 `PlaybackReporter`，判据全部收口在本文件可测的纯函数里。
 */
class PlaybackHeartbeatPolicyTest {

    private val aid = 123L
    private val cid = 456L

    // ── shouldSendPlaybackHeartbeat（周期判据） ──────────────────────────────

    @Test
    fun playingForegroundWithIdsSends() {
        assertTrue(
            PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(
                isPlaying = true, isInBackground = false, aid = aid, cid = cid
            )
        )
    }

    @Test
    fun pausedDoesNotSend() {
        assertFalse(
            PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(
                isPlaying = false, isInBackground = false, aid = aid, cid = cid
            )
        )
    }

    @Test
    fun backgroundDoesNotSend() {
        assertFalse(
            PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(
                isPlaying = true, isInBackground = true, aid = aid, cid = cid
            )
        )
    }

    @Test
    fun missingIdentityDoesNotSend() {
        assertFalse(
            PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(true, false, aid = 0L, cid = cid)
        )
        assertFalse(
            PlaybackHeartbeatPolicy.shouldSendPlaybackHeartbeat(true, false, aid = aid, cid = 0L)
        )
    }

    // ── 快照计算 ─────────────────────────────────────────────────────────────

    @Test
    fun snapshotComputesPlayedAndRealPlayedSeconds() {
        val snapshot = PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSnapshot(
            currentPositionMs = 65_432L,
            accumulatedPlayMs = 10_000L,
            activePlayStartElapsedMs = 1_000L,
            nowElapsedMs = 4_000L
        )
        assertEquals(65L, snapshot.playedTimeSec)
        assertEquals(13L, snapshot.realPlayedTimeSec) // 10s + (4000-1000)=3s
    }

    @Test
    fun snapshotClampsNegatives() {
        val snapshot = PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSnapshot(
            currentPositionMs = -5L,
            accumulatedPlayMs = -10L,
            activePlayStartElapsedMs = null,
            nowElapsedMs = 1_000L
        )
        assertEquals(0L, snapshot.playedTimeSec)
        assertEquals(0L, snapshot.realPlayedTimeSec)
    }

    // ── 快照进展判据（shouldFlush） ───────────────────────────────────────────

    private fun snapshot(played: Long, real: Long) =
        PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot(playedTimeSec = played, realPlayedTimeSec = real)

    @Test
    fun flushRequiresValidIdentityAndPositivePlay() {
        val s = snapshot(10L, 10L)
        assertFalse(PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(0L, cid, s, null))
        assertFalse(PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, 0L, s, null))
        assertFalse(PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot(0L, 0L), null))
    }

    @Test
    fun flushWhenFirstReportOrProgressed() {
        assertTrue(PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot(10L, 10L), null))
        assertFalse("无进展不发", PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot(10L, 10L), snapshot(10L, 10L)))
        assertTrue("位置前进才发", PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot(11L, 10L), snapshot(10L, 10L)))
        assertTrue("真实时长前进也发", PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot(aid, cid, snapshot(10L, 11L), snapshot(10L, 10L)))
    }

    // ── 会话开始时间戳 ───────────────────────────────────────────────────────

    @Test
    fun sessionStartTsKeepsExisting() {
        assertEquals(100L, PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSessionStartTsSec(100L, 999L))
        assertEquals(999L, PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSessionStartTsSec(0L, 999L))
        assertEquals(0L, PlaybackHeartbeatPolicy.resolvePlaybackHeartbeatSessionStartTsSec(0L, -1L))
    }

    // ── tick 决策（PlaybackReporter.heartbeatTickDecision 的可测内核） ────────

    private fun tick(
        isPlaying: Boolean = true,
        isInBackground: Boolean = false,
        heartbeatEnded: Boolean = false,
        positionMs: Long = 60_000L,
        playedTimeMs: Long = 60_000L,
        lastReported: PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot? = null
    ): PlaybackHeartbeatPolicy.PlaybackHeartbeatSnapshot? {
        return playbackHeartbeatTickDecision(
            isPlaying = isPlaying,
            isInBackground = isInBackground,
            aid = aid,
            cid = cid,
            positionMs = positionMs,
            playedTimeMs = playedTimeMs,
            nowElapsedMs = 1_000L,
            heartbeatEnded = heartbeatEnded,
            lastReported = lastReported
        )
    }

    @Test
    fun tickSendsWhilePlaying() {
        assertNotNull(tick())
    }

    @Test
    fun tickSkipsWhenPausedOrBackground() {
        assertNull(tick(isPlaying = false))
        assertNull(tick(isInBackground = true))
    }

    @Test
    fun tickSkipsAfterHeartbeatEnded() {
        assertNull(tick(heartbeatEnded = true))
    }

    @Test
    fun tickResumesAfterPauseEnds() {
        assertNotNull("恢复播放后应继续触发", tick(isPlaying = true, lastReported = snapshot(10L, 10L), positionMs = 61_000L, playedTimeMs = 61_000L))
    }

    @Test
    fun tickSkipsWithoutProgress() {
        assertNull(
            "位置与真实时长都没前进 → 不发",
            tick(lastReported = snapshot(60L, 60L), positionMs = 60_000L, playedTimeMs = 60_000L)
        )
    }

    // ── 周期常量与循环接线（结构断言） ────────────────────────────────────────

    @Test
    fun intervalIsUpstream30Seconds() {
        assertEquals(30_000L, PlaybackHeartbeatPolicy.PLAYBACK_HEARTBEAT_INTERVAL_MS)
    }

    /** PT-04 验收②：取消路径存在且被 endHeartbeat 调用（W20：锚在调用本身）。 */
    @Test
    fun endHeartbeatCancelsTheLoop() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile
        val reporter = File(
            repoRoot,
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackReporter.kt"
        )
        assertTrue("PlaybackReporter.kt 不见了", reporter.isFile)
        val lines = reporter.readLines()
        val endAt = lines.indexOfFirst { it.trimStart().startsWith("private suspend fun endHeartbeat(") }
        assertTrue("找不到 endHeartbeat", endAt >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in endAt until lines.size) {
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
        // W26：注释掉的调用等于没有调用——必须跳过注释行再判
        val code = body.filter {
            val t = it.trimStart()
            !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
        }
        assertTrue(
            "endHeartbeat 体内必须取消心搏循环（heartbeatLoopJob?.cancel()）——" +
                "否则会话结束后循环仍在后台空转（FINDINGS P2：凡周期任务都要有退出条件）",
            code.any { it.contains("heartbeatLoopJob?.cancel()") }
        )
        // 循环体必须睡的是策略常量（单一真值源），不许现场写 30_000
        assertTrue(
            "循环必须用 PlaybackHeartbeatPolicy.PLAYBACK_HEARTBEAT_INTERVAL_MS 作为周期",
            lines.any { it.contains("delay(PlaybackHeartbeatPolicy.PLAYBACK_HEARTBEAT_INTERVAL_MS)") }
        )
    }
}
