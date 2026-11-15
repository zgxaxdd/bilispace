package com.naaammme.bbspace.feature.comment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A14（上游 v0.3.6 同步）：首屏评论骨架槽位数按视口高度推导的边界。
 *
 * 纯 JVM，无 Android 依赖 —— 这是本项唯一能真跑的验证（其余靠
 * `:core:designsystem` 的 `SkeletonShimmerStructureTest` 做结构守卫）。
 *
 * 上游判据：`ceil(视口高度 / 130f).coerceIn(3, 8)`，`<= 0` 时回落下限。
 * 参考实机：OPPO PLC110 为 2800px @ density 3.5 ⇒ 800dp ⇒ 7 槽。
 */
class CommentSkeletonSlotPolicyTest {

    @Test
    fun unmeasuredOrInvalidViewport_fallsBackToMinimum() {
        assertEquals("未测量（0）必须回落下限，不能铺 0 槽", 3, resolveCommentSkeletonSlotCount(0))
        assertEquals("负值（异常输入）同样回落下限", 3, resolveCommentSkeletonSlotCount(-100))
    }

    @Test
    fun shortViewport_stillKeepsMinimumSlots() {
        assertEquals(3, resolveCommentSkeletonSlotCount(1))
        assertEquals(3, resolveCommentSkeletonSlotCount(100))
        assertEquals("恰好 130dp ⇒ ceil(1)=1 ⇒ 被夹到下限 3", 3, resolveCommentSkeletonSlotCount(130))
    }

    @Test
    fun normalViewport_scalesWithHeight() {
        assertEquals("131dp ⇒ ceil=2 ⇒ 夹到 3", 3, resolveCommentSkeletonSlotCount(131))
        assertEquals("390dp ⇒ 3 槽", 3, resolveCommentSkeletonSlotCount(390))
        assertEquals("391dp ⇒ ceil=4", 4, resolveCommentSkeletonSlotCount(391))
        // 参考实机：2800px / 3.5 = 800dp ⇒ ceil(800/130) = ceil(6.15) = 7
        assertEquals("实机 800dp ⇒ 7 槽", 7, resolveCommentSkeletonSlotCount(800))
        assertEquals("1040dp ⇒ ceil(8.0) = 8", 8, resolveCommentSkeletonSlotCount(1040))
    }

    @Test
    fun hugeViewport_isCappedAtMaximum() {
        assertEquals("超过 1040dp 一律封顶 8 槽", 8, resolveCommentSkeletonSlotCount(1041))
        assertEquals(8, resolveCommentSkeletonSlotCount(4000))
        assertEquals("极端输入不得溢出", 8, resolveCommentSkeletonSlotCount(Int.MAX_VALUE))
    }

    @Test
    fun slotHeightEstimate_isInRealisticRange() {
        // 130dp 是**估值**（见常量 KDoc）。这条断言的作用是：若有人把它改成明显不合理的值
        // （比如 40dp ⇒ 首屏铺 8 槽但每槽只有真实行高的 1/3，列表仍会被撑长），这里会红。
        assertEquals(130f, COMMENT_SKELETON_SLOT_HEIGHT_DP, 0.01f)
        assertEquals(3, COMMENT_SKELETON_MIN_SLOTS)
        assertEquals(8, COMMENT_SKELETON_MAX_SLOTS)
    }
}
