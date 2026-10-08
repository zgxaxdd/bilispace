package com.naaammme.bbspace.feature.video.swipesource

import com.naaammme.bbspace.core.feed.FeedCursor
import com.naaammme.bbspace.core.swipe.source.SwipeCursor

/**
 * 刷视频接力的**来源自持游标**换算（SX-04 / 决策 D-1）——纯函数，可纯 JVM 单测。
 *
 * ## 为什么需要它
 * 接力的取流入口已从"恒 app 协议的旧入口 `fetchFeed(idx, …)`"改为
 * **按设置分派的游标重载** `fetchFeed(cursor, …)`
 * （[com.naaammme.bbspace.core.feed.FeedRepository] 内 `appSettings.feedApiType.first()`）。
 * app 协议与 web 协议的翻页语义不同（见 [FeedCursor] 的 KDoc）：
 * - app（MOBILE，及 MERGED 的 app 半边）：服务端 `idx` 游标；
 * - web（WEB，及 MERGED 的 web 半边）：**不下发任何游标**，只有本地页序号。
 *
 * 所以两套游标必须**同时**随接力推进，否则 web 档会拿同一个页号反复拉同一页
 * （静默死循环——这正是上一会话"接力恒走 app 协议"那条偏离要规避的风险，
 * 见 `docs/2026-10-03-BiliPai移植-执行报告.md`）。决策 D-1 之后改由本策略正面解决。
 *
 * ## 为什么不直接复用 [com.naaammme.bbspace.core.feed.FeedCursorPolicy]
 * 首页的口径原本是"**档位**决定推哪个字段"（WEB 档把 `appIdx` 归零、MOBILE 档把 `webPage` 归零）。
 * 接力不能照搬这个归零：WEB 档下 `FeedRepository.fetchWebFeed` 在 WBI 密钥取不到时会
 * **回退** `fetchMobile(cursor.appIdx, …)`（该函数的 catch 分支），若 `appIdx` 被归零，
 * 回退就会恒拉第 0 页。因此接力对两个字段**同时 +1**，未参与当前档位的那个字段递增无副作用：
 * - MOBILE：只有 `appIdx` 参与取流，推进值与旧实现 `idx + 1` **逐字等价**（默认零回归红线）；
 * - WEB：只有 `webPage` 参与取流，`appIdx` 退化为回退页码；
 * - MERGED：两路各自推进（与 SX-12 联动）。
 *
 * > 2026-10-04（B-3/L-2）：首页那条**同一个病灶**也已修掉 ——
 * > `FeedCursorPolicy.loadMoreCursor(WEB, …)` 不再把 `appIdx` 归零，改为跟随列表末项。
 * > 两处口径现在一致：**取流只看当前档位对应的字段，但另一个字段必须可推进**。
 *
 * `FeedCursor` 类型本身仍然复用 core:feed 的那一个，不另造。
 */
object SwipeFeedCursorPolicy {

    /**
     * 接力游标 → 取流游标。
     * 负值夹到 0：与旧实现 `cursor.idx.coerceAtLeast(0L)` 同口径（不改变任何历史行为）。
     */
    fun toFeedCursor(cursor: SwipeCursor): FeedCursor = FeedCursor(
        appIdx = cursor.idx.coerceAtLeast(0L),
        webPage = cursor.webPage.coerceAtLeast(0)
    )

    /**
     * `SwipeSourceBatch.nextCursor` → 取流游标；
     * `null`（源到头 / 空批）回落到 [FeedCursor.Initial]——与旧实现的 `?: 0L` 同义。
     */
    fun toFeedCursorOrInitial(cursor: SwipeCursor?): FeedCursor =
        cursor?.let(::toFeedCursor) ?: FeedCursor.Initial

    /** 取流游标 → 接力游标（回填给 `SwipeSourceBatch.nextCursor`）。 */
    fun toSwipeCursor(cursor: FeedCursor, sourceId: String): SwipeCursor =
        SwipeCursor(sourceId = sourceId, idx = cursor.appIdx, webPage = cursor.webPage)

    /** 首屏 / 换来源重置后的接力游标（app 与 web 两套分页空间都不能混用）。 */
    fun initial(sourceId: String): SwipeCursor = toSwipeCursor(FeedCursor.Initial, sourceId)

    /**
     * 推进一格：两个分页空间**同时** +1。
     *
     * MOBILE 档的 `appIdx + 1` 与改动前 `SwipeCursor(sourceId = id, idx = idx + 1)`
     * 逐字等价 —— 这是"默认路径零回归"的红线，由 `SwipeFeedCursorPolicyTest` 钉住。
     */
    fun advance(cursor: FeedCursor): FeedCursor = FeedCursor(
        appIdx = cursor.appIdx + 1L,
        webPage = cursor.webPage + 1
    )
}
