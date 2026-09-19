package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

private val largeTitleRowHeight = 52.dp
private val smallBarHeight = 52.dp

/**
 * iOS 大标题顶栏的折叠状态。connection 必须挂在**内容滚动的祖先**上
 * （如 Scaffold 的 modifier）——topBar 与 content 在 Scaffold 里是兄弟节点，
 * 挂在 topBar 自身收不到内容滚动。
 */
@Stable
class BiliLargeTitleScrollState internal constructor(
    internal val maxCollapsePx: Float
) {
    internal var collapseOffsetPx by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            if (delta >= 0f) return Offset.Zero
            val prev = collapseOffsetPx
            collapseOffsetPx = (collapseOffsetPx - delta).coerceIn(0f, maxCollapsePx)
            return Offset(0f, collapseOffsetPx - prev)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            val delta = available.y
            if (delta <= 0f) return Offset.Zero
            val prev = collapseOffsetPx
            collapseOffsetPx = (collapseOffsetPx - delta).coerceIn(0f, maxCollapsePx)
            return Offset(0f, collapseOffsetPx - prev)
        }
    }
}

@Composable
fun rememberBiliLargeTitleScrollState(): BiliLargeTitleScrollState {
    val maxCollapsePx = with(LocalDensity.current) { largeTitleRowHeight.toPx() }
    return remember(maxCollapsePx) { BiliLargeTitleScrollState(maxCollapsePx) }
}

/**
 * 二级页顶栏，按界面风格分支：
 * - iOS：大标题导航（方案 2.2）——静止态 34sp 粗标题，滚动折叠为 17sp 居中
 *   小标题 + 底部 hairline；折叠语义同 M3 exitUntilCollapsed（上滑先收标题、
 *   列表到顶后下滑反向展开）。调用方把 [state].connection 挂到 Scaffold modifier。
 * - Material：普通 M3 TopAppBar，视觉与改造前一致，state 不参与。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BiliTopBar(
    title: String,
    state: BiliLargeTitleScrollState = rememberBiliLargeTitleScrollState(),
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    if (isIOS) {
        IOSLargeTitleTopBar(
            title = title,
            state = state,
            modifier = modifier,
            onBack = onBack,
            actions = actions
        )
    } else {
        TopAppBar(
            title = { Text(title) },
            modifier = modifier,
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = actions
        )
    }
}

@Composable
private fun IOSLargeTitleTopBar(
    title: String,
    state: BiliLargeTitleScrollState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val fraction = (state.collapseOffsetPx / state.maxCollapsePx).coerceIn(0f, 1f)
    // 小标题与 hairline 在折叠后半段淡入，大标题在前半段淡出，避免两标题同时可见
    val smallAlpha = ((fraction - 0.55f) / 0.45f).coerceIn(0f, 1f)
    val largeAlpha = 1f - (fraction / 0.6f).coerceIn(0f, 1f)
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant
    val bgColor = MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(smallBarHeight)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    IOSChevron(
                        color = MaterialTheme.colorScheme.primary,
                        pointingRight = false
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(smallAlpha)
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(largeTitleRowHeight * (1f - fraction))
                .clipToBounds()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .alpha(largeAlpha)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(smallAlpha)
                .background(hairlineColor)
        )
    }
}
