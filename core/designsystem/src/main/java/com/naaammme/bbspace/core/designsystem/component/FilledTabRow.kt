package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/**
 * 顶部 Tab，按界面风格分支：
 * - iOS：Segmented Control（方案 2.3）——#767680 12% 灰底容器、9dp 圆角，
 *   选中滑块随选择**位移滑动**（A2：spring 弹性 + 文字颜色 150ms 渐变）。
 * - Material：现代胶囊 Tab（未选中无底色、选中胶囊高亮），视觉与改造前一致。
 */
@Composable
fun FilledTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 3.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    if (LocalUiStyle.current == UiStyle.IOS) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trailing == null) {
                IOSSegmented(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                IOSSegmented(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                trailing.invoke(this)
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = if (trailing == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.weight(1f)
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    Card(
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Text(
                            text = tab,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** iOS Segmented Control：滑块位移 spring 弹性（A2），文字颜色 150ms 渐变。 */
@Composable
private fun IOSSegmented(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val container = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val thumbColor = if (dark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    BoxWithConstraints(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(container)
    ) {
        val segmentWidth = maxWidth / tabs.size
        val thumbX by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "segmentThumbX"
        )
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(1.dp)
                .shadow(1.dp, RoundedCornerShape(7.dp), clip = false)
                .background(thumbColor, RoundedCornerShape(7.dp))
        )
        Row(modifier = Modifier.fillMaxSize()) {
            val onBackground = MaterialTheme.colorScheme.onBackground
            val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) onBackground else onSurfaceVariant,
                    animationSpec = tween(durationMillis = 150),
                    label = "segmentText"
                )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
