package com.naaammme.bbspace.feature.video.swipe

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.StoryFeedItem
import com.naaammme.bbspace.core.model.VideoRelate
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool

/**
 * 竖屏全屏「上下滑切换视频」（BiliPai 上游 PortraitVideoPager 同款功能的 bbspace 化）的
 * **纯数据层**：滑动队列的条目、去重/多样性合并、补流触发判据。
 *
 * 与 BiliPai 的对应关系（移植时对照过 `PortraitPagerSwitchPolicy.kt` / `PortraitVideoLoadPolicy.kt`）：
 * - BiliPai 用 `RelatedVideo`/`ViewInfo` 两型混排；bbspace 统一为轻量 [SwipeEntry]
 *   （bbspace 切视频走 `VideoViewModel.openVideo` 完整管线，取流/弹幕/历史都不需要重模型）；
 * - BiliPai 的标题签名相似去重（关键词交集 / 时长差）压缩为「规范化标题 + 封面 key」两档——
 *   bbspace 的接力流来自各视频自己的相关推荐（同 UP 互推重复率高），bvid + 标题 + 封面
 *   三档硬去重已覆盖实测重复场景，关键词分词的收益/成本比不划算；
 * - UP 主多样性（最近窗口内同 UP 限额）原样保留，但按作者名字符串判（VideoRelate 无 mid）。
 */

/** 滑动队列条目：页 0（锚点 = 进入全屏时的视频）与后续页共用。 */
@Immutable
data class SwipeEntry(
    val target: com.naaammme.bbspace.core.model.VideoTarget.Ugc,
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String?,
    val durationText: String?,
) {
    val displayAuthor: String? get() = author?.takeIf(String::isNotBlank)

    /**
     * 条目身份键（**用于请求去重**：`requestedKey` 防目标震荡）：bvid 优先、aid 兜底。
     *
     * 为什么需要 aid 兜底（真机 bug 修复）：bbspace 的相关推荐来自详情响应的 relate
     * 模块，其 `basic.uri` 是 **aid 格式**（`bilibili://video/{aid}`），bvid 解析为空
     * （BiliPai 的 relate 是独立接口、JSON 直接带 bvid，所以上游没有这个问题）。
     * 早期版本只认 bvid → 整页 40 条推荐全部被丢弃、滑动队列为空、pager 只有一页
     * ——真机症状"无法上滑、没有内容"。身份键必须覆盖两种形态。
     *
     * ⚠️ 这个**字符串**只适合做"同一目标别重复发请求"的去重，**不能**用来判断
     * "这一页是不是当前正在播放的那一页"——见 [isCurrentVideo]。
     */
    val key: String get() = swipeKey(bvid, target.aid)

    /**
     * **这一页是不是「当前正在播放的视频」**（pager 用它决定哪一页组合播放器）。
     *
     * ## 为什么不能直接比 [key] 字符串（真机 bug：翻页后新视频不播放）
     *
     * 两侧的 key 是**在不同时刻**算出来的：
     * - 队列条目在**建队时**算：relate 卡没有 bvid → `key = "aid:117339261503379"`；
     * - pager 的 `currentKey` 从 `videoState.ids` 算，而 `ids` 在**取流/详情返回后会被
     *   解析结果整体覆盖**（`applyDetailResult`：`ids = detailResult.ids`，此时 bvid 已被
     *   服务端补齐）→ `currentKey` 变成 `"BV…"`。
     *
     * 于是同一条目在"取流中"匹配、**取流完成的一瞬间失配**：页身份翻转，播放器页
     * 落空 → 用户看到的是封面页 + 永远不起播（真机症状"可以滑动，但无法播放下一个
     * 视频"）。锚点页（页 0，bvid 本来就全）不受影响，所以只有后续页坏。
     *
     * 判据因此改为**按内容比**、而不是按字符串比：aid 是 bvid 的 1:1 另一侧表示，
     * 两侧都有 aid 时以 aid 为准（对"bvid 后来才补齐"天然免疫）；只有 aid 缺失的
     * 特殊条目才退回比 bvid。这样"取流前/取流后"两个阶段结论一致。
     */
    fun isCurrentVideo(bvid: String?, aid: Long): Boolean {
        if (target.aid > 0L && aid > 0L) return target.aid == aid
        val entryBvid = this.bvid.trim()
        val playingBvid = bvid.orEmpty().trim()
        return entryBvid.isNotEmpty() && entryBvid == playingBvid
    }
}

