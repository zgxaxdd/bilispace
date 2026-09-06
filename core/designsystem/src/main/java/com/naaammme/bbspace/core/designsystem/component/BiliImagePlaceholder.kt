package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * 图片占位容器：异步图片加载前后统一显示的底色层。
 */
@Composable
fun BiliImagePlaceholder(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val clippedModifier = if (shape != RectangleShape) modifier.clip(shape) else modifier
    Box(
        modifier = clippedModifier.background(color = containerColor, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
