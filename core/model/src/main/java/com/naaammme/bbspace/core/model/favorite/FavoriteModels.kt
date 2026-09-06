package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class FavoritePage(
    val folders: List<FavoriteFolder>
)

@Immutable
data class FavoriteFolder(
    val fid: Long,
    val title: String,
    val cover: String?,
    val attrDesc: String?,
    val mediaCount: Int,
    val createdAtSec: Long,
    val isTop: Boolean,
    /** 目标内容是否已在该收藏夹内（list-all 携带 rid 时返回，1=已收藏），其余场景为 0 */
    val favState: Int = 0,
    /** 收藏夹属性位：bit0=私有（与 PiliPlus 语义一致） */
    val attr: Int = 0
) {
    val isPrivate: Boolean
        get() = attr and 0b1 == 0b1
}

@Immutable
data class FavoriteContentCursor(
    val startOid: Long = 0L,
    val startOtype: Int = 0,
    val startScore: Long = 0L
)

@Immutable
data class FavoriteContentPage(
    val items: List<FavoriteContentItem>,
    val cursor: FavoriteContentCursor,
    val hasMore: Boolean
)

@Immutable
data class FavoriteFolderContentPage(
    val items: List<FavoriteContentItem>,
    val hasMore: Boolean,
    val totalCount: Int,
    val hasInvalid: Boolean
)

@Immutable
data class FavoriteContentItem(
    val key: String,
    val oid: Long,
    val otype: Int,
    val title: String,
    val cover: String?,
    val ownerName: String?,
    val viewText: String?,
    val danmakuText: String?,
    val playbackDesc: String?,
    val typeDesc: String?,
    val isInvalid: Boolean,
    val target: FavoriteContentTarget?
) {
    val isOpenable: Boolean
        get() = target != null
}

sealed interface FavoriteContentTarget {
    @Immutable
    data class Video(val target: VideoTarget) : FavoriteContentTarget

    @Immutable
    data class DynamicDetail(val opusId: String) : FavoriteContentTarget
}
