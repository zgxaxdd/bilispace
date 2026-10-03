package com.naaammme.bbspace.feature.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SX-05：播放器控制件显隐策略（纯 JVM，不碰 UI）。
 *
 * 设备侧只剩"看起来够不够沉浸"（L4-116）；判据本身在这里钉死。
 */
class PlayerChromePolicyTest {

    private fun hide(
        visible: Boolean = true,
        playing: Boolean = true,
        gesture: Boolean = false,
        dialog: Boolean = false,
        sheet: Boolean = false,
    ): Boolean = PlayerChromePolicy.shouldAutoHide(
        controlsVisible = visible,
        isPlaying = playing,
        gestureActive = gesture,
        dialogOpen = dialog,
        sheetOpen = sheet
    )

    @Test
    fun hidesOnlyWhenVisiblePlayingAndFullyIdle() {
        assertTrue("可见 + 播放中 + 无手势无弹层 ⇒ 该自动隐藏", hide())
    }

    @Test
    fun neverHidesWhenControlsAreNotShownOrWhenPaused() {
        assertFalse("本来就没显示，没有可隐藏的东西", hide(visible = false))
        assertFalse("暂停时保持可见（用户正是要看控件找进度/重播）", hide(playing = false))
    }

    @Test
    fun gestureOrOverlayBlocksAutoHide() {
        assertFalse("拖动中不许在手指底下把控件收走", hide(gesture = true))
        assertFalse("弹层在场时必须留着退出/返回的可用性（SX-05 要点②）", hide(dialog = true))
        assertFalse("弹层在场时必须留着退出/返回的可用性（SX-05 要点②）", hide(sheet = true))
    }

    @Test
    fun revealsOnGestureOnlyWhenControlsAreHidden() {
        assertTrue(
            "SX-05 要点①：拖动要能唤出控件",
            PlayerChromePolicy.shouldRevealOnGesture(gestureActive = true, controlsVisible = false)
        )
        assertFalse(
            "已可见时不再写 state —— 否则每帧给自动隐藏计时续期，控件永不隐藏",
            PlayerChromePolicy.shouldRevealOnGesture(gestureActive = true, controlsVisible = true)
        )
        assertFalse(
            "没手势就不唤出",
            PlayerChromePolicy.shouldRevealOnGesture(gestureActive = false, controlsVisible = false)
        )
    }

    @Test
    fun autoHideTimeoutKeepsTheHistoricalTwoSeconds() {
        assertEquals(
            "SX-05 只给这个超时起名字（去裸字面量），不改手感",
            2_000L,
            PlayerChromePolicy.AUTO_HIDE_MS
        )
    }
}
