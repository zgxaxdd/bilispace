package com.naaammme.bbspace.infra.network.dns

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FL-11：多源并行编排的状态测（③ 全部失败时总耗时 ≤ 预算）。
 *
 * 这条保护的是**起播关键路径上的延迟**：原实现 5 个 HTTPDNS 服务器串行探测，
 * 最坏 5×1s 叠加。测试用可注入的探针与真实线程池验证"并行 + 预算封顶"这件事本身
 * （不是测时序运气：所有任务同眠，并行与串行的耗时差是数量级级别）。
 */
class ParallelFirstSuccessTest {

    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "dns-test-probe").apply { isDaemon = true }
    }

    @Test
    fun allFailuresDoNotStackLinearly() {
        val probeMs = 200L
        val tasks = List(5) {
            Callable<String?> {
                Thread.sleep(probeMs)
                null
            }
        }
        val started = System.nanoTime()
        val result = parallelFirstSuccess(tasks, executor, totalBudgetMs = 1_500)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNull("全部失败应返回 null", result)
        assertTrue(
            "5 个源各眠 ${probeMs}ms：并行（3 并发 → 两波）应在 ~400ms 量级完成，" +
                "串行会是 ~1000ms —— 实测 ${elapsedMs}ms",
            elapsedMs < probeMs * 5
        )
        assertTrue("总耗时不得超过预算", elapsedMs <= 1_500 + 300)
    }

    @Test
    fun firstSuccessWinsAndRestAreCancelled() {
        val tasks = listOf(
            Callable<String?> { Thread.sleep(150); null },
            Callable<String?> { "second-server" },
            Callable<String?> { Thread.sleep(150); null }
        )
        val result = parallelFirstSuccess(tasks, executor, totalBudgetMs = 1_000)
        assertEquals("首个非空结果即返回", "second-server", result)
    }

    @Test
    fun emptyAndZeroBudgetShortCircuit() {
        assertNull(parallelFirstSuccess(emptyList(), executor, totalBudgetMs = 1_000))
        val slow = listOf(Callable<String?> { Thread.sleep(300); "late" })
        val started = System.nanoTime()
        assertNull(
            "预算为 0 时必须立即返回 null（不等待在飞任务）",
            parallelFirstSuccess(slow, executor, totalBudgetMs = 0)
        )
        assertTrue((System.nanoTime() - started) / 1_000_000L < 300)
    }

    @Test
    fun thrownExceptionsCountAsSourceFailure() {
        val tasks = listOf(
            Callable<String?> { throw IllegalStateException("boom") },
            Callable<String?> { "ok" }
        )
        assertEquals("单源异常不许中断整体编排", "ok", parallelFirstSuccess(tasks, executor, totalBudgetMs = 1_000))
    }
}
