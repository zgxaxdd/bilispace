package com.naaammme.bbspace.core.designsystem.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频列表列数策略（PT-09；上游 `VideoListLayoutControl.kt:119-121` 逐字语义）。
 * 覆盖任务书要求的 320/960 临界。
 */
class VideoListLayoutPolicyTest {

    @Test
    fun singleColumnPreferenceAlwaysWins() {
        assertEquals(1, resolveVideoListColumns(singleColumn = true, availableWidthDp = 1440f))
    }

    @Test
    fun below320dpIsAlwaysSingleColumn() {
        assertEquals(1, resolveVideoListColumns(singleColumn = false, availableWidthDp = 319f))
    }

    @Test
    fun exactly320dpIsTwoColumns() {
        assertEquals(2, resolveVideoListColumns(singleColumn = false, availableWidthDp = 320f))
    }

    @Test
    fun wideWindowsDeriveThirdColumnAt960dp() {
        assertEquals(2, resolveVideoListColumns(singleColumn = false, availableWidthDp = 639f))
        assertEquals(3, resolveVideoListColumns(singleColumn = false, availableWidthDp = 960f))
    }

    @Test
    fun beyond960dpCapsAtThree() {
        assertEquals(3, resolveVideoListColumns(singleColumn = false, availableWidthDp = 2560f))
    }

    /** 桌面（2560/3≈853）与平板（800/2=400）的最小可读卡宽都在 320dp 之上。 */
    @Test
    fun everyDerivedColumnKeepsMinReadableWidth() {
        for (width in listOf(640f, 800f, 960f, 1280f, 2560f)) {
            val columns = resolveVideoListColumns(singleColumn = false, availableWidthDp = width)
            assertTrue("width=$width columns=$columns 每列须 ≥320dp", width / columns >= 320f)
        }
    }
}
