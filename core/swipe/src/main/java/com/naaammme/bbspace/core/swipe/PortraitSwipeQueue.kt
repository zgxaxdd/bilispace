package com.naaammme.bbspace.core.swipe

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.FeedDislikeContext
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.StoryFeedItem
import com.naaammme.bbspace.core.model.ThreePointReason
import com.naaammme.bbspace.core.model.VideoDimension
import com.naaammme.bbspace.core.model.VideoRelate
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.swipe.model.SwipeId

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
    val target: VideoTarget.Ugc,
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String?,
    val durationText: String?,
    /**
     * UP 主 mid（SW-04）。feed 源（`args.up_id`）与 story 源（`authorMid`）有；
     * **relate 源没有**——关注按钮（SW-20）按 null 隐藏，不做假按钮。
     */
    val ownerMid: Long? = null,
    /** 时长原始秒数（SW-04）；时长文本（[durationText]）之外的第二表示，供判据用。 */
    val durationSec: Long? = null,
    /** 画幅（SW-04）；feed/story 源未核实带不带尺寸，拿不到就 null（"未知放过"，SW-40）。 */
    val dimension: VideoDimension? = null,
    /**
     * 负反馈上下文（SW-04/SW-24）：**仅 feed 源有**（relate/story 上游无此通道）。
     * null 时"不感兴趣"入口隐藏。
     */
    val dislikeContext: FeedDislikeContext? = null,
    /**
     * 负反馈理由（SW-24）：来自 feed 卡的 `three_point_v2.reasons`，与首页同一份数据源。
     * 空表 = 拿不到理由（relate/story 源，或该卡没给），此时"不感兴趣"仍可用，
     * 但走 [SwipeDislikeRemovalPolicy] 之外的默认理由由调用方决定（见 VM 的接线）。
     */
    val dislikeReasons: List<ThreePointReason> = emptyList(),
) {
    val displayAuthor: String? get() = author?.takeIf(String::isNotBlank)

    /**
     * 类型化身份键（SW-03）：**页身份一律走它**（"这一页是不是当前正在播放的视频"
     * → [isCurrentVideo]）。aid 优先、bvid 兜底的语义在 [SwipeId.matches] 内置。
     */
    val id: SwipeId get() = SwipeId.of(bvid, target.aid)

    /**
     * 旧版字符串身份键：**只许**用于日志与去重 map（appendSwipeEntries 的 seenKeys、
     * 相似度签名缓存这类"同形同串"比较），**不得**再用于页身份判断——
     * 字符串在"取流完成、bvid 被服务端补齐"的一瞬间会从 `aid:…` 翻成 `BV…`
     * （C7 真机事故根因，见 [isCurrentVideo]）。源码守卫盯着这里不许回归。
     */
    fun legacyKey(): String = swipeKey(bvid, target.aid)

    /**
     * **这一页是不是「当前正在播放的视频」**（pager 用它决定哪一页组合播放器）。
     *
     * ## 为什么不能直接比字符串（真机 bug：翻页后新视频不播放）
     *
     * 两侧的 key 是**在不同时刻**算出来的：
     * - 队列条目在**建队时**算：relate 卡没有 bvid → `key = "aid:117339261503379"`；
     * - pager 的 currentKey 从 `videoState.ids` 算，而 `ids` 在**取流/详情返回后会被
     *   解析结果整体覆盖**（`applyDetailResult`：`ids = detailResult.ids`，此时 bvid 已被
     *   服务端补齐）→ `currentKey` 变成 `"BV…"`。
     *
     * 于是同一条目在"取流中"匹配、**取流完成的一瞬间失配**：页身份翻转，播放器页
     * 落空 → 用户看到的是封面页 + 永远不起播（真机症状"可以滑动，但无法播放下一个
     * 视频"）。锚点页（页 0，bvid 本来就全）不受影响，所以只有后续页坏。
     *
     * 判据因此是**按内容比**、而不是按字符串比：aid 是 bvid 的 1:1 另一侧表示，
     * 两侧都有 aid 时以 aid 为准（对"bvid 后来才补齐"天然免疫）；只有 aid 缺失的
     * 特殊条目才退回比 bvid。这样"取流前/取流后"两个阶段结论一致。
     * 语义逐条固化在 [com.naaammme.bbspace.core.swipe.model.SwipeId.matches]。
     */
    fun isCurrentVideo(bvid: String?, aid: Long): Boolean =
        id.matches(SwipeId.of(bvid, aid))
}

