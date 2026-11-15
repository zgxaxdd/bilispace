package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

@Composable
fun <T> CapsuleGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    gap: Dp = 2.dp,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    itemContent: @Composable (item: T, index: Int, shape: Shape) -> Unit
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    val shapes = MaterialTheme.shapes

    Column(modifier = modifier) {
        // iOS Inset Grouped 的 section 标题/脚注：与卡片左缘对齐、内缩 16dp，
        // 由 [BiliGroupHeader]/[BiliGroupFooter] 提供默认排版（阶段 C，方案 §2-G7）
        header?.invoke()
        items.forEachIndexed { index, item ->
            itemContent(item, index, capsuleGroupShape(index, items.size, shapes, isIOS))
            if (index < items.lastIndex) {
                if (isIOS) {
                    // iOS Inset Grouped：条目间为组内 hairline 分隔线（底色与卡片同色，
                    // 线体左缩进 56dp 对齐文字），整组视觉上是一张连续的分组卡
                    GroupedHairline()
                } else {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(gap)
                    )
                }
            }
        }
        footer?.invoke()
    }
}

@Composable
fun CapsuleListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = Color.Unspecified,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    // 未显式指定底色时按风格取默认：iOS 分组行为卡片白（surfaceContainerLow），
    // Material 维持 surfaceContainer。iOS 页面底色是 grouped 灰，沿用 surfaceContainer
    // 会让行与页面同色、分层消失。
    val resolvedColor = if (containerColor == Color.Unspecified) {
        if (isIOS) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainer
    } else {
        containerColor
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
            //
            // 接在 `CapsuleListItem` 而不是每个调用点：本行是设置页分组列表
            // （`SettingCategory`）/ 搜索结果分组 / 隐私与播放记录等列表的**唯一行出口**，
            // 一处挂载覆盖全部消费方。
            //
            // **整行宽控件**用 `feedbackStrength = 0.6f`（与 `AppMiuixListItem`、
            // `appSearchElasticPress` 同一克制档）：满强度会让一整行在按下时明显横移，
            // 读作"页面在晃"而不是"这一行被按到"。
            //
            // `highlightShape = shape` 让光斑裁在本行自己的分组圆角内
            //（首行上圆/末行下圆/中间直角，见 [capsuleGroupShape]）；
            // 不传时默认是 50% 胶囊形，中间行（RectangleShape）会被裁成椭圆。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                highlightShape = shape
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = resolvedColor),
        border = biliDarkHairlineBorder(resolvedColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (isIOS) 48.dp else 84.dp)
                .padding(horizontal = BiliSpacing.lg, vertical = if (isIOS) BiliSpacing.smPlus else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                Box(
                    modifier = Modifier.padding(end = BiliSpacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    leadingContent()
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (trailingContent != null) {
                Box(
                    modifier = Modifier.padding(start = BiliSpacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    trailingContent()
                }
            }
        }
    }
}

/** iOS 组内分隔线：1dp 线体左缩进 56dp，其余部分与卡片同色以保持整组连续。 */
@Composable
private fun GroupedHairline() {
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    // 语义角色色（阶段 A）：separator 与原 outlineVariant 取值一致，词汇归位
    val lineColor = biliColorRoles().separator
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(cardColor)
    ) {
        Box(
            modifier = Modifier
                .padding(start = 56.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(lineColor)
        )
    }
}

private fun capsuleGroupShape(index: Int, count: Int, shapes: Shapes, isIOS: Boolean): Shape {
    if (isIOS) {
        val r = BiliRadius.groupedList
        return when {
            count <= 1 -> RoundedCornerShape(r)
            // 分组列表的"上下两半分角"统一走 BiliRadius 的配对助手，与首页卡片
            // （HomeVideoPage 的封面/信息区）同一套写法，见 topRounded 的注释。
            index == 0 -> BiliRadius.topRounded(r)
            index == count - 1 -> BiliRadius.bottomRounded(r)
            else -> RectangleShape
        }
    }
    val outer = shapes.large
    val inner = shapes.extraSmall
    return when {
        count <= 1 -> outer
        index == 0 -> outer.copy(
            bottomStart = inner.bottomStart,
            bottomEnd = inner.bottomEnd
        )
        index == count - 1 -> outer.copy(
            topStart = inner.topStart,
            topEnd = inner.topEnd
        )
        else -> inner
    }
}

/**
 * 分组列表 section 标题（iOS Inset Grouped 惯例）：footnote 半粗、secondaryLabel 灰、
 * 左缘内缩 16dp（与组内分隔线左缩进同一网格）。供 [CapsuleGroup] 的 header 槽使用。
 */
@Composable
fun BiliGroupHeader(text: String, modifier: Modifier = Modifier) {
    // labelLarge 本身即 SemiBold 14/20（iOS Headline 档）——不内联 copy，
    // 守住 ListAllocationStructureTest 的内联棘轮
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = biliColorRoles().groupHeader,
        modifier = modifier.padding(start = BiliSpacing.lg, top = BiliSpacing.md, bottom = BiliSpacing.xsPlus)
    )
}

/**
 * 分组列表 section 脚注：caption、tertiaryLabel、同网格内缩。
 */
@Composable
fun BiliGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = biliColorRoles().groupFooter,
        modifier = modifier.padding(start = BiliSpacing.lg, top = BiliSpacing.xsPlus, bottom = BiliSpacing.md)
    )
}
