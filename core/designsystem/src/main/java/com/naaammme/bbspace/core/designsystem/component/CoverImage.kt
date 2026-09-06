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
                }
            )
        } else {
            fallbackContent?.invoke(this)
        }
        content?.invoke(this)
    }
}
