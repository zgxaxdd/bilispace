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
