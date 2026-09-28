package com.naaammme.bbspace.feature.video.swipe

/**
 * 队列补货的"凑满"判据（对照 BiliPai `PortraitVideoPager.kt:1341-1412`）。
 *
 * 背景：单页推荐流里大量条目会被去重/UP 多样性挡掉（实测一页 10 条只进 2~4 条），
 * "拉一页就收工"会让队列很快见底 —— 用户滑到尽头就是"没有下一个视频了"。
 * BiliPai 的解法是**最多拉 3 页、直到凑够 8 条**；这里只搬这两个数字，
 * 判据抽成纯函数以便单测（不要把它写进协程里，那样只能靠真机试）。
 */
fun shouldFetchNextRefillPage(
    attempts: Int,
    appended: Int,
    maxAttempts: Int = SWIPE_REFILL_MAX_ATTEMPTS,
    targetAppended: Int = SWIPE_REFILL_TARGET,
): Boolean {
    if (attempts < 0 || appended < 0) return false
    if (attempts >= maxAttempts) return false
    return appended < targetAppended
}

/** 最多拉几页（BiliPai 同值）。 */
internal const val SWIPE_REFILL_MAX_ATTEMPTS = 3

/** 一轮补货希望净增几条（BiliPai 同值：8 条已验证候选）。 */
internal const val SWIPE_REFILL_TARGET = 8

/**
 * 兜底触发判据：推荐流拉满页数后仍没凑够 [SWIPE_REFILL_TARGET] 条 → 该走 related 兜底。
 *
 * 单独抽出来是为了可测：真机上"到底有没有走兜底"只能靠日志，
 * 而这条判据写错的后果是"永远不走兜底"（队列照样见底）或"每次都走"（白拉一次详情）。
 */
fun shouldRunSwipeRefillFallback(
    appended: Int,
    target: Int = SWIPE_REFILL_TARGET,
): Boolean = appended in 0 until target

/**
 * 兜底目标选择：推荐流拉满 3 页仍凑不满时，用**队尾那一支的"相关推荐"**再补一轮
 * （零新接口：详情响应自带 relates，与播放时的接力链同一条，去重交给 `appendSwipeEntries`）。
 *
 * 为什么挑队尾而不是队首：队尾就是用户**下一个会滑到**的位置，把它后面的内容补厚，
 * 才不会出现"滑到最后一条就没有了"。
 *
 * 要求 `aid > 0`：详情接口按 aid/bvid 查询，两者都缺的条目查不了（返回 null）。
 */
fun selectSwipeRefillFallbackTarget(queue: List<SwipeEntry>): SwipeEntry? =
    queue.lastOrNull { it.target.aid > 0L }
