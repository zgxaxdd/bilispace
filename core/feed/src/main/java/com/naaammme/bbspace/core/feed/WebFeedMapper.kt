package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.common.media.httpsImageUrl
import com.naaammme.bbspace.core.model.DescButton
import com.naaammme.bbspace.core.model.FeedArgs
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import java.util.Locale
import org.json.JSONObject

/**
 * WEB 推荐流（`x/web-interface/wbi/index/top/feed/rcmd`）→ [FeedItem] 的纯映射器（PT-10 §4.3）。
 *
 * **为什么是独立对象而不是 `FeedRepository` 里的私有方法**：web 响应结构与 app 协议
 * 完全不同，映射规则要能**脱离 Android / 网络做纯 JVM 单测**（方案 §6 V4/V5）。
 *
 * ## 与 app 协议的结构性差异（必须在设置页告知用户，见 §4.3）
 * 1. **无 `idx` 游标**：web item 不带翻页游标 → [FeedItem.idx] 恒为 `0`（翻页由 [FeedCursor.webPage] 承担）。
 * 2. **无三连点 / 无 `dislikeContext`**：WEB 档下首页卡片的"不感兴趣"不可用
 *    → [FeedItem.dislikeContext] 恒为 `null`，`HomeViewModel.submitDislike` 会走"暂不支持"分支。
 * 3. **只有 UGC 视频**：没有直播卡 / 番剧卡 / 课卡 / 动态卡，内容构成比 app 档"纯"。
 */
internal object WebFeedMapper {

    /** web 卡片类型（仅用于 `contentType` 复用分组，取值稳定即可 —— 方案 §4.3）。 */
    const val WEB_CARD_TYPE = "web_small_cover"

    /** web 流恒为普通视频卡。 */
    const val WEB_GOTO = "av"

    /**
     * 解析整包响应：`{ code, message, data: { item: [...] } }`。
     *
     * 与上游 `SharedContentRepository.recommendations` 同口径：
     * - `code != 0` 直接抛错（非静默空列表 —— 风控/限流要能被看见，方案 §8 F6）；
     * - 跳过无法路由的条目（`id <= 0` 且 `bvid` 为空）。
     */
    fun mapResponse(json: JSONObject): List<FeedItem> {
        val code = json.optInt("code")
        if (code != 0) {
            error("web 推荐流返回 code=$code msg=${json.optString("message")}")
        }
        val arr = json.optJSONObject("data")?.optJSONArray("item") ?: return emptyList()
        val result = ArrayList<FeedItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            mapItem(obj)?.let(result::add)
        }
        return result
    }

    /**
     * 单条 web item → [FeedItem]。**缺字段不崩**：任意字段缺失都退化为默认值/空串
     * （方案 §7："上游模型可能滞后于服务端"）。
     */
    fun mapItem(obj: JSONObject): FeedItem? {
        val aid = obj.optLong("id")
        val bvid = obj.optString("bvid").takeIf(String::isNotBlank)
        if (aid <= 0L && bvid == null) return null
        val owner = obj.optJSONObject("owner")
        val upName = owner?.optString("name")?.takeIf(String::isNotBlank)
        val upMid = owner?.optLong("mid") ?: 0L
        val stat = obj.optJSONObject("stat")
        val cid = obj.optLong("cid").takeIf { it > 0L } ?: 0L
        val target = if (aid > 0L) {
            VideoTarget.Ugc(
                aid = aid,
                cid = cid,
                bvid = bvid,
                src = VideoTargetTool.feed()
            )
        } else {
            null
        }
        return FeedItem(
            cardType = WEB_CARD_TYPE,
            cardGoto = WEB_GOTO,
            goto = WEB_GOTO,
            param = aid.toString(),
            uri = "bilibili://video/$aid",
            title = obj.optString("title"),
            cover = normalizeWebUrl(obj.optString("pic")),
            // 角标：左=时长、右=播放量（与 app 协议 `cover_left_text_1` / `cover_right_text` 同义）
            coverLeftText1 = formatDuration(obj.optInt("duration")),
            coverLeftText2 = null,
            coverRightText = formatViewCount(stat?.optLong("view") ?: 0L),
            idx = 0L,
            target = target,
            liveRoute = null,
            descButton = upName?.let { DescButton(text = it, uri = "") },
            rcmdReason = null,
            args = FeedArgs(
                upId = upMid,
                upName = upName,
                tid = obj.optInt("tid"),
                tname = obj.optString("tname").takeIf(String::isNotBlank),
                aid = aid
            ),
            threePointV2 = null,
            // web item 无三连点 → 无负反馈上下文（V5 守卫钉死这一条）
            dislikeContext = null
        )
    }

    /**
     * 秒数 → 角标时长文本（`12:34` / `1:02:03`）。
     *
     * 与 `feature/rank/RankScreen.kt` 的 `formatDuration` **逐字同口径** ——
     * 同一 UI 里两个来源必须渲染出同一种文本，否则用户能看出"这张卡的时长格式不一样"。
     * 用 [Locale.ROOT] 显式指定：默认 locale 在阿拉伯语等环境下会输出本地数字。
     */
    fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    /**
     * 播放量 → 角标文本（`1234` / `1.2万` / `3.4亿`）。
     *
     * 与 `feature/rank/RankScreen.kt` 的 `formatRankCount` + `trimRankNumber`
     * 逐字同口径（含"整数不带小数点"的裁剪规则）。
     */
    fun formatViewCount(count: Long): String? {
        if (count <= 0L) return null
        return when {
            count >= 100_000_000L -> trimNumber(count / 100_000_000.0) + "亿"
            count >= 10_000L -> trimNumber(count / 10_000.0) + "万"
            else -> count.toString()
        }
    }

    private fun trimNumber(value: Double): String {
        val rounded = (value * 10).toInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    /**
     * web 图片地址归一化。
     *
     * ⚠️ **任务书 §4.3 说"走 httpsImageUrl() 即可"是不准确的**：本仓库的
     * [`httpsImageUrl`] 只补 `http://`，对 web 返回的**协议相对地址**（`//i0.hdslb.com/...`）
     * 会原样返回 —— 拿到 `//` 开头的地址下游（Coil）无法加载。所以先补协议再交给它。
     */
    internal fun normalizeWebUrl(raw: String): String {
        if (raw.isBlank()) return raw
        return if (raw.startsWith("//")) "https:$raw".httpsImageUrl() else raw.httpsImageUrl()
    }
}
