package com.naaammme.bbspace.feature.video.swipe

import kotlin.math.abs

/**
 * 「同一支视频被不同 UP 反复推荐」的相似度判据 —— 从 BiliPai
 * `PortraitPagerSwitchPolicy.kt:407-497` 原样移植（阈值与停用词表一并搬过来，
 * 不自行发明数字）。
 *
 * ## 为什么需要
 * 连播队列的来源是"相关推荐接力"，同一支视频常被多个 UP 以**不同标题后缀**
 * （【】【合集】【完整版】…）反复推荐；只做"标题全等 / 封面相等"是挡不住的，
 * 用户会连着刷到同一支视频。BiliPai 的判据是三条叠加：
 * 1. 关键词交集 ≥ 3 → 相似；
 * 2. 关键词交集 ≥ 2 **且** 时长差 ≤ 30 秒 → 相似；
 * 3. 同一个 UP **且** 交集 ≥ 1 **且** 时长差 ≤ 45 秒 → 相似。
 *
 * ## 与 BiliPai 的两处已知差异（都是数据源限制，不是判据改动）
 * - BiliPai 用 `owner.mid`（数字 ID）判"同一个 UP"；bbspace 的队列条目只有**作者名**，
 *   所以这里用名字 —— 改名/同名会误判，但连播队列里同名同长的概率很低，先按这条走。
 * - 时长：BiliPai 的 relate 直接带秒；bbspace 只有 `durationText`（"12:34"），
 *   这里解析出来；解析不到就退化成"只判关键词"（第 3 条自动不成立）。
 *
 * 纯逻辑，JVM 可测：标题规范化 / 关键词抽取 / 时长解析 / 三条判据的边界。
 */

/** 标题里的括号段（常带推荐后缀）：整段丢掉再比。 */
private val SWIPE_TITLE_BRACKET_PATTERN = Regex("[\\[{（(【].*?[\\]})）)】]")

/** 非"中文/字母/数字"一律视作分隔符。 */
private val SWIPE_TITLE_NON_WORD_PATTERN = Regex("[^\\u4e00-\\u9fa5a-z0-9]+")
private val SWIPE_TITLE_WHITESPACE_PATTERN = Regex("\\s+")

/** 中文词：连续 2~6 个汉字当作一个关键词（BiliPai 同款，不做分词）。 */
private val SWIPE_TITLE_ZH_PATTERN = Regex("[\\u4e00-\\u9fa5]{2,6}")

/** 英文/数字词：长度 ≥ 3。 */
private val SWIPE_TITLE_EN_PATTERN = Regex("[a-z0-9]{3,}")

/** 中文停用词（BiliPai 原表）：这些词在标题里几乎不携带区分度。 */
private val SWIPE_TITLE_STOP_WORDS = setOf(
    "视频", "合集", "最新", "一个", "我们", "你们", "今天", "真的", "这个",
    "竖屏", "横屏", "官方", "完整版", "全集", "合集版"
)

/** 相似判据的阈值（BiliPai 原值，别改）。 */
internal const val SWIPE_KEYWORD_OVERLAP_STRONG = 3
internal const val SWIPE_KEYWORD_OVERLAP_WEAK = 2
internal const val SWIPE_DURATION_DELTA_WEAK_SEC = 30
internal const val SWIPE_DURATION_DELTA_SAME_OWNER_SEC = 45

/** 中文关键词最多取几个（BiliPai 同款：6 个中文 + 4 个英文）。 */
private const val SWIPE_MAX_ZH_KEYWORDS = 6
private const val SWIPE_MAX_EN_KEYWORDS = 4

/**
 * 标题规范化：丢掉括号段 → 非中英文数字转空格 → 折叠空白 → 小写。
 *
 * 与 [normalizeSwipeTitle]（"截断括号 + 只留字母数字"）**不同**且**两者都要保留**：
 * 那个是"标题全等"去重的严格键，这个是关键词抽取的输入（保留空格才能切词）。
 */
fun normalizeSwipeTitleForKeywords(title: String): String = title.lowercase()
    .replace(SWIPE_TITLE_BRACKET_PATTERN, " ")
    .replace(SWIPE_TITLE_NON_WORD_PATTERN, " ")
    .replace(SWIPE_TITLE_WHITESPACE_PATTERN, " ")
    .trim()

