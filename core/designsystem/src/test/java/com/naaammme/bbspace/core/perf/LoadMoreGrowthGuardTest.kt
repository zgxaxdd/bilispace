package com.naaammme.bbspace.core.perf

import com.naaammme.bbspace.core.designsystem.component.LoadMoreGrowthGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LoadMoreGrowthGuard] 的状态机测试（AP-10 ③：同一 isLoadingMore true→false
 * 循环 10 次只触发 1 次请求）。
 *
 * 这些场景全部来自真实故障形态（AdaptiveMediaGrid.kt 的注释与本轮 S2 报告）：
 * 守卫写错的代价是"再也加载不出新内容"，所以正反两面都要覆盖。
 */
class LoadMoreGrowthGuardTest {

    /**
     * 核心：停在列表尾部，服务端连续返回空页（列表不增长），
     * effect 随 isLoadingMore 翻转重启 10 次 —— 只许发出 1 次请求。
     */
    @Test
    fun emptyPagesDoNotStorm() {
        val guard = LoadMoreGrowthGuard()
        var requests = 0
        val itemCount = 20
        guard.onItemLineChanged(itemCount)
        // 滚到尾部：初次请求
        if (guard.tryAcquire()) requests++
        repeat(10) {
            // 每轮 = 一次"加载在飞（谓词假离）→ 加载完成（真值又变回 true）"的 effect 重跑
            guard.onVisibility(shouldLoadMore = false, busy = true)  // 加载中的假离
            guard.onVisibility(shouldLoadMore = true, busy = false)  // 空页加载完成
            if (guard.tryAcquire()) requests++
        }
        assertEquals(
            "空页循环 10 轮必须只发出 1 次请求（无界请求风暴是 AP-10 的病灶）",
            1,
            requests
        )
    }

    /** 列表确实变长 → 守卫复位，下一批预取照常（不许"再也加载不出来"）。 */
    @Test
    fun growthRearmsTheGuard() {
        val guard = LoadMoreGrowthGuard()
        var requests = 0
        var itemCount = 20
        guard.onItemLineChanged(itemCount)
        if (guard.tryAcquire()) requests++
        repeat(3) { round ->
            // 服务端正常返回：列表变长
            itemCount += 20
            guard.onItemLineChanged(itemCount)
            guard.onVisibility(shouldLoadMore = false, busy = true)
            guard.onVisibility(shouldLoadMore = true, busy = false)
            if (guard.tryAcquire()) requests++
            assertEquals("第 ${round + 1} 次增长后应允许第 ${round + 2} 次预取", round + 2, requests)
        }
    }

    /** 全重复页（响应到达、条数不变）与空页同罪：不复位。 */
    @Test
    fun duplicatePageWithSameCountDoesNotRearm() {
        val guard = LoadMoreGrowthGuard()
        guard.onItemLineChanged(20)
        assertTrueFirstRequest(guard)
        // 响应到达但条数不变（全被去重滤掉）
        guard.onItemLineChanged(20)
        guard.onVisibility(shouldLoadMore = true, busy = false)
        assertEquals("条数没变就不许再发", false, guard.tryAcquire())
    }

    /** 真的滚离（false 且不在飞）→ 复位：滚回来允许一次合法重试。 */
    @Test
    fun scrollingAwayAllowsOneRetryOnReturn() {
        val guard = LoadMoreGrowthGuard()
        guard.onItemLineChanged(20)
        if (guard.tryAcquire()) Unit // 第一次请求
        guard.onVisibility(shouldLoadMore = false, busy = false) // 真滚离
        assertEquals("滚离再回来应允许重试", true, guard.tryAcquire())
    }

    /** 清空重搜：0 是中间态不复位；新首屏 0 → N 自会复位。 */
    @Test
    fun clearingThenRefillingRearms() {
        val guard = LoadMoreGrowthGuard()
        guard.onItemLineChanged(20)
        guard.tryAcquire()
        guard.onItemLineChanged(0)  // 清空（中间态）：不复位
        assertEquals("0 条的中间态不许开闸", false, guard.tryAcquire())
        guard.onItemLineChanged(8)  // 新首屏到达
        assertEquals("新内容到达后必须开闸", true, guard.tryAcquire())
    }

    private fun assertTrueFirstRequest(guard: LoadMoreGrowthGuard) {
        assertEquals("首次领取必须放行", true, guard.tryAcquire())
    }
}
