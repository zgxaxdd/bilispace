package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.settings.FeedApiType

/**
 * 首页推荐流的**取流游标**（PT-10 §4.4）。
 *
 * 只有**首页**用它取流（经 `FeedRepository.fetchFeed(cursor, …)` 重载）；
 * 其余调用点（刷视频接力等）走恒为 app 协议的旧入口，不感知本类型。
 *
 * 为什么不能只用 `idx` 一个数：app 协议与 web 协议的翻页语义不同——
 * - app（`/x/v2/feed/index`）下发 `item.idx`，下一页要把它**原样回传**；
 * - web（`/x/web-interface/wbi/index/top/feed/rcmd`）**不返回任何游标**，只有本地的页序号。
 *
 * 所以两套游标**分开持有**，[MERGED] 档下各自独立推进（互不影响）。
 * MOBILE 档只用 [appIdx]、WEB 档只用 [webPage]，取值与历史行为逐字一致（避免默认路径漂移）。
 */
data class FeedCursor(
    /** app 协议游标（`idx` 参数）。MOBILE/MERGED 使用；WEB 恒为 0。 */
    val appIdx: Long = 0L,
    /** web 协议页序号（映射到 `fresh_idx` / `fresh_idx_1h` / `y_num`）。从 0 起。 */
    val webPage: Int = 0
) {
    companion object {
        /** 首屏 / 重置后的游标。 */
        val Initial = FeedCursor()
    }
}

/**
 * 游标推进策略（纯函数，可纯 JVM 单测 —— 方案 §6 V6 要求"翻 3 页 pageIndex 单调 +1 且不串页"）。
 *
 * ⚠️ 这是本方案唯一"容易静默错"的地方（错的表现是翻页重复 / 跳页），
 * 所以推进逻辑必须收口在这里、有测试钉住，而不是散在 ViewModel 里手写 `+1`。
 */
object FeedCursorPolicy {

    /**
     * 刷新（下拉 / 首屏）时的请求游标。
     *
     * MOBILE / MERGED 保留既有口径：app 游标 = 当前列表首项的 `idx`（历史行为，**不改**）。
     * WEB 无概况游标 → web 页序号归零。
     */
    fun refreshCursor(source: FeedApiType, appIdx: Long): FeedCursor = when (source) {
        FeedApiType.WEB -> FeedCursor(appIdx = 0L, webPage = 0)
        FeedApiType.MOBILE, FeedApiType.MERGED -> FeedCursor(appIdx = appIdx, webPage = 0)
    }

    /**
     * 加载更多时的请求游标。
     *
     * app 游标 = 当前列表末项的 `idx`（历史行为，**不改**）；web 页序号在上一页基础上 +1。
     */
    fun loadMoreCursor(source: FeedApiType, last: FeedCursor, appIdx: Long): FeedCursor = when (source) {
        FeedApiType.WEB -> FeedCursor(appIdx = 0L, webPage = last.webPage + 1)
        FeedApiType.MOBILE -> FeedCursor(appIdx = appIdx, webPage = 0)
        FeedApiType.MERGED -> FeedCursor(appIdx = appIdx, webPage = last.webPage + 1)
    }
}
