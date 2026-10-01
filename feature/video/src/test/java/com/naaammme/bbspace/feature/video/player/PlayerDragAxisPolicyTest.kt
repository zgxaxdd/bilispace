package com.naaammme.bbspace.feature.video.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放器手势轴仲裁的意图宽限。
 *
 * 真机症状（BiliPai 上游同款缺陷，`5bb8728c0`）：斜着上滑刷视频时，手指落地的自然
 * 竖向抖动先给出几像素，旧判据在 touchSlop 那一刻就按 `abs(x) > abs(y)` 锁死轴，
 * 于是本意"上滑翻页"的手势被判成横向 seek（不可逆），表现为"划不动 / 进度乱跳"。
 *
 * 这里钉的是**判据形状**：横向要明显主导、竖向/斜向在会话里多一段宽限、
 * 而宽限在详情页必须关掉（否则与外层列表双响应）。
 */
class PlayerDragAxisPolicyTest {

    // 典型设备 touchSlop ≈ 8dp（这里直接用 px 数值，判据只看比值）
    private val slop = 8f

    @Test
    fun belowSystemSlopStaysUndecidedWhateverTheScene() {
        assertEquals(PlayerDragIntent.UNDECIDED, PlayerDragAxisPolicy.resolveIntent(0f, 0f, slop))
        assertEquals(PlayerDragIntent.UNDECIDED, PlayerDragAxisPolicy.resolveIntent(3f, 4f, slop))
        assertEquals(
            PlayerDragIntent.UNDECIDED,
            PlayerDragAxisPolicy.resolveIntent(3f, 4f, slop, ambiguousSlopMultiplier = 1f)
        )
    }

    @Test
    fun clearlyHorizontalLocksImmediatelyEvenWithGrace() {
        // 宽限只延后"竖向/斜向"的归属，不能把用户点名的 seek 拖慢半拍
        assertEquals(PlayerDragIntent.HORIZONTAL, PlayerDragAxisPolicy.resolveIntent(40f, 6f, slop))
        assertEquals(
            PlayerDragIntent.HORIZONTAL,
            PlayerDragAxisPolicy.resolveIntent(12f, 1f, slop, ambiguousSlopMultiplier = 1f)
        )
    }

    @Test
    fun diagonalDriftGetsGraceOnlyInsideASwipeSession() {
        // x=8,y=6：比值 1.33 < 1.5 不构成横向主导，位移刚过 slop、未过宽限
        // - 会话：未决（让 pager 从起手就能追翻页）
        assertEquals(PlayerDragIntent.UNDECIDED, PlayerDragAxisPolicy.resolveIntent(8f, 6f, slop))
        // - 详情页：立即定竖向（旧时间点，避免"列表滚 + 亮度动"双响应）
        assertEquals(
            PlayerDragIntent.VERTICAL,
            PlayerDragAxisPolicy.resolveIntent(8f, 6f, slop, ambiguousSlopMultiplier = 1f)
        )
    }

    @Test
    fun pastTheGraceWindowNonDominantTravelBelongsToVertical() {
        // 斜着但没斜过 1.5 倍：宽限之后归竖向（会话里 = 让位给翻页）
        assertEquals(PlayerDragIntent.VERTICAL, PlayerDragAxisPolicy.resolveIntent(20f, 15f, slop))
        assertEquals(PlayerDragIntent.VERTICAL, PlayerDragAxisPolicy.resolveIntent(2f, 40f, slop))
    }

    @Test
    fun signDoesNotMatterAndZeroSlopDoesNotCrash() {
        assertEquals(PlayerDragIntent.HORIZONTAL, PlayerDragAxisPolicy.resolveIntent(-40f, -6f, slop))
        assertEquals(PlayerDragIntent.VERTICAL, PlayerDragAxisPolicy.resolveIntent(-2f, -40f, slop))
        // slop=0（不该出现，但判据不能因此把"没动"读成手势）
        assertEquals(PlayerDragIntent.UNDECIDED, PlayerDragAxisPolicy.resolveIntent(0f, 0f, 0f))
        assertEquals(PlayerDragIntent.HORIZONTAL, PlayerDragAxisPolicy.resolveIntent(5f, 0f, 0f))
    }

    @Test
    fun graceMultiplierBelowOneIsClampedToNoGrace() {
        // 传 0.5 不能被解读成"比 slop 更早锁轴"
        assertEquals(
            PlayerDragIntent.VERTICAL,
            PlayerDragAxisPolicy.resolveIntent(8f, 6f, slop, ambiguousSlopMultiplier = 0.5f)
        )
    }

    @Test
    fun constantsMatchUpstreamShape() {
        // 与 BiliPai `VerticalPriorityPagerGesture.kt:39-41` 同值：改数字=改手感，先对齐再谈调
        assertEquals(1.5f, PlayerDragAxisPolicy.HORIZONTAL_DOMINANCE_RATIO, 0f)
        assertEquals(1.5f, PlayerDragAxisPolicy.AMBIGUOUS_SLOP_MULTIPLIER, 0f)
    }
}
