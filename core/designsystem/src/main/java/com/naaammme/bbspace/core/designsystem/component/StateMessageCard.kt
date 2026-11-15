package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixCard
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixTextAction
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

/**
 * 三态（空 / 错误 / 提示）的**统一出口**（UI-04 规范：不许各屏自造卡片）。
 *
 * ## 2026-10-17 现代化重绘（`StateMessageCard` 全站 78 处调用，改一处全站生效）
 *
 * 旧形态是「一个 surfaceContainerLow 的 Card + 左对齐文字」，三个问题：
 * 1. **所有态都长成一块卡片** —— 连"已经到底了""加载更多中"这种一句话提示也套一张底卡，
 *    信息流里读起来像"这里坏了"；现代做法是**一句话就只给一句话**，需要解释时才用卡片。
 * 2. **只有文字层级**：没有图标、没有标题档，空态与错误态除了颜色几乎不可区分。
 * 3. 无对齐语言：全站左对齐，空态应当居中。
 *
 * 现在分两档：
 * - **轻态**（`title == null && icon == null && actionText == null`）：**不画底卡**，
 *   居中的一行 `bodySmall` + `onSurfaceVariant` —— 覆盖"到底了 / 加载中 / 没有更多"这类
 *   占全站多数的调用点。
 * - **重态**（给了 `icon` / `title` / 动作之一）：卡片 = **图标 → 标题(SemiBold) → 说明 → 动作**
 *   四段式，居中留白走 token。空态与错误态都调用同一出口，靠 `isError` 换色调。
 *
 * 向后兼容：`text` 仍是第一个位置参数，旧调用一行都不用改（只是视觉变轻）。
 *
 * @param icon 可选图标（线性风格优先，`Icons.Outlined.*`）；给了它就自动进入"重态"。
 */
@Composable
fun StateMessageCard(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    isError: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    // 错误态只换"说明与图标"的色调，标题始终用 onSurface —— 否则整块读成红色警告牌。
    val bodyColor = if (isError) scheme.error else scheme.onSurfaceVariant
    // ⚠️ **错误态永远走"重态"**（评审 B1，确定性缺陷）：全站 78 处调用里 10 处是
    // `isError = true` 且只传了 text —— 若按"没有 title/icon/action 就是轻态"处理，
    // 动态/消息首屏错误、详情页错误、楼中楼错误会从"一块错误卡"退化成一整屏顶部一行红字，
    // 用户读作"没渲染"。空/提示类才允许轻态。
    val light = !isError && title == null && icon == null && actionText == null

    if (light) {
        // 轻态也**必须居中**：调用点普遍传 `fillMaxSize()`，直接给 Text 会变成
        // "空白页顶部一行小字"（评审 B1 的第二个问题）。Box 居中后再交给 Text。
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = BiliSpacing.lg),
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    // W2 批次 1 接线（2026-10-18）：**Card 原语改走 Miuix 渲染层**。
    // 选它当第一个真实消费者，是因为本组件是全站三态的**唯一出口**（78 处调用点）：
    // 空态/错误态立刻拿到 miuix 的超椭圆（squircle）圆角与原生按压缩放，
    // 而调用方 API 一字未改。squircle 是形状语言（不是 iOS 专属），MATERIAL 档同样受益。
    AppMiuixCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = bodyColor,
                    modifier = Modifier.size(BiliSpacing.xxxl)
                )
            }
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = bodyColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (actionText != null && onAction != null) {
                // 动作走同一族的 TextAction（第二个真实消费者），与卡片共用圆角/按压语义。
                //
                // ## 全局拖拽反馈（2026-10-18 第十九轮）
                // `AppMiuixTextAction` 本身**没有**接 `appElasticPress`（它的兄弟
                // `AppMiuixButton` 接了，这个漏了——见 task-9 的 A 清单里"动作按钮"一条）。
                // 在调用点补挂而不改原语，是因为该原语在本批次之外还有别的消费者，
                // 而三态卡的动作用户是**唯一确证**需要反馈的那一类（点"重试"必须手感一致）。
                // `appElasticPress` 有 marker 去重：日后原语自己补挂也不会叠加。
                AppMiuixTextAction(
                    text = actionText,
                    onClick = onAction,
                    modifier = Modifier.appElasticPress()
                )
            }
        }
    }
}
