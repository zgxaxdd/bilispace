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
    /**
     * app 协议游标（`idx` 参数）。MOBILE/MERGED 取流时使用。
     *
     * WEB 档**取流不用它**，但它必须跟着列表末项推进：WBI 密钥取不到时
     * `FeedRepository.fetchWebFeed` 会回退 `fetchMobile(appIdx)`（B-3/L-2），
     * 这里恒为 0 的话，回退就会**反复拉 app 协议第 0 页** —— 用户看到的是
     * "上拉加载更多不再出新内容"。
     */
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
     *
     * ## WEB 档为什么也要带上 app 游标（B-3 / L-2 修复）
     *
     * 修复前 WEB 档把 `appIdx` **强制归零**，理由是"不串用两套语义"。但
     * `FeedRepository.fetchWebFeed` 在 WBI 密钥取不到时会回退
     * `fetchMobile(cursor.appIdx, …)`：`appIdx` 恒 0 ⇒ 每次"加载更多"都去要 app 协议的
     * **第 0 页** ⇒ 反复拿到同一批内容、分页原地踏步（真机表现是"下拉没反应/重复内容"）。
     *
     * 现在 WEB 档的 `appIdx` 跟着列表末项走（与 [MERGED] 档的 app 半边同口径）：
     * - WBI 正常时它**完全不参与取流**（web 请求只看 `webPage`），行为零变化；
     * - WBI 失败时它正好是回退取的 app 游标 —— 第一次回退可能仍在第 0 页（列表尾部还是
     *   web 条目、`FeedItem.idx` 恒 0），但那一页的 app 条目自带真实 `idx`，从第二次回退
     *   起就继续往下翻，不会永久踏步。
     *
     * 刷视频接力侧的同类问题早在 `SwipeFeedCursorPolicy.advance`（两套游标同时 +1）里
     * 解决过，并写明了"若 appIdx 被归零，回退就会恒拉第 0 页"—— 首页这条是同一个病灶。
     */
    fun loadMoreCursor(source: FeedApiType, last: FeedCursor, appIdx: Long): FeedCursor = when (source) {
        FeedApiType.WEB -> FeedCursor(appIdx = appIdx, webPage = last.webPage + 1)
        FeedApiType.MOBILE -> FeedCursor(appIdx = appIdx, webPage = 0)
        FeedApiType.MERGED -> FeedCursor(appIdx = appIdx, webPage = last.webPage + 1)
    }
}
