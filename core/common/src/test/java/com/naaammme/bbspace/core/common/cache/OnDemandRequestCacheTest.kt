package com.naaammme.bbspace.core.common.cache

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B12（2026-10-13，上游 v0.3.6 同步）：在途请求合并 + 等待者计数取消。
 *
 * ## 这些断言针对的是真实竞争，不是"看起来对"
 *
 * 本仓竖屏连播有两条**独立发起**的 playurl 路径（预取 `preloadPlayUrl` 与开播
 * `fetchPlaybackSource`），共用同一份缓存，但缓存只在**成功之后**才写入 ⇒
 * "预取在途、用户已经滑到"这个窗口里两条路径都 cache miss，**同一个 key 发两次完整
 * gRPC 往返**。快速连滑时这个窗口很常见。
 *
 * 所以下面每条测试都直接数 `load` 的**实际调用次数** —— 那是"有没有真的合并"的唯一
 * 硬证据。只断言"返回值相等"是证明不了合并的（两次独立请求也会返回相等的值）。
 */
class OnDemandRequestCacheTest {

    private val key = "av123#cid456"

    /**
     * ⚠️ **scope 必须复用 `runTest` 的调度器**（调用方传 `backgroundScope`）。
     *
     * 我第一版在这里自建 `CoroutineScope(SupervisorJob() + StandardTestDispatcher())` ——
     * 那会创建一个**独立的** scheduler，于是 `advanceUntilIdle()` 驱动不了缓存内部的
     * async，`runTest` 只能干等到超时（18 条测试各挂 1 分钟）。这不是被测代码的问题，
     * 是测试脚手架的问题。
     *
     * `backgroundScope` 用 `runTest` 自己的 scheduler，且会在测试结束时自动取消 ——
     * 正好适合"缓存持有的长生命周期 scope"这个形状。
     */
    private fun cache(
        scope: kotlinx.coroutines.CoroutineScope,
        maxEntries: Int = 4,
        maxAgeMs: Long = 1_000L,
        now: () -> Long = { 0L },
        canCache: (String) -> Boolean = { true },
    ) = OnDemandRequestCache<String, String>(
        maxEntries = maxEntries,
        maxAgeMs = maxAgeMs,
        nowMs = now,
        scope = scope,
        canCache = canCache,
    )

    // ── 合并 ──────────────────────────────────────────────────────────────────

    @Test
    fun concurrentCallsForSameKey_shareOneLoad() = runTest {
        val cache = cache(backgroundScope)
        val loads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        coroutineScope {
            val a = async { cache.getOrLoad(key) { loads.incrementAndGet(); gate.await(); "S" } }
            val b = async { cache.getOrLoad(key) { loads.incrementAndGet(); gate.await(); "S" } }
            advanceUntilIdle()
            gate.complete(Unit)
            assertEquals("S", a.await())
            assertEquals("S", b.await())
        }
        assertEquals(
            "B12：同 key 的并发调用必须**只发一次**请求 —— 这正是预取与开播竞争时被浪费的那一次。",
            1,
            loads.get()
        )
    }

    @Test
    fun cacheHitDoesNotLoadAgain() = runTest {
        val cache = cache(backgroundScope, maxAgeMs = 1_000L)
        val loads = AtomicInteger(0)
        repeat(3) {
            cache.getOrLoad(key) { loads.incrementAndGet(); "S" }
        }
        assertEquals("TTL 内重复取必须命中缓存，不再发请求。", 1, loads.get())
    }

    // ── 过期 ──────────────────────────────────────────────────────────────────

    @Test
    fun expiredEntry_loadsAgain() = runTest {
        var now = 0L
        val cache = cache(backgroundScope, maxAgeMs = 100L, now = { now })
        val loads = AtomicInteger(0)
        cache.getOrLoad(key) { loads.incrementAndGet(); "S" }
        now = 100L // 恰好到期（用 >= 判定）
        cache.getOrLoad(key) { loads.incrementAndGet(); "S" }
        assertEquals("到期后必须重新加载。", 2, loads.get())
    }

    @Test
    fun peekReturnsNullWhenExpired() = runTest {
        var now = 0L
        val cache = cache(backgroundScope, maxAgeMs = 100L, now = { now })
        cache.getOrLoad(key) { "S" }
        assertEquals("S", cache.peek(key))
        now = 100L
        assertNull("过期的 peek 必须返回 null（而不是陈旧值）。", cache.peek(key))
    }

    // ── 失败永不缓存 ──────────────────────────────────────────────────────────

    @Test
    fun failedResultIsNotCached_whenCanCacheFilters() = runTest {
        val cache = cache(backgroundScope, canCache = { it.startsWith("OK") })
        val loads = AtomicInteger(0)
        cache.getOrLoad(key) { loads.incrementAndGet(); "FAIL" }
        cache.getOrLoad(key) { loads.incrementAndGet(); "FAIL" }
        assertEquals(
            "B12：失败结果不得进缓存 —— 否则一次网络抖动会让该视频在 TTL 内**再也开不了**。",
            2,
            loads.get()
        )
        assertNull(cache.peek(key))
    }

