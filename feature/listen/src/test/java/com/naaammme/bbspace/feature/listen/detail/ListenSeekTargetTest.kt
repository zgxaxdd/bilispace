package com.naaammme.bbspace.feature.listen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拖动听书进度条的目标位置计算（BACKLOG ST-09）。
 *
 * 崩溃点是真实的：`Slider.onValueChangeFinished` 在无障碍 / IME 路径下可以在
 * **没有一次 onValueChange** 的情况下到达，那时 `dragFraction` 还是 null，
 * 而原先那行写的是 `dragFraction!!`。
 * 抽成纯函数之后这条路径能在 JVM 上跑 —— 不需要 Compose 语义树，也不需要设备。
 */
class ListenSeekTargetTest {

    @Test
    fun noDragMeansNoSeekTarget() {
        // 这就是原来会崩的那条路径：finished 到了，但用户从没拖动过
        assertNull(listenSeekTargetMs(null, 123_456L))
    }

    @Test
    fun unknownDurationYieldsNoTarget() {
        assertNull(listenSeekTargetMs(0.5f, 0L))
        assertNull(listenSeekTargetMs(0.5f, -1L))
    }

    @Test
    fun fractionMapsLinearly() {
        assertEquals(5_000L, listenSeekTargetMs(0.5f, 10_000L))
        assertEquals(0L, listenSeekTargetMs(0f, 10_000L))
        assertEquals(10_000L, listenSeekTargetMs(1f, 10_000L))
    }

    @Test
    fun outOfRangeFractionIsClampedNotPassedThrough() {
        // 越界一次就会 seek 到负数或超过总时长
        assertEquals(0L, listenSeekTargetMs(-0.4f, 10_000L))
        assertEquals(10_000L, listenSeekTargetMs(3.5f, 10_000L))
    }
}
