package com.naaammme.bbspace.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎所有权仲裁的状态测试（BACKLOG ST-07）。
 *
 * `PlayerEngineArbiter` 是本仓唯一一台播放器（`@Singleton PlayerEngine`）的归属判定，
 * 它自己没有任何 Android 依赖，所以能在纯 JVM 上把"谁能动引擎"这件事的所有交错都跑一遍。
 *
 * 下面 [Gate] 复刻的是**调用点必须遵守的契约**（`if (arbiter.release(h)) engine.release()`），
 * 真实调用点是否长这样由 `PlayerEngineOwnershipGuardTest` 逐行钉住 ——
 * 两边分开是有意的：语义在这里测，接线在守卫里测，避免为了测一个类去伪造
 * `Dispatchers.Main` 与整个 ExoPlayer 栈。
 */
class PlayerEngineArbiterTest {

    /** 记数的假引擎：只关心 release / stopForReuse 各被调了几次。 */
    private class FakeEngine {
        var releases = 0
        var stops = 0
        fun release() {
            releases += 1
        }

        fun stopForReuse() {
            stops += 1
        }
    }

    /** 调用点契约的复刻：非占用者动不了引擎。 */
    private class Gate(
        private val arbiter: PlayerEngineArbiter,
        private val engine: FakeEngine
    ) {
        fun startUsing(holder: EngineHolder) = arbiter.claim(holder)

        fun finish(holder: EngineHolder, releasePlayer: Boolean) {
            if (releasePlayer) {
                if (arbiter.release(holder)) engine.release()
            } else if (arbiter.isHolder(holder)) {
                engine.stopForReuse()
            }
        }
    }

    private class Harness(
        val arbiter: PlayerEngineArbiter,
        val engine: FakeEngine,
        val gate: Gate
    )

    private fun harness(): Harness {
        val arbiter = PlayerEngineArbiter()
        val engine = FakeEngine()
        return Harness(arbiter, engine, Gate(arbiter, engine))
    }

    @Test
    fun nothingIsOwnedAtStart() {
        val arbiter = PlayerEngineArbiter()
        assertEquals(EngineHolder.NONE, arbiter.holder)
        assertFalse(arbiter.release(EngineHolder.STREAM))
        assertFalse(arbiter.release(EngineHolder.DOWNLOAD))
        assertFalse(arbiter.isHolder(EngineHolder.STREAM))
    }

    @Test
    fun onlyTheCurrentHolderCanRelease() {
        val h = harness()
        h.gate.startUsing(EngineHolder.STREAM)
        // 缓存页此时没有这台引擎，它的 release 必须被挡掉
        assertFalse(h.arbiter.release(EngineHolder.DOWNLOAD))
        assertEquals(EngineHolder.STREAM, h.arbiter.holder)
        assertTrue(h.arbiter.release(EngineHolder.STREAM))
        assertEquals(EngineHolder.NONE, h.arbiter.holder)
    }

    @Test
    fun claimIsPreemptiveBecauseThereIsOnlyOnePlayer() {
        val arbiter = PlayerEngineArbiter()
        arbiter.claim(EngineHolder.STREAM)
        arbiter.claim(EngineHolder.DOWNLOAD)
        // 后写者拿到媒体，前一个会话的收尾就不该再动引擎
        assertFalse(arbiter.release(EngineHolder.STREAM))
        assertEquals(EngineHolder.DOWNLOAD, arbiter.holder)
        assertTrue(arbiter.release(EngineHolder.DOWNLOAD))
    }

    /** 本批要修的真实场景：正在放缓存视频时直播页收尾，引擎不能被动。 */
    @Test
    fun streamTeardownDoesNotKillAnOngoingDownloadPlayback() {
        val h = harness()
        h.gate.startUsing(EngineHolder.STREAM)
        h.gate.startUsing(EngineHolder.DOWNLOAD)
        h.gate.finish(EngineHolder.STREAM, releasePlayer = true)
        h.gate.finish(EngineHolder.STREAM, releasePlayer = false)
        assertEquals(0, h.engine.releases)
        assertEquals(0, h.engine.stops)
        assertEquals(EngineHolder.DOWNLOAD, h.arbiter.holder)

        // 缓存页自己是占用者，它的收尾照常真正释放
        h.gate.finish(EngineHolder.DOWNLOAD, releasePlayer = true)
        assertEquals(1, h.engine.releases)
        assertEquals(EngineHolder.NONE, h.arbiter.holder)
    }

    /** 反向：缓存页收尾时若流会话已接管，同样不动引擎。 */
    @Test
    fun downloadTeardownDoesNotKillAStreamThatTookOver() {
        val arbiter = PlayerEngineArbiter()
        arbiter.claim(EngineHolder.DOWNLOAD)
        arbiter.claim(EngineHolder.STREAM)
        assertFalse(arbiter.release(EngineHolder.DOWNLOAD))
        assertEquals(EngineHolder.STREAM, arbiter.holder)
    }

    @Test
    fun stopForReuseKeepsOwnership() {
        val arbiter = PlayerEngineArbiter()
        arbiter.claim(EngineHolder.STREAM)
        assertTrue(arbiter.isHolder(EngineHolder.STREAM))
        // stopForReuse 只是"这台机器我留着复用"，不交还占用
        assertTrue(arbiter.isHolder(EngineHolder.STREAM))
        assertTrue(arbiter.release(EngineHolder.STREAM))
    }

    @Test
    fun releaseIsIdempotentForTheSameHolder() {
        val arbiter = PlayerEngineArbiter()
        arbiter.claim(EngineHolder.DOWNLOAD)
        assertTrue(arbiter.release(EngineHolder.DOWNLOAD))
        assertFalse(arbiter.release(EngineHolder.DOWNLOAD))
    }

    /** 两条页面几乎同时收尾：只能有一次真正释放（这是 CAS 的意义）。 */
    @Test
    fun concurrentReleaseHappensExactlyOnce() {
        val arbiter = PlayerEngineArbiter()
        arbiter.claim(EngineHolder.DOWNLOAD)
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (arbiter.release(EngineHolder.DOWNLOAD)) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发释放没在 5 秒内收口", done.await(5L, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.get())
    }
}
