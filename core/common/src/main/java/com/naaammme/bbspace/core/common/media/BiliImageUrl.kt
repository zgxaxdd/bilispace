package com.naaammme.bbspace.core.common.media

private val thumbReg = Regex(
    pattern = """(@(\d+[a-z]_?)*)(\..*)?$""",
    options = setOf(RegexOption.IGNORE_CASE)
)

const val BILI_IMAGE_DEFAULT_Q = 15
private const val AVATAR_IMAGE_SUFFIX = "@120w_120h_85q_!widget-layer-avatar.webp"

/**
 * 封面分辨率档位：按卡片用途选择，默认不低于 640w，保证高分屏不糊。
 */
enum class BiliCoverSize(val suffix: String) {
    /** 双列小卡及通用封面（16:9） */
    Small("@640w_360h_1e_1c_85q.webp"),

    /** 双列 4:3 封面（PiliPlus-Max 风格） */
    Small43("@640w_480h_1e_1c_85q.webp"),

    /** 单列大图 / 视频详情等大尺寸场景 */
    Large("@960w_540h_1e_1c_85q.webp")
}

fun String.httpsImageUrl(): String {
    return when {
        startsWith("https://") -> this
        startsWith("http://") -> "https://${removePrefix("http://")}"
        else -> this
    }
}

fun String?.httpsImageUrlOrNull(): String? {
    return this?.httpsImageUrl()
}

fun coverUrlFor(src: String?, size: BiliCoverSize = BiliCoverSize.Small): String? {
    if (src.isNullOrBlank()) return src
    return src.httpsImageUrl().replaceImageBody { body ->
        body.substringBefore('@') + size.suffix
    }
}

fun coverThumbnailUrl(src: String?): String? = coverUrlFor(src, BiliCoverSize.Small)

fun thumbnailUrl(src: String?, q: Int = BILI_IMAGE_DEFAULT_Q): String? {
    if (src.isNullOrBlank()) return src
    var matched = false
    val url = src.httpsImageUrl().replace(thumbReg) { match ->
        matched = true
        val suffix = match.groupValues[3].ifEmpty { ".webp" }
        "${match.groupValues[1]}_${q}q$suffix"
    }
    return if (matched) {
        url
    } else {
        "${url}@${q}q.webp"
    }
}

fun avatarThumbnailUrl(src: String?): String? {
    if (src.isNullOrBlank()) return src
    return src.httpsImageUrl().replaceImageBody { body ->
        body.substringBefore('@') + AVATAR_IMAGE_SUFFIX
    }
}

fun originImageUrl(src: String?): String? {
    if (src.isNullOrBlank()) return src
    return src.httpsImageUrl().replaceImageBody { body ->
        body.replace(thumbReg, "")
    }
}

private inline fun String.replaceImageBody(transform: (String) -> String): String {
    val queryIdx = indexOf('?')
    val body = if (queryIdx >= 0) substring(0, queryIdx) else this
    val query = if (queryIdx >= 0) substring(queryIdx) else ""
    return transform(body) + query
}
