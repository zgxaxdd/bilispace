package com.naaammme.bbspace.feature.comment

import com.naaammme.bbspace.core.model.COMMENT_FILTER_ALL
import com.naaammme.bbspace.core.model.CommentFilterTag
import com.naaammme.bbspace.core.model.CommentPage
import com.naaammme.bbspace.core.model.CommentReply
import com.naaammme.bbspace.core.model.CommentSort
import com.naaammme.bbspace.core.model.CommentSubject
import java.util.LinkedHashMap

/**
 * 评论首页缓存的分桶键：同一个评论主体 + 同一种排序 + 同一个筛选项 + 同一个观看者。
 *
 * 前三项自明（换视频/换排序/换筛选项都该重新拉）。**观看者 mid 是必需的**：
 * [CommentReply.liked] / [CommentReply.disliked] 是随账号变化的（gRPC
 * `reply_control.action`），不带 mid 会让换账号后先看到上一个账号的点赞/点踩态。
 *
 * 上游对应实现：BiliPai `VideoCommentLoadPolicy.videoCommentCacheKey`
 * （`"$oid#$type#$sortModeApiMode#$viewerMid"`）。本仓比上游多拼一项
 * `selectedFilter` —— 上游的筛选项只在 UI 层做本地过滤，本仓的
 * `CommentUiState.selectedFilter` 是**请求参数**（见 `CommentRepository.buildReq`
 * 的 `setFilterTagName`），不同筛选项的首屏内容不同，必须分开存。
 *
 * `filter` 为空串时按 [COMMENT_FILTER_ALL] 归一，与 `CommentRepository.mapPage`
 * 的 `filterTag.ifBlank { COMMENT_FILTER_ALL }` 保持一致 —— 否则同一个评论区会
 * 因为"传了空串"与"传了全部"裂成两个桶，缓存永远打不中。
 */
internal fun commentPageCacheKey(
    subject: CommentSubject,
    sort: CommentSort,
    filter: String,
    viewerMid: Long
): String {
    val normalizedFilter = filter.ifBlank { COMMENT_FILTER_ALL }
    return "${subject.oid}#${subject.type}#${sort.name}#$normalizedFilter#$viewerMid"
}

/**
 * 评论首页的缓存快照。
 *
 * 字段集刻意与 `CommentViewModel.refresh()` 成功路径写入 [com.naaammme.bbspace.feature.comment.CommentUiState]
 * 的字段**一一对应**（即 `CommentPage.toUiState` 会写的那几项）。这一点是关键：
 * 快照缺字段不会报错，只会让重进评论区时那一项悄悄回默认值 —— 排序表头、
 * 筛选项标签、到底文案都是用户看得见的，缺一个就是一个可见的回退。
 *
 * `subject` / `currentMid` / `loading` 不入快照：前两者由调用方（`refresh` 的入参）
 * 现成持有，后者永远是"读缓存时正在刷新"（见 stale-while-revalidate 的注释）。
 */
internal data class CommentPageSnapshot(
    val title: String,
    val count: Long,
    val sort: CommentSort,
    val canSwitchSort: Boolean,
    val filterTags: List<CommentFilterTag>,
    val selectedFilter: String,
    val items: List<CommentReply>,
    val nextOffset: String?,
    val endText: String?
)

/** [CommentPage] → [CommentPageSnapshot]；只搬首屏字段，翻页游标（`nextOffset`）原样带。 */
internal fun CommentPage.toSnapshot(): CommentPageSnapshot = CommentPageSnapshot(
    title = title,
    count = count,
    sort = sort,
    canSwitchSort = canSwitchSort,
    filterTags = filterTags,
    selectedFilter = selectedFilter,
    items = items,
    nextOffset = nextOffset,
    endText = endText
)

/**
 * 默认缓存桶数。
 *
 * 12 与上游 `VideoCommentCache(maxEntries = 12)` 相同。按本仓实测构成估内存：
 * 一级评论一条约 1~3KB（正文 + 头像 URL + 表情/图片列表），一屏 20~30 条，
 * 一个桶约 30~90KB，12 个桶约 0.4~1MB —— 对评论区这种"退出再进要秒出"的场景
 * 是可接受的上限。真机没量过，属于估值；若日后要收紧，改这一个常量即可。
 */
internal const val COMMENT_PAGE_CACHE_MAX_ENTRIES = 12

/**
 * 进程内 LRU 评论首页缓存（手写 LRU，不引入依赖）。
 *
 * **刻意做成进程级而不是 ViewModel 级**：`CommentPanel` 用 `hiltViewModel()`，
 * 而它是各详情目的地内的组合（`VideoDetailPage` / `PortraitSwipePager` /
 * `DynamicDetailScreen` / `MsgFeedScreen`），ViewModel 挂在 NavBackStackEntry 上 ——
 * 退出目的地就被回收，挂在它身上的缓存会跟着一起丢，而"退出再进"恰恰是最需要
 * 秒出的场景。
 *
 * 所有读写都加 [Synchronized]：多个目的地的 ViewModel 可能同时持有不同主体，
 * 并发访问是真实路径，不是理论风险。
 */
internal class CommentPageCache(private val maxEntries: Int = COMMENT_PAGE_CACHE_MAX_ENTRIES) {

    private val entries = LinkedHashMap<String, CommentPageSnapshot>()

    @Synchronized
    fun get(key: String): CommentPageSnapshot? {
        val snapshot = entries.remove(key) ?: return null
        // 先删再插到队尾，保持「最近使用」语义
        entries[key] = snapshot
        return snapshot
    }

    /**
     * 写入一桶。空列表不写：`refresh()` 失败/空页时写进去，下次进来会先铺一个
     * 空列表再走网络，比直接骨架更差（用户看到"评论区是空的"这一瞬间的误判）。
     */
    @Synchronized
    fun put(key: String, snapshot: CommentPageSnapshot) {
        if (key.isBlank() || snapshot.items.isEmpty()) return
        entries.remove(key)
        entries[key] = snapshot
        while (entries.size > maxEntries) {
            val oldestKey = entries.keys.firstOrNull() ?: break
            entries.remove(oldestKey)
        }
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/**
 * 进程内共享实例。见 [CommentPageCache] 的类注释：为什么不能挂在 ViewModel 上。
 */
internal val sharedCommentPageCache = CommentPageCache()
