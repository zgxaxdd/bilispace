package com.naaammme.bbspace.core.swipe

/**
 * 连播队列的**条数上限**（报告 P1-8③ / §2.1.7）。
 *
 * ## 为什么要给上限
 * 队列此前**无界**：每翻一页、每来一批 relates 就 `appendSwipeEntries` 追加。
 * 两个后果：
 * 1. 长时间挂机刷视频时列表只涨不落（内存与 pager 页数）；
 * 2. 去重里的相似度档是 `accepted.any { … }` 的逐对比较（**O(队列长 × 新批长)**，
 *    关键词抽取虽有签名缓存，但那一层循环仍在），队列越长每次补货越慢 ——
 *    而它跑在调用方（ViewModel）线程上。上限把这一项从"随会话时长增长"变成常数。
 *
 * ## 一条硬约束：**绝不截断当前页及其之前**
 * `VerticalPager` 以**页号**为身份（`currentPage` 是下标）。若从头部截断，
 * 用户当前正在看的那一页会瞬间变成别的视频 —— 那是比"队列长"严重得多的事故。
 * 所以本策略只从**尾部**截，并且保证保留条数 ≥ `currentPage + 1`。
 *
 * ## 为什么不顺便保"运行时长上限"
 * 报告写的是"条数与运行时长上限"。条数上限已经把内存与去重成本钉死了；
 * 再加一条按时间的上限会引入"刷到一半队列被按时间清空"的行为，
 * 而那个行为**没有任何可观测的收益**（条数已经兜住）。故只做条数。
 */
object SwipeQueueBoundPolicy {

    /**
     * 队列条数上限。
     *
     * 200 的取舍：正常一次会话的 relates 总量远小于它（实测 seed + 3~5 批 refill
     * 通常几十条），所以日常刷视频**根本碰不到**它；只有挂机几小时的极端会话才会触发，
     * 而那正是需要兜住的那一类。取更小的值（如 50）会让"往回翻"很快翻到已截断的位置，
     * 反而更像 bug。
     */
    const val MAX_QUEUE_ENTRIES = 200

    /**
     * 把 [entries] 截到 [maxEntries] 以内，**只截尾部**。
     *
     * @param currentPage 当前页号（0 基）。保留条数至少为 `currentPage + 1`。
     */
    fun bound(
        entries: List<SwipeEntry>,
        currentPage: Int,
        maxEntries: Int = MAX_QUEUE_ENTRIES
    ): List<SwipeEntry> {
        if (maxEntries <= 0 || entries.size <= maxEntries) return entries
        val keep = maxOf(maxEntries, currentPage + 1).coerceAtMost(entries.size)
        if (keep >= entries.size) return entries
        return entries.subList(0, keep)
    }
}
