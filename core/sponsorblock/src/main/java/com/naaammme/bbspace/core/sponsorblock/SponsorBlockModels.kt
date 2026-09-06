package com.naaammme.bbspace.core.sponsorblock

/**
 * 空降助手（SponsorBlock）片段分类。
 * wire 名与 bsbsb.top 服务端协议一致；默认策略对齐小电视空降助手插件默认配置。
 */
enum class SbCategory(
    val wire: String,
    val title: String,
    val shortTitle: String,
    val colorArgb: Long,
    val defaultSkipType: SbSkipType
) {
    SPONSOR("sponsor", "赞助/恰饭", "赞助", 0xFF00D400, SbSkipType.AUTO_SKIP),
    SELFPROMO("selfpromo", "无偿/自我推广", "推广", 0xFFFFFF00, SbSkipType.MANUAL_SKIP),
    EXCLUSIVE_ACCESS("exclusive_access", "独家访问/抢先体验", "品牌合作", 0xFF008A5C, SbSkipType.SHOW_OVERLAY),
    INTERACTION("interaction", "三连/互动提醒", "三连提醒", 0xFFCC00FF, SbSkipType.MANUAL_SKIP),
    POI_HIGHLIGHT("poi_highlight", "精彩时刻/重点", "精彩时刻", 0xFFFF1684, SbSkipType.MANUAL_SKIP),
    INTRO("intro", "过场/开场动画", "开场动画", 0xFF00FFFF, SbSkipType.MANUAL_SKIP),
    OUTRO("outro", "鸣谢/结束画面", "片尾", 0xFF0202ED, SbSkipType.MANUAL_SKIP),
    PREVIEW("preview", "回顾/概要", "预览", 0xFF008FD6, SbSkipType.SHOW_OVERLAY),
    PADDING("padding", "填充内容/前黑/后黑", "填充内容", 0xFF222222, SbSkipType.AUTO_SKIP),
    FILLER("filler", "离题闲聊/玩笑", "离题", 0xFF7300FF, SbSkipType.DISABLED),
    MUSIC_OFFTOPIC("music_offtopic", "音乐:非音乐部分", "非音乐", 0xFFFF9900, SbSkipType.AUTO_SKIP);

    companion object {
        fun fromWire(wire: String): SbCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 片段动作类型（服务端协议字段 actionType）。
 */
enum class SbActionType(val wire: String) {
    SKIP("skip"),
    MUTE("mute"),
    FULL("full"),
    POI("poi");

    companion object {
        fun fromWire(wire: String): SbActionType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 用户可配置的片段处理策略（对应插件 CategorySkipOption）。
 * 序号持久化到 DataStore，勿调整顺序。
 */
enum class SbSkipType(val label: String) {
    DISABLED("禁用"),
    SHOW_OVERLAY("仅显示"),
    MANUAL_SKIP("手动跳过"),
    AUTO_SKIP("自动跳过")
}

/**
 * 服务端返回的一个片段。
 */
data class SbSegment(
    val startSec: Double,
    val endSec: Double,
    val cid: Long,
    val uuid: String,
    val category: SbCategory,
    val actionType: SbActionType,
    val locked: Boolean,
    val votes: Int,
    val videoDurationSec: Double
) {
    val startMs: Long get() = (startSec * 1000).toLong()
    val endMs: Long get() = maxOf((endSec * 1000).toLong(), startMs + 1L)

    fun containsMs(positionMs: Long): Boolean = positionMs in startMs until endMs
}
