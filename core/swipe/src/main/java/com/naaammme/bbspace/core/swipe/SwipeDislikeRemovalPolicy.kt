package com.naaammme.bbspace.core.swipe

/**
 * 「不感兴趣」的**队列变更**判据（任务书 SW-24）。
 *
 * 语义（验收：当前页被移除 → 前进一次，不跳两支）：
 * - 队列 = 锚点之外的部分（`pages = [anchor] + queue`）；**锚点不可移除**（会话的页 0
 *   是入口视频，摘掉它等于换会话）→ 命中锚点时返回"未变更"；
 * - 移除当前页那一支后，**页号不变**（列表右移，同一页自然显示下一支）——这就是
 *   "前进一次"；绝不能顺手再 +1，否则会跳过一支；
 * - 被移除的是"还没滑到"的后方条目 → 页号与当前播放都无需变化；
 * - 被移除的是"已滑过"的前方条目 → 当前页索引要**左移一位**（否则当前页会指向
 *   下一支，用户会看到画面跳变）。
 *
 * 纯函数：调用方给队列与页号，返回新队列与"当前页索引是否需要补偿"。
 */
object SwipeDislikeRemovalPolicy {

    data class Removal(
        /** 变更后的队列（未命中/命中锚点时为原队列本身）。 */
        val queue: List<SwipeEntry>,
        /** 当前页索引需要减 1（移除的是已滑过的前方条目）；false = 保持不变。 */
        val shiftCurrentPageDown: Boolean,
        /** 是否真的移除了。 */
        val removed: Boolean,
    )

    /**
     * @param queue 当前队列（不含锚点）
     * @param anchor 会话锚点（页 0）；命中它时不可移除
     * @param target 要移除的条目
     * @param currentPage pager 当前页号（`pages` 坐标系，0 = 锚点）
     */
    fun remove(
        queue: List<SwipeEntry>,
        anchor: SwipeEntry?,
        target: SwipeEntry,
        currentPage: Int,
    ): Removal {
        val key = target.legacyKey()
        if (key.isBlank()) return Removal(queue, shiftCurrentPageDown = false, removed = false)
        if (anchor != null && anchor.legacyKey() == key) {
            // 锚点 = 会话入口，摘掉它等于换会话：不做（UI 侧对应"这一条没有不感兴趣入口"）
            return Removal(queue, shiftCurrentPageDown = false, removed = false)
        }
        val queueIndex = queue.indexOfFirst { it.legacyKey() == key }
        if (queueIndex < 0) return Removal(queue, shiftCurrentPageDown = false, removed = false)
        val pageOfTarget = queueIndex + 1 // pages 坐标系（页 0 是锚点）
        val updated = queue.toMutableList().apply { removeAt(queueIndex) }
        return Removal(
            queue = updated,
            // 目标在当前页之前 → 当前页左移一位；等于/早于当前页的情况都不加号，
            // 这样"移除当前页"就是"页号不变、内容前进一支"
            shiftCurrentPageDown = pageOfTarget < currentPage,
            removed = true
        )
    }
}
