package com.naaammme.bbspace.core.swipe.model

/**
 * 刷视频条目的**类型化身份键**（任务书 §4.4 / SW-03）。
 *
 * ## 为什么值得类型化（A 级：C7 真机事故根因）
 * `SwipeEntry` 旧版用字符串 `key`（`swipeKey`：bvid 优先、aid 兜底）同时充当两个身份：
 * - **去重键**（"同一目标别重复发请求/别重复入队"）；
 * - **页身份**（"这一页是不是当前正在播放的视频"）。
 *
 * 但这两个身份的**比较时刻不同**：队列条目在建队时算（relate 卡只有 aid →
 * `aid:117339261503379`），pager 的 currentKey 从取流结果算（bvid 被服务端补齐 →
 * `BV…`）。同一条目在"取流中"匹配、取流完成的一瞬间失配——页身份翻转、播放器页落空
 * （症状"可以滑动但无法播放下一个视频"）。`SwipeEntry.isCurrentVideo` 为此改成按内容比
 * （aid 优先），但字符串键仍然把两个语义**在类型上**混在一起，新的调用点随时可能
 * 再把"比字符串"写回去。
 *
 * 类型化后：**页身份一律走 [SwipeId]**（[SwipeId.matches] 内置 aid 优先语义，
 * 对"bvid 事后补齐"天然免疫）；`SwipeEntry.legacyKey()` 仅供日志与去重 map，
 * 不再参与"是不是同一页"的判断。[Media](SwipeId.Media) 为多P/合集预留（SW-41）。
 */
sealed interface SwipeId {

    /** 只有 aid（relate 卡/首页流的常见形态：bvid 靠本地 avToBv 补，可能补不出来）。 */
    data class Av(val aid: Long) : SwipeId

    /** 只有 bvid（aid 缺失的特殊条目）。 */
    data class Bv(val bvid: String) : SwipeId

    /** 两侧都有（bvid 补齐后的常态）。 */
    data class Both(val aid: Long, val bvid: String) : SwipeId

    /**
     * 复合键：同一 bvid 下的具体分P/剧集（bvid#cid）。多P follow-up 注入（SW-41）
     * 的前置依赖——同 aid 异 cid **不是**同一目标。
     */
    data class Media(val bvid: String, val cid: Long) : SwipeId

    /**
     * 这一侧身份是否指向 [current] 那一侧的同一目标。
     *
     * 判据（与 `SwipeEntry.isCurrentVideo` 逐条对齐，勿改）：
     * 1. **aid 优先**：两侧都能解析出有效 aid（> 0）时以 aid 为准——aid 是 bvid 的
     *    1:1 另一侧表示，对"bvid 后来才补齐"天然免疫（C7 的修复点）；
     * 2. aid 不可比时退回 bvid（trim 后非空全等）；
     * 3. [Media] 只与同 bvid 且同 cid 的 [Media] 相等（复合键是"具体到分P"的身份）。
     */
    fun matches(current: SwipeId?): Boolean {
        current ?: return false
        if (this is Media || current is Media) {
            return this is Media && current is Media &&
                this.bvid == current.bvid && this.cid == current.cid
        }
        val selfAid = when (this) {
            is Av -> aid
            is Both -> aid
            else -> 0L
        }
        val otherAid = when (current) {
            is Av -> current.aid
            is Both -> current.aid
            else -> 0L
        }
        if (selfAid > 0L && otherAid > 0L) return selfAid == otherAid
        val selfBvid = when (this) {
            is Bv -> bvid
            is Both -> bvid
            else -> null
        }?.trim()
        val otherBvid = when (current) {
            is Bv -> current.bvid
            is Both -> current.bvid
            else -> null
        }?.trim()
        return !selfBvid.isNullOrEmpty() && selfBvid == otherBvid
    }

    companion object {
        /**
         * 从"bvid / aid 至多一个可用"的原始数据构造身份。
         * 两侧都缺（无 aid 且 bvid 空白）返回 `Bv("")`——它与任何身份的
         * [SwipeId.matches] 都是 false（blank 防御），调用方无需再判空。
         */
        fun of(bvid: String?, aid: Long): SwipeId {
            val trimmed = bvid.orEmpty().trim()
            return when {
                trimmed.isNotEmpty() && aid > 0L -> Both(aid = aid, bvid = trimmed)
                aid > 0L -> Av(aid = aid)
                else -> Bv(bvid = trimmed)
            }
        }
    }
}
