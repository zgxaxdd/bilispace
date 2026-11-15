package com.naaammme.bbspace.feature.comment

import kotlin.math.ceil

/**
 * 评论骨架槽位的估算高度（dp），对齐真实一级评论行高。
 *
 * 骨架行高与真实行高越接近，「骨架 → 内容」替换时的列表位移越小。
 *
 * 估值依据（本仓实测构成，`component/CommentReplyCard.kt`）：
 * 头像 `size(44.dp)` + 上下 `padding(vertical = 10.dp)` = 64dp 骨架基线，
 * 加上昵称行（约 18dp）、正文两行（约 2×16dp + 8dp 间距）、操作行（约 22dp）
 * 与块间距 —— 一级评论落在 120~150dp 区间，取 130dp 与上游同量级。
 *
 * ⚠️ 这个数字是**估值**，没有真机量过行高；它只用于决定"首屏铺几槽"，
 * 偏差一格不会造成功能问题（少铺 → 数据到达时列表被撑长一点；多铺 → 槽位在屏外，
 * LazyList 根本不会组合它们）。若日后真机量到明显不同的行高，改这里一个常量即可。
 */
internal const val COMMENT_SKELETON_SLOT_HEIGHT_DP = 130f

/** 首屏骨架槽位下限：至少铺满一屏可见区，否则数据到达时列表会明显被撑长。 */
internal const val COMMENT_SKELETON_MIN_SLOTS = 3

/**
 * 首屏骨架槽位上限。
 *
 * 为什么封顶 8：每个槽位是一个 `CommentCardSkeleton`，虽然共享同一个呼吸时钟
 * （见 `ProvideSkeletonShimmer` 的幂等），但仍是真实布局节点；平板/大屏上按
 * 视口高度算会得到 12+ 槽，而屏外槽位没有任何收益。
 */
internal const val COMMENT_SKELETON_MAX_SLOTS = 8

/**
 * 按可视高度估算首屏需要铺多少个骨架槽位。
 *
 * 宁可多铺一点（多余槽位在屏幕外，LazyList 不会组合它们，没有成本），也不要少铺 ——
 * 少铺会让列表在数据到达时突然被撑长，产生位移。
 *
 * 上游对应实现：BiliPai `VideoCommentLoadPolicy.resolveVideoCommentSkeletonSlotCount`
 * （`VIDEO_COMMENT_SKELETON_SLOT_HEIGHT_DP = 130f`、`coerceIn(3, 8)`），判据原样。
 *
 * @param viewportHeightDp 可见区高度（dp）。`<= 0`（未测量 / 异常）时返回下限。
 */
internal fun resolveCommentSkeletonSlotCount(viewportHeightDp: Int): Int {
    if (viewportHeightDp <= 0) return COMMENT_SKELETON_MIN_SLOTS
    val estimated = ceil(viewportHeightDp / COMMENT_SKELETON_SLOT_HEIGHT_DP).toInt()
    return estimated.coerceIn(COMMENT_SKELETON_MIN_SLOTS, COMMENT_SKELETON_MAX_SLOTS)
}
