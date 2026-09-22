package com.naaammme.bbspace.core.common.coroutines

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runCatchingCancellable] 的行为测试。
 *
 * 为什么单独给它写测试：本轮（以及上一轮）把大量 `runCatching` 换成了它，
 * 而"换了就等于修好取消语义"这个前提是**没有验证过的**——如果这个 helper 本身
 * 行为不对，那所有替换都是在传播一个错误的假设。它又是纯同步代码（inline + try/catch），
 * 不需要 coroutines-test、不需要设备就能完整覆盖，没有理由不测。
 */
class RunCatchingCancellableTest {

    /** 取消必须**原样抛出**，不能变成 Result.failure。 */
    @Test
    fun cancellationIsRethrownNotCaptured() {
        var caught: Throwable? = null
        try {
            runCatchingCancellable { throw CancellationException("scope cancelled") }
        } catch (c: CancellationException) {
            caught = c
        }
        assertTrue(
            "CancellationException 被吞掉了 —— 这正是标准库 runCatching 破坏结构化并发的地方：" +
                "协程不再从挂起点向上展开，调用方的 onFailure 照常执行，于是" +
                "\"页面已销毁却还在改状态\"。",
            caught != null
        )
        assertEquals("scope cancelled", caught?.message)
    }

    /** 真实异常仍然要包成 failure，否则换成它就等于把错误处理弄丢了。 */
    @Test
    fun realFailureIsStillCaptured() {
        val result = runCatchingCancellable { throw IllegalStateException("network down") }
        assertTrue("真实异常必须走 Result.failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun successIsPassedThrough() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrThrow())
    }

    /**
     * 子类也要按取消处理。
     *
     * 实际会遇到的子类是 `job cancellation` 内部抛出的那些，以及 `TimeoutCancellationException`
     * （`withTimeoutOrNull` 的机制）。如果只判 `::class == CancellationException::class`
     * 就会把超时当成真实失败。
     */
    @Test
    fun cancellationSubclassIsAlsoRethrown() {
        class MyCancellation : CancellationException("sub")
        var caught = false
        try {
            runCatchingCancellable { throw MyCancellation() }
        } catch (c: CancellationException) {
            caught = true
        }
        assertTrue("CancellationException 的子类同样属于取消，必须重抛", caught)
    }
}
