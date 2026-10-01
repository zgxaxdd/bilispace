package com.naaammme.bbspace.core.swipe

/**
 * 等首帧 veil 的**退场策略**（任务书 SW-11）："快网不闪、慢网不空"。
 *
 * ## 问题（B 级证据：真机反馈）
 * veil 没有最短展示时长：快网下首帧几十毫秒就到，遮罩"闪一下就没了"
 * （用户问过"是不是故意拖慢"——反向的快侧问题）。但**补等待**本身也是行为变化：
 * 只能靠眼睛判的改动不得改默认行为（§8.5-17），所以本轮只落
 * **策略 + 编译期默认关**（[MIN_VEIL_VISIBLE_MS_DEFAULT] = 0 = 首帧即撤，与现状逐帧一致）；
 * 真机确认"快网仍闪"后，把常量调到 200~400ms 即开启（L4-23 复测项）。
 *
 * ## 判据
 * - **只补足、不新增等待**：已等待 ≥ min 时立即退场（而不是把 min 重算一遍）；
 * - **失败不撤 veil**：取流/解码失败时错误态 + 重试按钮就在 veil 里呈现
 *   （守卫 `swipeWaitsFirstFrameBehindLoadingVeil` 锁的行为），撤掉 veil 就是
 *   "纯黑 + 大暂停键"的历史回归。"失败态不吃最短时长"指的是**错误呈现本身**
 *   立即发生（由 playbackState.error 直接驱动，不经本策略）；
 * - **中途切页**：等待起点跟随页切换重置（调用方保证 `visibleSinceMs` 随页更新），
 *   本函数不跨页记账。
 */
object SwipeWaitPolicy {

    /**
     * 最短展示时长的**出厂默认**：0 = 策略关（首帧即撤，现状行为）。
     * 源码守卫盯着这个值——开启必须走真机结论 + 本守卫同批更新。
     */
    const val MIN_VEIL_VISIBLE_MS_DEFAULT = 0L

    /**
     * @param firstFrameRendered 首帧是否已渲染
     * @param visibleSinceMs veil 开始展示的时钟毫秒；null = 不在等待态
     * @param nowMs 当前时钟毫秒（调用方给钟，保持纯函数）
     * @param minVisibleMs 最短展示时长；0 = 策略关
     * @param playbackFailed 当前页失败（veil 保留并切错误态，**不撤**）
     * @return 0 = 立即撤 veil；>0 = 还差多少毫秒补足最短展示；null = 不撤（首帧未到/失败/不在等待）
     */
    fun retireDelayMs(
        firstFrameRendered: Boolean,
        visibleSinceMs: Long?,
        nowMs: Long,
        minVisibleMs: Long = MIN_VEIL_VISIBLE_MS_DEFAULT,
        playbackFailed: Boolean = false,
    ): Long? {
        if (visibleSinceMs == null) return null
        if (playbackFailed) return null
        if (!firstFrameRendered) return null
        if (minVisibleMs <= 0L) return 0L
        val elapsed = nowMs - visibleSinceMs
        if (elapsed >= minVisibleMs) return 0L
        return minVisibleMs - elapsed
    }
}
