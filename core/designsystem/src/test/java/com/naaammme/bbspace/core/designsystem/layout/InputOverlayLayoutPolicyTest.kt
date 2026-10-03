package com.naaammme.bbspace.core.designsystem.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/** 输入弹层限宽策略（PT-09；上游 `InputOverlayLayoutPolicy.kt:13` 逐字语义）。 */
class InputOverlayLayoutPolicyTest {

    @Test
    fun compactWindowKeepsFullWidth() {
        assertEquals(0, resolveBottomInputOverlayMaxWidthDp(0))
        assertEquals(599, resolveBottomInputOverlayMaxWidthDp(599))
    }

    /** 600 临界：Medium 起限宽 640。 */
    @Test
    fun mediumWindowCapsAt640() {
        assertEquals(640, resolveBottomInputOverlayMaxWidthDp(600))
        assertEquals(640, resolveBottomInputOverlayMaxWidthDp(800))
        assertEquals(640, resolveBottomInputOverlayMaxWidthDp(1272))
    }

    /** 病态输入（负窗口）与上游逐字一致：`-1 < 600` → 原样返回窗口宽。 */
    @Test
    fun negativeWindowMatchesUpstreamVerbatim() {
        assertEquals(-1, resolveBottomInputOverlayMaxWidthDp(-1))
    }
}
