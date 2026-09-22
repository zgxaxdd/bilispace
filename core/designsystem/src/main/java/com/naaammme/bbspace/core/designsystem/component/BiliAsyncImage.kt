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

@Composable
fun rememberBiliImageRequest(
    url: String?,
    variant: BiliImageVariant = BiliImageVariant.CardCover
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    return remember(context, url, variant) {
        val resolvedUrl = when {
            variant.original -> originImageUrl(url)
            variant == BiliImageVariant.Avatar -> avatarThumbnailUrl(url)
            variant == BiliImageVariant.CardCoverLarge -> coverUrlFor(url, BiliCoverSize.Large)
            variant == BiliImageVariant.CardCover43 -> coverUrlFor(url, BiliCoverSize.Small43)
            variant == BiliImageVariant.CardCover -> coverThumbnailUrl(url)
            else -> thumbnailUrl(url, variant.q)
        }
        ImageRequest.Builder(context)
            .data(resolvedUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
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
    onSuccess: ((Bitmap) -> Unit)? = null
) {
    val request = rememberBiliImageRequest(url = url, variant = variant)
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