/** 身份键：bvid 优先、aid 兜底（[SwipeEntry.key] 与 pager 的 currentKey 必须同构）。 */
fun swipeKey(bvid: String?, aid: Long): String =
    bvid.orEmpty().trim().ifEmpty { "aid:$aid" }

/** 锚点条目（页 0）由 VideoViewModel.beginSwipeSession 直接构造：target 必须取栈顶的
 *  activeTarget（完整 aid/cid/src），bvid-only 的裸 target 会让翻回页 0 的取流多走兜底。 */

/** 相关推荐 → 队列条目；bvid / aid 全缺（如番剧/课程卡）才丢弃。 */
fun swipeEntriesFromRelates(relates: List<VideoRelate>): List<SwipeEntry> =
    relates.mapNotNull { relate ->
        val target = relate.target.withBvid()
        val bvid = target.bvid.orEmpty().trim()
        if (bvid.isEmpty() && target.aid <= 0L) {
            null
        } else {
            SwipeEntry(
                target = target,
                bvid = bvid,
                title = relate.title,
                cover = relate.cover,
                author = relate.author,
                durationText = relate.durationText
            )
        }
    }

/** 竖屏故事流（`x/v2/feed/index/story`）条目 → 队列条目。 */
fun swipeEntryFromStory(item: StoryFeedItem): SwipeEntry? {
    val target = VideoTarget.Ugc(
        aid = item.aid,
        cid = item.cid,
        bvid = item.bvid.trim().takeIf(String::isNotEmpty)
    ).withBvid()
    val bvid = target.bvid.orEmpty().trim()
    if (bvid.isEmpty() && target.aid <= 0L) return null
    return SwipeEntry(
        target = target,
        bvid = bvid,
        title = item.title,
        cover = item.cover,
        author = item.author,
        durationText = item.durationSec.takeIf { it > 0L }?.let(::formatSwipeDuration)
    )
}

/** 首页推荐流条目 → 队列条目（补货接力用）；无视频目标 / 直播卡丢弃。 */
fun swipeEntryFromFeedItem(item: FeedItem): SwipeEntry? {
    val target = (item.target as? VideoTarget.Ugc)?.withBvid() ?: return null
    val bvid = target.bvid.orEmpty().trim()
    if (bvid.isEmpty() && target.aid <= 0L) return null
    return SwipeEntry(
        target = target,
        bvid = bvid,
        title = item.title,
        cover = item.cover,
        author = item.args?.upName,
        durationText = null
    )
}

/**
 * 目标补全：bvid 缺失时用**本地换算**补齐（[com.naaammme.bbspace.core.model.VideoTargetTool.avToBv]，
 * 移植自 BiliPai `IdUtils`）。
 *
 * 三个数据源都可能只给 aid：
 * - 详情响应的 relate 卡（`basic.uri = bilibili://video/{aid}`，已在 `mapRelates` 处补过一次，
 *   这里是第二层防线——任何新调用点漏补都不会退化成 aid-only 条目）；
 * - 首页推荐流的 App 接口（新视频的 `player_args` 可能不带 bvid，uri 又是 aid 格式）；
 * - story 流的 `player_args.bvid` 为空时。
 *
 * 为什么值得补：`bvid` 是整条链路（页身份、历史、弹幕、上报、分享）的主键；BiliPai 直接
 * **丢弃**没有 bvid 的推荐卡（`toRelatedVideoForPortraitRecommendation` 里 `bvid.isEmpty() → null`），
 * 那会白白丢掉本来就稀缺的接力条目——本地换算能拿到真值（真机实测：换算结果与取流后
 * 服务端解析出的 bvid 完全一致），所以选择补齐而不是丢弃。
 * 换算不出来（aid 非法/超范围）时原样返回，由调用方按 aid 兜底。
 */
private fun VideoTarget.Ugc.withBvid(): VideoTarget.Ugc =
    if (!bvid.isNullOrBlank() || aid <= 0L) this else copy(bvid = VideoTargetTool.avToBv(aid))