/** 抽取标题关键词（中文 2~6 字最多 6 个 + 英文数字 ≥3 位最多 4 个，去停用词）。 */
fun extractSwipeTitleKeywords(title: String): Set<String> {
    val normalized = normalizeSwipeTitleForKeywords(title)
    if (normalized.isBlank()) return emptySet()
    val zh = SWIPE_TITLE_ZH_PATTERN.findAll(normalized)
        .map { it.value }
        .filter { it !in SWIPE_TITLE_STOP_WORDS }
        .take(SWIPE_MAX_ZH_KEYWORDS)
        .toList()
    val en = SWIPE_TITLE_EN_PATTERN.findAll(normalized)
        .map { it.value }
        .take(SWIPE_MAX_EN_KEYWORDS)
        .toList()
    return (zh + en).toSet()
}

/**
 * `durationText`（"12:34" / "1:02:03"）→ 秒。
 *
 * 解析不出来（null/空/格式怪）返回 0 —— 与 BiliPai 的 `duration.coerceAtLeast(0)`
 * 对齐：0 表示"时长未知"，此时只可能命中"关键词 ≥3"那条判据。
 */
fun swipeDurationSec(durationText: String?): Int {
    val text = durationText?.trim().orEmpty()
    if (text.isEmpty()) return 0
    val parts = text.split(':')
    if (parts.size !in 2..3) return 0
    val numbers = parts.map { it.trim().toIntOrNull() ?: return 0 }
    if (numbers.any { it < 0 }) return 0
    return when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
}

/**
 * 两条队列条目是不是"同一支视频"（BiliPai 判据的 bbspace 版）。
 *
 * 注意：调用方负责缓存签名（O(n²) 的逐对比较里每次都抽关键词会很贵）。
 */
fun areSwipeEntriesSimilar(
    firstTitle: String,
    firstCover: String,
    firstAuthor: String?,
    firstDurationText: String?,
    secondTitle: String,
    secondCover: String,
    secondAuthor: String?,
    secondDurationText: String?,
): Boolean {
    // 第 0 层：封面 key 相等（BiliPai 把"封面相等"与"标题全等"都放在最前面短路）
    val firstCoverKey = swipeCoverKey(firstCover)
    val secondCoverKey = swipeCoverKey(secondCover)
    if (firstCoverKey.isNotEmpty() && firstCoverKey == secondCoverKey) return true

    val firstKeywords = extractSwipeTitleKeywords(firstTitle)
    val secondKeywords = extractSwipeTitleKeywords(secondTitle)
    if (firstKeywords.isEmpty() || secondKeywords.isEmpty()) return false
    val overlap = firstKeywords.intersect(secondKeywords).size
    if (overlap >= SWIPE_KEYWORD_OVERLAP_STRONG) return true

    // ⚠️ 与 BiliPai 的**关键适配**：它的 relate 一定带秒，所以"时长差"永远可算；
    // bbspace 的队列里 durationText 经常为 null（解析成 0），若直接参与比较，
    // 两条毫不相干的同 UP 视频会因为"0-0=0 ≤ 45s"被判相似 → 队列被误杀变薄。
    // 所以：**时长未知时后两条判据不成立**（宁可漏杀，不可误杀）。
    val firstSec = swipeDurationSec(firstDurationText)
    val secondSec = swipeDurationSec(secondDurationText)
    val durationKnown = firstSec > 0 && secondSec > 0
    if (!durationKnown) return false
    val durationDelta = abs(firstSec - secondSec)

    if (overlap >= SWIPE_KEYWORD_OVERLAP_WEAK && durationDelta <= SWIPE_DURATION_DELTA_WEAK_SEC) return true
    val firstAuthorKey = firstAuthor?.trim()?.takeIf(String::isNotEmpty)
    val secondAuthorKey = secondAuthor?.trim()?.takeIf(String::isNotEmpty)
    if (
        firstAuthorKey != null &&
        firstAuthorKey == secondAuthorKey &&
        overlap >= 1 &&
        durationDelta <= SWIPE_DURATION_DELTA_SAME_OWNER_SEC
    ) {
        return true
    }
    return false
}

/** [areSwipeEntriesSimilar] 的条目版（队列里直接用）。 */
fun areSwipeEntriesSimilar(first: SwipeEntry, second: SwipeEntry): Boolean = areSwipeEntriesSimilar(
    firstTitle = first.title,
    firstCover = first.cover,
    firstAuthor = first.displayAuthor,
    firstDurationText = first.durationText,
    secondTitle = second.title,
    secondCover = second.cover,
    secondAuthor = second.displayAuthor,
    secondDurationText = second.durationText,
)