/** 旧版字符串身份键：bvid 优先、aid 兜底（[SwipeEntry.legacyKey] 与去重 map 同构；仅限日志与去重）。 */
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
                durationText = relate.durationText,
                // relate 卡没有 UP mid（SW-04 核实结论）；时长/画幅是 proto 已有字段的接出
                ownerMid = null,
                durationSec = relate.durationSec,
                dimension = relate.dimension
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
        durationText = item.durationSec.takeIf { it > 0L }?.let(::formatSwipeDuration),
        ownerMid = item.authorMid.takeIf { it > 0L },
        durationSec = item.durationSec.takeIf { it > 0L },
        // story 卡的 dimension 有则带、无则 null（"未知放过"，SW-40）；dislikeContext 无通道
        dimension = item.dimension
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
        durationText = null,
        // feed 源有 UP mid（args.up_id）与负反馈通道；时长/尺寸待核实，留 null
        ownerMid = item.args?.upId?.takeIf { it > 0L },
        dislikeContext = item.dislikeContext,
        // SW-24：负反馈理由随条目带走（会话内选理由/直接提交都用它）
        dislikeReasons = item.threePointV2
            .orEmpty()
            .flatMap { it.reasons.orEmpty() }
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
 * - 身份去重（[SwipeEntry.legacyKey]，bvid 优先 / aid 兜底的字符串形；页身份判断
 *   已类型化为 [SwipeEntry.id]/[SwipeEntry.isCurrentVideo]）硬去重（含锚点与已在队列内的）；
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
    /** 排序种子：默认按会话锚点派生 —— 同一会话内可复现，不同会话不同序。 */
    arrangeSeed: Int = anchorKey.hashCode(),
): List<SwipeEntry> {
    if (incoming.isEmpty()) return existing
    val accepted = existing.toMutableList()
    val seenKeys = accepted.mapTo(mutableSetOf()) { it.legacyKey() }
    anchorKey.trim().takeIf(String::isNotEmpty)?.let(seenKeys::add)
    val seenTitles = accepted.mapNotNullTo(mutableSetOf()) {
        normalizeSwipeTitle(it.title).takeIf(String::isNotEmpty)
    }
    val seenCovers = accepted.mapNotNullTo(mutableSetOf()) {
        swipeCoverKey(it.cover).takeIf(String::isNotEmpty)
    }
    // 相似度签名缓存：见文件末尾 areSwipeEntriesSimilarCached 的说明
    val signatures = HashMap<String, Set<String>>(incoming.size + accepted.size)
    // 新批次先洗牌 + 相邻不同 UP 排布（BiliPai 同款）：只重排**这一批**，
    // 已在队列里的页顺序不动（pager 以页号为身份，重排会让用户当前页跳变）。
    val ordered = arrangeSwipeEntries(
        seed = arrangeSeed,
        incoming = incoming,
        precedingOwner = accepted.lastOrNull()?.displayAuthor
    )
    ordered.forEach { candidate ->
        val key = candidate.legacyKey()
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
        // 相似度档（BiliPai `PortraitPagerSwitchPolicy.kt:407-441` 的移植）：
        // 同一支视频被不同 UP 以不同标题后缀反复推荐时，上面的"标题全等/封面相等"挡不住，
        // 用户会连着刷到同一支。签名缓存把 O(n²) 里的正则抽取摊成一次。
        if (accepted.any { existing -> areSwipeEntriesSimilarCached(existing, candidate, signatures) }) {
            return@forEach
        }
        accepted += candidate
        seenKeys += key
        if (title.isNotEmpty()) seenTitles += title
        if (cover.isNotEmpty()) seenCovers += cover
    }
    return accepted
}

/**
 * 相似度比较（带签名缓存）：关键词抽取是正则 + 集合运算，逐对比较里必须缓存，
 * 否则队列长到几十条时每次补货都要重跑上百次正则。
 *
 * `internal` 而不是 `private`：`SwipeArrangement` 的排布也要用同一份判据 +
 * 自己的缓存（同一批次内共享）。
 */
internal fun areSwipeEntriesSimilarCached(
    first: SwipeEntry,
    second: SwipeEntry,
    cache: MutableMap<String, Set<String>>,
): Boolean {
    val firstCoverKey = swipeCoverKey(first.cover)
    val secondCoverKey = swipeCoverKey(second.cover)
    if (firstCoverKey.isNotEmpty() && firstCoverKey == secondCoverKey) return true
    val firstKeywords = cache.getOrPut(first.legacyKey()) { extractSwipeTitleKeywords(first.title) }
    val secondKeywords = cache.getOrPut(second.legacyKey()) { extractSwipeTitleKeywords(second.title) }
    if (firstKeywords.isEmpty() || secondKeywords.isEmpty()) return false
    val overlap = firstKeywords.intersect(secondKeywords).size
    if (overlap >= SWIPE_KEYWORD_OVERLAP_STRONG) return true
    // 时长未知 → 后两条判据不成立（与 SwipeSimilarity.areSwipeEntriesSimilar 同一适配：
    // bbspace 的条目常常没有时长，用 0 参与比较会把同 UP 的不同视频误判成同一支）
    val firstSec = swipeDurationSec(first.durationText)
    val secondSec = swipeDurationSec(second.durationText)
    if (firstSec <= 0 || secondSec <= 0) return false
    val durationDelta = kotlin.math.abs(firstSec - secondSec)
    if (overlap >= SWIPE_KEYWORD_OVERLAP_WEAK && durationDelta <= SWIPE_DURATION_DELTA_WEAK_SEC) return true
    val firstOwner = first.displayAuthor
    val secondOwner = second.displayAuthor
    return firstOwner != null &&
        firstOwner == secondOwner &&
        overlap >= 1 &&
        durationDelta <= SWIPE_DURATION_DELTA_SAME_OWNER_SEC
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
