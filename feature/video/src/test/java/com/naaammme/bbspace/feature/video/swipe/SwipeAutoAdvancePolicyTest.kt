package com.naaammme.bbspace.feature.video.swipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播完要不要自动翻到下一支」的判据。
 *
 * 对照 BiliPai 的 `playbackCompletionBehavior`（STOP/REPEAT/CONTINUE 枚举）；
 * bbspace 落成"一个开关 + 与单曲循环互斥"的最小版本。
 *
 * 写错的两种后果都很直观：漏判 = 关掉了开关还在自动翻；误判 = 开着却停在结尾。
 */
class SwipeAutoAdvancePolicyTest {

    @Test
    fun advancesInTheNormalCase() {
        assertTrue(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = false,
                isLastPage = false,
                alreadyAdvancedForThisVideo = false,
            )
        )
    }

    @Test
    fun respectsTheUserSwitch() {
        assertFalse(
            "用户关掉「播完自动下一个」后必须停在结尾",
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = false,
                repeatOne = false,
                isLastPage = false,
                alreadyAdvancedForThisVideo = false,
            )
        )
    }

    @Test
    fun repeatOneWinsOverAutoAdvance() {
        // 单曲循环时播放器根本不发 STATE_ENDED；显式判一次是为了让"两者互斥"可见
        assertFalse(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = true,
                isLastPage = false,
                alreadyAdvancedForThisVideo = false,
            )
        )
    }

    @Test
    fun stopsAtTheLastPage() {
        assertFalse(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = false,
                isLastPage = true,
                alreadyAdvancedForThisVideo = false,
            )
        )
    }

    @Test
    fun isIdempotentPerVideo() {
        // 某些机型会连发两次 STATE_ENDED：同一支只许翻一次，否则一次跳两支
        assertFalse(
            shouldAutoAdvanceToNextSwipePage(
                autoAdvanceEnabled = true,
                repeatOne = false,
                isLastPage = false,
                alreadyAdvancedForThisVideo = true,
            )
        )
    }
}
