package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.model.StoryFeedItem
import com.naaammme.bbspace.core.model.VideoDimension
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 「刷视频模式」竖屏短视频流仓库。
 *
 * 接口移植自 BiliPai 上游 `StoryApi.getStoryFeed`：
 * `GET https://app.bilibili.com/x/v2/feed/index/story`（App 签名通道，与首页
 * `x/v2/feed/index` 同一网络栈）。响应是**竖屏短视频流**——官方「故事模式」的数据源，
 * 每条自带 aid/cid/bvid 与完整封面/标题/UP 信息，天生适合上下滑连播；
 * `goto != "av"` 的非视频卡（直播/Banner 等）在解析层直接丢弃。
 *
 * 分页说明（BiliPai 同款取舍）：该接口**没有游标参数**（BiliPai 只拉一次作种子），
 * 无限加载由调用方接力 `FeedRepository.fetchFeed(idx)` 的首页推荐流完成。
 */
@Singleton
class StoryFeedRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authStore: AuthStore
) {

    companion object {
        private const val STORY_ENDPOINT = "x/v2/feed/index/story"

        /** 与 BiliPai 上游一致的取流参数（fnval 4048 = DASH 全格式）。 */
        private const val STORY_PAGE_SIZE = 20
    }

    /**
     * 拉一页竖屏短视频流。
     *
     * @param aid 起始视频（传入后返回「从该视频相关内容开始」的流；刷视频模式从
     *   入口视频进入时用它锚定起点）
     * @param bvid 同 [aid] 的另一种锚定方式（BiliPai 两个都透传）
     */
    suspend fun fetchStoryFeed(
        aid: Long = 0L,
        bvid: String = "",
        ps: Int = STORY_PAGE_SIZE
    ): List<StoryFeedItem> {
        val profile = BiliRestProfile.APP
        val ts = System.currentTimeMillis() / 1000
        val params = restParamBuilder.app(profile, ts, authStore.accessToken) + mapOf(
            "fnval" to "4048",
            "fnver" to "0",
            "force_host" to "0",
            "fourk" to "1",
            "qn" to "32",
            "ps" to ps.toString(),
            "aid" to aid.toString(),
            "bvid" to bvid
        )
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_APP}$STORY_ENDPOINT",
            params = params,
            profile = profile
        )
        return parseStory(json)
    }

    private fun parseStory(json: JSONObject): List<StoryFeedItem> {
        if (json.optInt("code", -1) != 0) return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val items = data.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val card = items.optJSONObject(i) ?: continue
                if (card.optString("goto") != "av") continue
                val playerArgs = card.optJSONObject("player_args") ?: continue
                val bvid = playerArgs.optString("bvid")
                if (bvid.isBlank()) continue
                val owner = card.optJSONObject("owner")
                // 画幅（SW-04）：卡片上的 dimension{width,height,rotate} 有则解析、
                // 无则 null——VideoDimension.from 自带"宽高非正 → null"的兜底。
                val dimension = card.optJSONObject("dimension")?.let { dim ->
                    VideoDimension.from(
                        width = dim.optLong("width"),
                        height = dim.optLong("height"),
                        rotate = dim.optLong("rotate")
                    )
                }
                add(
                    StoryFeedItem(
                        aid = playerArgs.optLong("aid"),
                        cid = playerArgs.optLong("cid"),
                        bvid = bvid,
                        title = card.optString("title"),
                        cover = card.optString("cover"),
                        author = owner?.optString("name")?.takeIf(String::isNotBlank),
                        authorMid = owner?.optLong("mid") ?: 0L,
                        durationSec = card.optLong("duration"),
                        dimension = dimension
                    )
                )
            }
        }
    }
}
