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
import com.naaammme.bbspace.core.common.media.widthLimitedImageUrl

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

    /**
     * 播放器预览图：**保持原始比例**（不裁切）的限宽大图。
     * 刷视频模式的封面页 / 等首帧加载层用它 —— 那里要的是"完整封面居中显示"，
     * 而 [CardCoverLarge] 之类会按 16:9 裁切，竖屏视频的主体会被切掉。
     */
    PlayerPreview,
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
    variant == BiliImageVariant.PlayerPreview -> coverUrlFor(url, BiliCoverSize.PlayerPreview)
    variant == BiliImageVariant.CardCover -> coverThumbnailUrl(url)
    // 这两档原先落到 `thumbnailUrl(url, q)` —— 那**只限质量不限宽度**，图床按原尺寸返回。
    // 直播铺满背景的封面与聊天/预览九宫格的大图因此走的是"整张原图"的下载 + 解码。
    variant == BiliImageVariant.Banner -> widthLimitedImageUrl(url, BANNER_MAX_WIDTH_PX, variant.q)
    variant == BiliImageVariant.PreviewThumb ->
        widthLimitedImageUrl(url, PREVIEW_THUMB_MAX_WIDTH_PX, variant.q)

    else -> thumbnailUrl(url, variant.q)
}

/**
 * 直播播放器背景铺满用的封面宽度上限（px）。
 *
 * 手机横屏铺满也就 1280~1600px 宽，取 1280 与上游 `3d5c296cb` 的夹取量级一致；
 * 再大属于"人眼看不出区别但内存照单全收"的那一段。
 */
const val BANNER_MAX_WIDTH_PX = 1280

/** 预览缩略图（IM 会话、图片九宫格）的宽度上限（px）：它们是网格小图，不是主画面。 */
const val PREVIEW_THUMB_MAX_WIDTH_PX = 480

/**
 * 信息流卡片按"单列/双列"实际使用的封面变体。
 *
 * 必须与 [CoverImage] 里 `coverLarge -> CardCoverLarge / coverFourThree -> CardCover43`
 * 的分支保持一致 —— 预加载传错变体就等于请求了另一个 URL，缓存必然对不上。
 */
fun feedCoverVariant(singleColumn: Boolean): BiliImageVariant =
    if (singleColumn) BiliImageVariant.CardCoverLarge else BiliImageVariant.CardCover43

@Composable
fun rememberBiliImageRequest(
    url: String?,
    variant: BiliImageVariant = BiliImageVariant.CardCover
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    return remember(context, url, variant) {
        val resolvedUrl = biliImageUrlForVariant(url, variant)
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
