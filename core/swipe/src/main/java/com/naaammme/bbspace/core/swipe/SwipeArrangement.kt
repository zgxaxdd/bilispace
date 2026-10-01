package com.naaammme.bbspace.core.swipe

import kotlin.random.Random

/**
 * 新批次的**洗牌 + 相邻不同 UP 排布**（移植 BiliPai
 * `PortraitPagerSwitchPolicy.kt:280-330` 的 `shufflePortraitRecommendations`）。
 *
 * ## 为什么这么做
 * 相关推荐/推荐流都是**按相关性排序**的：一批里往往同一 UP 连着好几条、同一支视频
 * 换个标题又出现一次。直接按原序入队，用户就会"连着刷到同一个 UP 的三条"。
 * BiliPai 的处理是三步：
 * 1. 按身份去重后**用固定 seed 洗牌**（同样输入永远同样顺序 —— 可复现、也能单测）；
 * 2. 相似度去重（见 [areSwipeEntriesSimilar]）；
 * 3. **贪心排布**：每次尽量挑"与上一条不相似、且 UP 与上一条不同"的那一条；
 *    挑不到就退化成"只要不相似"，再挑不到就按剩余顺序取。
 *
 * ## 与 BiliPai 的两处差异
 * - 只洗**新批次**，绝不重排已在队列里的页（bbspace 的 pager 以页号为身份，
 *   重排会让"用户当前所在页"跳变）——这是硬约束，调用点只在合并新批次时。
 * - bbspace 的相关推荐里有 **bvid 为空的条目**（relate 的 uri 是 aid 格式，靠本地
 *   `avToBv` 补；补不出来时只有 aid）。BiliPai 直接 `filter { bvid.isNotBlank() }`，
 *   在 bbspace 会把整页推荐丢掉（C7 真机事故），所以这里按 [SwipeEntry.legacyKey] 去重，
 *   **不丢 aid 条目**（页身份判断已类型化为 [SwipeEntry.id]，见 SW-03）。
 *
 * 纯逻辑（固定 seed 的 `Random`），JVM 可测。
 */
fun arrangeSwipeEntries(
    seed: Int,
    incoming: List<SwipeEntry>,
    /** 队列尾部那一条的 UP：新批次的第一条也尽量别和它同 UP。 */
    precedingOwner: String? = null,
): List<SwipeEntry> {
    if (incoming.size <= 1) return incoming

    val shuffled = incoming
        .distinctBy { it.legacyKey() }
        // 先按身份键排序再洗牌：Kotlin 的 `shuffled(Random(seed))` 依赖**输入顺序**，
        // 同一批推荐若以不同顺序到达（接口分页/本地去重先后不同），结果就会不一样。
        // 排序后"同一个集合 → 同一个顺序"，跨会话/跨补货都可复现。
        .sortedBy { it.legacyKey() }
        .shuffled(Random(seed))

    // 逐对比较是 O(n²)：签名（关键词集合）每批只算一次
    val cache = HashMap<String, Set<String>>(shuffled.size)
    fun similar(left: SwipeEntry, right: SwipeEntry): Boolean =
        areSwipeEntriesSimilarCached(left, right, cache)

    val deduplicated = mutableListOf<SwipeEntry>()
    shuffled.forEach { candidate ->
        if (deduplicated.none { existing -> similar(existing, candidate) }) {
            deduplicated += candidate
        }
    }
    if (deduplicated.size <= 1) return deduplicated

    val remaining = deduplicated.toMutableList()
    val arranged = mutableListOf<SwipeEntry>()
    val preceding = precedingOwner?.trim()?.takeIf(String::isNotEmpty)
    while (remaining.isNotEmpty()) {
        val last = arranged.lastOrNull()
        val previousOwner = last?.displayAuthor ?: preceding?.takeIf { arranged.isEmpty() }
        val index = remaining.indexOfFirst { candidate ->
            val owner = candidate.displayAuthor
            (last == null || !similar(last, candidate)) &&
                (previousOwner == null || owner == null || owner != previousOwner)
        }.takeIf { it >= 0 }
            ?: remaining.indexOfFirst { candidate ->
                last == null || !similar(last, candidate)
            }.takeIf { it >= 0 }
            ?: 0
        arranged += remaining.removeAt(index)
    }
    return arranged
}
