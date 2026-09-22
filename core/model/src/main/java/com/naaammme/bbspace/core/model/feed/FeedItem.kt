package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class FeedItem(
    val cardType: String,
    val cardGoto: String,
    val goto: String,
    val param: String,
    val uri: String,
    val title: String,
    val cover: String,
    val coverLeftText1: String?,
    val coverLeftText2: String?,
    val coverRightText: String?,
    val idx: Long,
    val target: VideoTarget?,
    val liveRoute: LiveRoute?,
    val descButton: DescButton?,
    val rcmdReason: RcmdReason?,
    val args: FeedArgs?,
    val threePointV2: List<ThreePointItem>?,
    val dislikeContext: FeedDislikeContext?
) {
    /**
     * 列表稳定键 / 去重键：`goto|param|idx`。
     *
     * 为什么是**构造期赋值的 body 属性**，而不是每次调用时拼接的函数、也不是 `by lazy`：
     * - 作为函数：`AdaptiveMediaGrid` 的 key lambda 每次重组对每项调一次，卡片体内还要
     *   再查一次 `dislikedReasons`，于是滚动帧里每张可见卡片各分配 2 份 String；
     * - 用 `by lazy`：会为每个 item 多分配一个 Lazy 委托对象，且默认
     *   SYNCHRONIZED 模式带锁，比一次字符串拼接更贵。
     *
     * 放在 body 里（不是构造参数）是刻意的：data class 只有**主构造函数里的属性**才进
     * equals/hashCode/copy/componentN。写成构造参数会让"同一条目的键"参与内容比较，
     * 改变列表 diff 与 `distinctBy` 的语义。
     *
     * 之前 `HomeViewModel` 与 `HomeVideoPage` 各有一份**逐字相同**的
     * `private fun FeedItem.actionKey()`，两处独立演化迟早会算出不同的键，
     * 而键不一致的直接后果就是列表复用错乱。收口到这里。
     */
    val actionKey: String = "$goto|$param|$idx"
}

@Immutable
data class DescButton(
    val text: String,
    val uri: String
)

@Immutable
data class RcmdReason(
    val text: String,
    val textColor: String?,
    val bgColor: String?,
    val textColorNight: String?,
    val bgColorNight: String?
)

@Immutable
data class FeedArgs(
    val upId: Long,
    val upName: String?,
    val tid: Int,
    val tname: String?,
    val aid: Long
)

@Immutable
data class ThreePointItem(
    val title: String,
    val subtitle: String?,
    val type: String,
    val reasons: List<ThreePointReason>?,
    val feedbacks: List<ThreePointReason>?
)

@Immutable
data class ThreePointReason(
    val id: Int,
    val name: String,
    val toast: String,
    val extra: String?,
    val kind: ThreePointReasonKind
)

enum class ThreePointReasonKind {
    DISLIKE,
    FEEDBACK
}

@Immutable
data class FeedDislikeContext(
    val id: String,
    val goto: String,
    val useHdProfile: Boolean,
    val spmid: String,
    val fromSpmid: String,
    val fromModule: String?,
    val trackId: String?,
    val reportData: String?,
    val mid: Long?,
    val rid: Long?,
    val tagId: Long?
)

data class FeedToast(
    val hasToast: Boolean,
    val message: String
)
