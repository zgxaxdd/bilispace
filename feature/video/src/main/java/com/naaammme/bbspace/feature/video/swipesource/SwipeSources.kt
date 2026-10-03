package com.naaammme.bbspace.feature.video.swipesource

import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.feed.FeedRepository
import com.naaammme.bbspace.core.feed.StoryFeedRepository
import com.naaammme.bbspace.core.swipe.source.SwipeCursor
import com.naaammme.bbspace.core.swipe.source.SwipeSource
import com.naaammme.bbspace.core.swipe.source.SwipeSourceBatch
import com.naaammme.bbspace.core.swipe.source.SwipeSourceCost
import com.naaammme.bbspace.core.swipe.swipeEntryFromFeedItem
import com.naaammme.bbspace.core.swipe.swipeEntryFromStory

/** 首页推荐流源：起步与补货的主力（Light：单请求分页，蜂窝可用）。 */
class HomeFeedSwipeSource(
    private val feedRepository: FeedRepository,
) : SwipeSource {
    override val id: String = ID
    override val cost: SwipeSourceCost = SwipeSourceCost.Light
    override val diversity: Float = 1f

    override suspend fun next(cursor: SwipeCursor, limit: Int): SwipeSourceBatch {
        // SX-04（决策 D-1）：接力不再直连"恒 app 协议"的旧入口 `fetchFeed(idx, …)`，
        // 改走**感知推荐来源**的游标重载 —— 用户在设置里选 WEB/MERGED 时，刷视频的内容
        // 构成随之变化（web 只有普通视频、没有"不感兴趣"），这是**有意的默认行为变化**；
        // 默认 MOBILE 档的取流参数与推进值与改动前逐字一致（零回归红线）。
        // 游标由接力自己持有：app 侧 = FeedCursor.appIdx，web 侧 = FeedCursor.webPage。
        val feedCursor = SwipeFeedCursorPolicy.toFeedCursor(cursor)
        val result = runCatchingCancellable {
            feedRepository.fetchFeed(cursor = feedCursor, pull = false, flush = 0)
        }.getOrNull() ?: return emptyBatch()
        if (result.items.isEmpty()) return emptyBatch()
        // SW-04 的三源映射结论在源实现处落地（守卫盯着这里不许退回 VM）
        val entries = result.items.mapNotNull(::swipeEntryFromFeedItem)
        return SwipeSourceBatch(
            sourceId = id,
            entries = entries,
            nextCursor = SwipeFeedCursorPolicy.toSwipeCursor(
                SwipeFeedCursorPolicy.advance(feedCursor),
                id
            )
        )
    }

    private fun emptyBatch(): SwipeSourceBatch = SwipeSourceBatch(sourceId = id, entries = emptyList(), nextCursor = null)

    companion object {
        const val ID = "home-feed"
    }
}

/**
 * story 竖屏流源：**只作起步兜底**（首页流拿不到时保证"点进去一定有内容"）。
 * Heavy：整份 feed 响应比一次分页重，且仅起步用一次——蜂窝门控对它生效时，
 * 起步编排会显式绕过（起步必须尽力有内容，见 VideoViewModel 的接线注释）。
 */
class StoryFeedSwipeSource(
    private val storyFeedRepository: StoryFeedRepository,
    private val seedAid: Long,
    private val seedBvid: String,
) : SwipeSource {
    override val id: String = ID
    override val cost: SwipeSourceCost = SwipeSourceCost.Heavy
    override val diversity: Float = 0.5f

    override suspend fun next(cursor: SwipeCursor, limit: Int): SwipeSourceBatch {
        val items = runCatchingCancellable {
            storyFeedRepository.fetchStoryFeed(aid = seedAid, bvid = seedBvid)
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return SwipeSourceBatch(id, emptyList(), null)
        return SwipeSourceBatch(
            sourceId = id,
            entries = items.mapNotNull(::swipeEntryFromStory),
            // 一次性源：问过即到头（起步不需要续读）
            nextCursor = null
        )
    }

    companion object {
        const val ID = "story"
    }
}
