package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

@Composable
fun CoverImage(
    url: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape? = MaterialTheme.shapes.medium,
    coverLarge: Boolean = false,
    coverFourThree: Boolean = false,
    fallbackContent: (@Composable BoxScope.() -> Unit)? = null,
    /** 解码成功回调（转发给 [BiliAsyncImage]），供封面代表色提取使用。 */
    onImageLoaded: ((android.graphics.Bitmap) -> Unit)? = null,
    /**
     * 显式缓存键（转发给 [BiliAsyncImage]）。
     *
     * 卡片动态取色需要它：取色结果按这个键缓存，Coil 的位图也按这个键缓存，
     * 两者同源才能保证"算过色的封面"与"已解码的封面"始终对得上。
     * 传 null 则退回 Coil 默认行为。
     */
    cacheKey: String? = null,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    BiliImagePlaceholder(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape ?: RectangleShape
    ) {
        if (!url.isNullOrBlank()) {
            BiliAsyncImage(
                url = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                variant = when {
                    coverLarge -> BiliImageVariant.CardCoverLarge
                    coverFourThree -> BiliImageVariant.CardCover43
                    else -> BiliImageVariant.CardCover
                },
                onSuccess = onImageLoaded,
                cacheKey = cacheKey
            )
        } else {
            fallbackContent?.invoke(this)
        }
        content?.invoke(this)
    }
}
