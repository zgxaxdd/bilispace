package com.naaammme.bbspace.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.naaammme.bbspace.core.common.media.BILI_IMAGE_DEFAULT_Q
import com.naaammme.bbspace.core.common.media.BiliCoverSize
import com.naaammme.bbspace.core.common.media.avatarThumbnailUrl
import com.naaammme.bbspace.core.common.media.coverThumbnailUrl
import com.naaammme.bbspace.core.common.media.coverUrlFor
import com.naaammme.bbspace.core.common.media.originImageUrl
import com.naaammme.bbspace.core.common.media.thumbnailUrl

enum class BiliImageVariant(
    val q: Int = BILI_IMAGE_DEFAULT_Q,
    val original: Boolean = false
) {
    Avatar,
    CardCover,
    CardCover43,
    CardCoverLarge,
    PreviewThumb,
    Banner,
    Original(original = true)
}

/**
 * 变体 → 实际请求 URL。**纯函数**（不碰 Compose），所以 JVM 单测能直接覆盖。
 *
 * 为什么要把它从 [rememberBiliImageRequest] 里抽出来：抽出来之前，"某个变体到底请求
 * 哪个 URL"这件事只有 composable 内部知道，于是首页的封面预加载只能自己去猜 ——
 * 它猜的结果是直接传未解析的 `item.cover`，与卡片请求的
 * `...@640w_480h_1e_1c_85q.webp` **不是同一个 URL**。Coil 的 memory / disk
 * cache key 都由这个 URL 派生，键不同 → 预加载解出来的位图卡片永远取不到，
 * **预加载 100% 白做，还额外下载了一张原图**。
 * 现在两条路径共用这一个函数，从结构上不可能再各算各的。
 */
fun biliImageUrlForVariant(url: String?, variant: BiliImageVariant): String? = when {
    url.isNullOrBlank() -> url
    variant.original -> originImageUrl(url)
    variant == BiliImageVariant.Avatar -> avatarThumbnailUrl(url)
    variant == BiliImageVariant.CardCoverLarge -> coverUrlFor(url, BiliCoverSize.Large)
    variant == BiliImageVariant.CardCover43 -> coverUrlFor(url, BiliCoverSize.Small43)
    variant == BiliImageVariant.CardCover -> coverThumbnailUrl(url)
    else -> thumbnailUrl(url, variant.q)
}

/**
 * 信息流卡片按"单列/双列"实际使用的封面变体。
 *
 * 必须与 [CoverImage] 里 `coverLarge -> CardCoverLarge / coverFourThree -> CardCover43`
 * 的分支保持一致 —— 预加载传错变体就等于请求了另一个 URL，缓存必然对不上。
 */
fun feedCoverVariant(singleColumn: Boolean): BiliImageVariant =
    if (singleColumn) BiliImageVariant.CardCoverLarge else BiliImageVariant.CardCover43

/**
 * 封面在 Coil 里的**显式缓存键**。
 *
 * ## 为什么需要它（而不是让 Coil 自己按 URL 算）
 *
 * 卡片动态取色要把"取色结果的缓存键"与"Coil 图片缓存的键"对齐：
 * 取色挂在下游的 `onSuccess` 上，如果两者用不同的键，就会出现
 * 「同一张封面在 A 处算过色、B 处却查不到」这类不一致。
 *
 * 上游 BiliPai 的做法是**显式指定** memory/disk cache key（`cover_{bvid}_n`），
 * 让取色键与图片键同源。BBSpace 此前只把原始 URL 当取色键、且**没有**把任何键
 * 传给 Coil —— 于是取色键与 Coil 实际用的键各算各的。
 *
 * ## 键的形状
 *
 * `cover_{identity}_{quality}`，quality 取 `l`（大图/单列）或 `n`（普通/双列）。
 * 带上质量档是必要的：单列与双列请求的是**不同尺寸的位图**，共用键会让
 * 小图命中大图槽位（Coil 会因尺寸不足重新解码，等于白算）。
 *
 * @param identity 视频的稳定身份。调用方应传列表稳定键（`FeedItem.actionKey`），
 *   它在同一视频的任意次滚动/刷新里都不变，因此键可跨帧复用。
 * @param useLargeCover 是否请求大图变体（单列用大图）。
 */
fun resolveCoverCacheKey(identity: String, useLargeCover: Boolean): String {
    val trimmed = identity.trim()
    if (trimmed.isEmpty()) return ""
    return "cover_${trimmed}_${if (useLargeCover) "l" else "n"}"
}

@Composable
fun rememberBiliImageRequest(
    url: String?,
    variant: BiliImageVariant = BiliImageVariant.CardCover,
    /**
     * 可选的显式缓存键。传了就同时用于 memory 与 disk cache key，
     * 让调用方（如卡片动态取色）能用**同一个键**索引 Coil 的缓存。
     * 传 null/空则退回 Coil 的默认行为（按解析后的 URL 算）。
     */
    cacheKey: String? = null
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    return remember(context, url, variant, cacheKey) {
        val resolvedUrl = biliImageUrlForVariant(url, variant)
        ImageRequest.Builder(context)
            .data(resolvedUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (!cacheKey.isNullOrBlank()) {
                    memoryCacheKey(cacheKey)
                    diskCacheKey(cacheKey)
                }
            }
            .build()
    }
}

@Composable
fun BiliAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    variant: BiliImageVariant = BiliImageVariant.CardCover,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * 解码成功回调，拿到已加载的 [Bitmap]。
     *
     * 用途：推荐页卡片的「封面代表色」提取（[com.naaammme.bbspace.core.designsystem.color.VideoCardCoverColorStore]）。
     * 复用 Coil 已经解码好的位图，**不额外发一次图片请求**——这是上游的实现方式，
     * 也是这个功能能做到零额外网络/解码开销的原因。
     */
    onSuccess: ((Bitmap) -> Unit)? = null,
    /** 显式缓存键，见 [rememberBiliImageRequest] 的 `cacheKey`。 */
    cacheKey: String? = null
) {
    val request = rememberBiliImageRequest(url = url, variant = variant, cacheKey = cacheKey)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = onSuccess?.let { callback ->
            { state -> (state.result.image as? BitmapImage)?.bitmap?.let(callback) }
        }
    )
}