    @Test
    fun successfulResultIsCached_whenCanCacheFilters() = runTest {
        val cache = cache(backgroundScope, canCache = { it.startsWith("OK") })
        val loads = AtomicInteger(0)
        cache.getOrLoad(key) { loads.incrementAndGet(); "OK" }
        cache.getOrLoad(key) { loads.incrementAndGet(); "OK" }
        assertEquals("成功结果必须进缓存。", 1, loads.get())
        assertEquals("OK", cache.peek(key))
    }

    // ── 等待者计数取消 ────────────────────────────────────────────────────────

    @Test
    fun lastWaiterLeaving_cancelsTheInFlightLoad() = runTest {
        val cache = cache(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<String>()

        val job = launch {
            cache.getOrLoad(key) {
                started.complete(Unit)
                neverFinishes.await() // 永不返回，模拟"预取在途"
            }
        }
        runCurrent()
        advanceUntilIdle()
        assertTrue(
            "加载应已开始。[诊断] started=${started.isCompleted} " +
                "inFlight=${cache.inFlightCount()} jobActive=${job.isActive}",
            started.isCompleted
        )
        assertEquals("应有一个在途请求。", 1, cache.inFlightCount())

        job.cancel() // 唯一等待者离开（用户滑走 / 预取协程被取消）
        advanceUntilIdle()
        assertEquals(
            "B12：最后一个等待者离开后，在途请求必须被**取消**（预取失去意义就别占带宽）。",
            0,
            cache.inFlightCount()
        )
    }

    @Test
    fun secondWaiterKeepsTheLoadAliveWhenFirstLeaves() = runTest {
        val cache = cache(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val loads = AtomicInteger(0)

        val first = launch {
            cache.getOrLoad(key) { loads.incrementAndGet(); gate.await(); "S" }
        }
        advanceUntilIdle()
        val second = async {
            cache.getOrLoad(key) { loads.incrementAndGet(); gate.await(); "S" }
        }
        advanceUntilIdle()

        first.cancel() // 第一个等待者走人，但还有第二个
        advanceUntilIdle()
        assertEquals(
            "B12：还有等待者时**不得**取消在途请求（否则第二个调用者会白等一场）。",
            1,
            cache.inFlightCount()
        )

        gate.complete(Unit)
        assertEquals("S", second.await())
        assertEquals("仍然只发一次。", 1, loads.get())
    }

    // ── 代际守卫 ──────────────────────────────────────────────────────────────

    @Test
    fun clearCompleted_preventStaleInFlightFromWritingBack() = runTest {
        val cache = cache(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val loads = AtomicInteger(0)

        val job = async {
            cache.getOrLoad(key) { loads.incrementAndGet(); gate.await(); "STALE" }
        }
        advanceUntilIdle()

        cache.clearCompleted() // 用户在设置里清了缓存（或内存裁剪触发）
        gate.complete(Unit)
        assertEquals("STALE", job.await())

        assertNull(
            "B12：清空之前发出的在途请求**不得**把结果写回缓存（旧代际的结果不该复活）。",
            cache.peek(key)
        )
        // 且下一次调用必须真的重新加载
        cache.getOrLoad(key) { loads.incrementAndGet(); "FRESH" }
        assertEquals("清空后必须重新请求。", 2, loads.get())
        assertEquals("FRESH", cache.peek(key))
    }

    // ── 失效 ──────────────────────────────────────────────────────────────────

    @Test
    fun invalidate_dropsCacheAndInFlight() = runTest {
        val cache = cache(backgroundScope)
        cache.getOrLoad(key) { "S" }
        assertEquals("S", cache.peek(key))
        cache.invalidate(key)
        assertNull("invalidate 必须同时清缓存。", cache.peek(key))
        assertEquals(0, cache.inFlightCount())
    }

    @Test
    fun invalidateWhere_matchesByPredicate() = runTest {
        val cache = cache(backgroundScope)
        cache.getOrLoad("av1#c") { "S1" }
        cache.getOrLoad("av2#c") { "S2" }
        cache.invalidateWhere { it.startsWith("av1#") }
        assertNull(cache.peek("av1#c"))
        assertEquals("不匹配的键必须留下。", "S2", cache.peek("av2#c"))
    }

    // ── LRU 容量 ──────────────────────────────────────────────────────────────

    @Test
    fun lruEvictsOldestBeyondCapacity() = runTest {
        val cache = cache(backgroundScope, maxEntries = 2)
        cache.getOrLoad("k1") { "v1" }
        cache.getOrLoad("k2") { "v2" }
        cache.getOrLoad("k3") { "v3" } // 触发淘汰最久未用（k1）
        assertNull("超过容量必须淘汰最久未用的条目。", cache.peek("k1"))
        assertEquals("v2", cache.peek("k2"))
        assertEquals("v3", cache.peek("k3"))
    }

    @Test
    fun getRefreshesRecencyOrder() = runTest {
        val cache = cache(backgroundScope, maxEntries = 2)
        cache.getOrLoad("k1") { "v1" }
        cache.getOrLoad("k2") { "v2" }
        cache.peek("k1") // 命中 → k1 变最近使用
        cache.getOrLoad("k3") { "v3" } // 淘汰的应是 k2
        assertEquals("命中必须刷新使用顺序（accessOrder）。", "v1", cache.peek("k1"))
        assertNull(cache.peek("k2"))
    }

    // ── 边界 ──────────────────────────────────────────────────────────────────

    @Test
    fun zeroMaxAge_disablesCaching() = runTest {
        val cache = cache(backgroundScope, maxAgeMs = 0L)
        val loads = AtomicInteger(0)
        cache.getOrLoad(key) { loads.incrementAndGet(); "S" }
        cache.getOrLoad(key) { loads.incrementAndGet(); "S" }
        assertEquals("maxAgeMs=0 表示不缓存（每次都要真的加载）。", 2, loads.get())
    }

    @Test
    fun distinctKeysLoadIndependently() = runTest {
        val cache = cache(backgroundScope)
        val loads = AtomicInteger(0)
        coroutineScope {
            val a = async { cache.getOrLoad("k1") { loads.incrementAndGet(); "v1" } }
            val b = async { cache.getOrLoad("k2") { loads.incrementAndGet(); "v2" } }
            assertEquals("v1", a.await())
            assertEquals("v2", b.await())
        }
        assertEquals("不同 key 必须各自加载（合并只对同 key 生效）。", 2, loads.get())
    }

    @Test
    fun loadExceptionPropagatesToCallerButNotToTheScope() = runTest {
        // 记录 scope 的"未捕获异常"。这条断言是**本仓相对上游的加固**的证据：
        // 上游让 async 直接失败 ⇒ 异常同时到达 await() 的调用者与 scope 的
        // CoroutineExceptionHandler（真机上可能被上报成未捕获异常）；本仓把失败
        // 收成值，只送达调用者。
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                CoroutineExceptionHandler { _, e -> uncaught += e } +
                StandardTestDispatcher(testScheduler)
        )
        val cache = cache(scope)
        val loads = AtomicInteger(0)

        var thrown: Throwable? = null
        try {
            cache.getOrLoad(key) {
                loads.incrementAndGet()
                throw IllegalStateException("boom")
            }
        } catch (e: Throwable) {
            thrown = e
        }
        advanceUntilIdle()

        assertTrue(
            "加载异常必须向上传播给调用者（不能吞成默认值）。实际 thrown=$thrown",
            thrown is IllegalStateException && thrown?.message == "boom"
        )
        assertEquals(
            "加固：失败**不得**同时被上报成 scope 的未捕获异常（真机上那会触发全局崩溃处理，" +
                "而调用方其实已经处理了它）。实际 uncaught=${uncaught.map { it.message }}",
            0,
            uncaught.size
        )
        assertEquals("异常之后在途表必须清空。", 0, cache.inFlightCount())
        assertNull("异常结果不得进缓存。", cache.peek(key))

        // 且下一次能正常加载（在途表没有被污染）
        assertEquals("RECOVERED", cache.getOrLoad(key) { loads.incrementAndGet(); "RECOVERED" })
        assertEquals("失败那次算 1 次加载，恢复那次算第 2 次。", 2, loads.get())
    }

    @Test
    fun concurrentCallsAcrossManyKeys_areCoalescedPerKey() = runTest {
        val cache = cache(backgroundScope, maxEntries = 64)
        val loads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = (1..8).flatMap { k ->
                List(3) {
                    async { cache.getOrLoad("k$k") { loads.incrementAndGet(); gate.await(); "v$k" } }
                }
            }
            advanceUntilIdle()
            gate.complete(Unit)
            jobs.forEach { it.await() }
        }
        assertEquals(
            "B12：8 个 key × 3 个并发调用者 ⇒ 恰好 8 次加载（每个 key 合并成一次）。",
            8,
            loads.get()
        )
    }

    @Test
    fun delayInsideLoad_stillCoalesces() = runTest {
        val cache = cache(backgroundScope)
        val loads = AtomicInteger(0)
        coroutineScope {
            val jobs = List(4) {
                async { cache.getOrLoad(key) { loads.incrementAndGet(); delay(50); "S" } }
            }
            jobs.forEach { it.await() }
        }
        assertEquals("加载内含挂起点时仍必须合并。", 1, loads.get())
    }
}