private fun formatSwipeDuration(totalSec: Long): String {
    val hours = totalSec / 3_600
    val minutes = (totalSec % 3_600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * 标题规范化：截断括号段（【】[]() 等常带推荐后缀）、保留字母数字并小写化。
 * 同一视频被多个 UP 互推时标题常带不同的前后缀括号，规范化后才能命中去重。
 */
fun normalizeSwipeTitle(title: String): String {
    val open = title.indexOfAny(charArrayOf('【', '[', '（', '('))
    val head = if (open < 0) title else title.substring(0, open)
    return head.lowercase().filter { it.isLetterOrDigit() }
}

/** 封面 key：取路径末段去 query 与 @ 尺寸后缀 —— 同图不同 CDN 参数视为同图。 */
fun swipeCoverKey(pic: String): String =
    pic.substringBefore('?')
        .substringBefore('@')
        .substringAfterLast('/')
        .lowercase()

/**
 * 把新拿到的相关推荐合并进滑动队列：**只追加、不重排**（保护用户当前所在页）。
 *
 * - 身份键（[SwipeEntry.key]，bvid 优先 / aid 兜底）硬去重（含锚点与已在队列内的）；
 * - 规范化标题相同、或封面 key 相同 → 视为同一视频的重复推荐；
 * - UP 主多样性：已接受条目的最近 [recentWindow] 条里，同一作者已占
 *   [maxPerOwnerPerWindow] 条时不收（BiliPai 同款「最近 12 条同 UP 最多 2 条」）。
 */
fun appendSwipeEntries(
    existing: List<SwipeEntry>,
    incoming: List<SwipeEntry>,
    anchorKey: String,
    recentWindow: Int = SWIPE_OWNER_DIVERSITY_WINDOW,
    maxPerOwnerPerWindow: Int = SWIPE_MAX_PER_OWNER,
): List<SwipeEntry> {
    if (incoming.isEmpty()) return existing
    val accepted = existing.toMutableList()
    val seenKeys = accepted.mapTo(mutableSetOf()) { it.key }
    anchorKey.trim().takeIf(String::isNotEmpty)?.let(seenKeys::add)
    val seenTitles = accepted.mapNotNullTo(mutableSetOf()) {
        normalizeSwipeTitle(it.title).takeIf(String::isNotEmpty)
    }
    val seenCovers = accepted.mapNotNullTo(mutableSetOf()) {
        swipeCoverKey(it.cover).takeIf(String::isNotEmpty)
    }
    incoming.forEach { candidate ->
        val key = candidate.key
        val title = normalizeSwipeTitle(candidate.title)
        val cover = swipeCoverKey(candidate.cover)
        val owner = candidate.displayAuthor
        if (key in seenKeys) return@forEach
        if (title.isNotEmpty() && title in seenTitles) return@forEach
        if (cover.isNotEmpty() && cover in seenCovers) return@forEach
        if (owner != null) {
            val recentOwners = accepted.takeLast(recentWindow.coerceAtLeast(1))
                .mapNotNull { it.displayAuthor }
            if (recentOwners.count { it == owner } >= maxPerOwnerPerWindow) return@forEach
        }
        accepted += candidate
        seenKeys += key
        if (title.isNotEmpty()) seenTitles += title
        if (cover.isNotEmpty()) seenCovers += cover
    }
    return accepted
}

/**
 * 补流判据：停稳页进入「倒数第 [threshold] 页」区间即应补流。
 * bbspace 的补流是自动的（翻页即切视频、新详情自带新一批相关推荐并 merge），
 * 该判据留给 UI 侧的预取/占位提示使用，不负责拉取本身。
 */
fun shouldExtendSwipeQueue(
    committedPage: Int,
    totalPages: Int,
    threshold: Int = SWIPE_EXTEND_THRESHOLD,
): Boolean {
    if (totalPages <= 0) return false
    return committedPage >= (totalPages - threshold).coerceAtLeast(0)
}

internal const val SWIPE_EXTEND_THRESHOLD = 2
internal const val SWIPE_OWNER_DIVERSITY_WINDOW = 12
internal const val SWIPE_MAX_PER_OWNER = 2
