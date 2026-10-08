package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

sealed interface WebLinkTarget {
    @Immutable data class ToVideo(val target: VideoTarget) : WebLinkTarget
    @Immutable data class ToSpace(val mid: Long) : WebLinkTarget
    @Immutable data class ToLive(val roomId: Long) : WebLinkTarget

    /**
     * 专栏 / 图文动态（opus 内容页，F-2，2026-10-05）。
     *
     * [opusType] 沿用本仓既有口径（`AppNavHost` 的 `HistoryTarget.Article → 1`、
     * 动态流卡片 → 0）：0 = 图文动态，1 = 专栏文章。站点侧 `www.bilibili.com/opus/<id>`
     * 是"统一内容页"，具体类型交给 `DynamicRepository.fetchOpusDetail` 的 `opusType` 判定。
     */
    @Immutable data class ToDynamic(val opusId: Long, val opusType: Int) : WebLinkTarget

    @Immutable data object External : WebLinkTarget
    @Immutable data object Stay : WebLinkTarget
}
