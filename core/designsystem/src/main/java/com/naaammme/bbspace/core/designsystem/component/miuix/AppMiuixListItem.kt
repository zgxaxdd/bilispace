package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles

/**
 * W2 批次 1：**Miuix 渲染层**的列表行原语（iOS Inset Grouped 的行）。
 *
 * ## 与上游 `AppMiuixListItem` 的对应关系
 * 上游把一行拆成 5 个槽位（overline / headline / supporting / leading / trailing），
 * 语义与本仓的 `CapsuleListItem` 一致；本仓只需要 4 个（title / subtitle / leading /
 * trailing），所以**不照搬 5 槽**，避免出现"永远没人填的槽位"。
 *
 * ## 尺寸（全部 token，不写 dp 字面量）
 * - 行高下限 = `BiliSpacing.xxxl * 1.5f` = **48dp**：Material 触控下限，iOS 分组行
 *   的通行行高；用 token 的倍数表达，避免为 48 新增标度（BRIEF §2 棘轮）；
 * - 左右内边距 = `BiliSpacing.lg`（16dp，与分组卡边距同网格）；
 * - 上下内边距 = `BiliSpacing.smPlus`（10dp，与 CapsuleListItem 的 iOS 档一致）；
 * - 标题 → 副标题间距 = `BiliSpacing.xs`（4dp）。
 *
 * ## 点击
 * 点击由本行的 `Modifier.clickable` 承担（与上游一致：上游 ListItem 也只是 Row，
 * 没有自带容器/涟漪语义）——外层分组卡负责底色与圆角。
 */
@Composable
fun AppMiuixListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val roles = biliColorRoles()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BiliSpacing.xxxl * 1.5f)
            // 全局拖拽反馈（2026-10-18 第十九轮，原语层接入）：本原语是设置页
            // 分组列表项的出口。**整行宽控件**用 `feedbackStrength` 压到 0.6 ——
            // 满强度会让一整行在按下时明显横移，读作"页面在晃"而不是"这一行被按到"。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f)
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.smPlus),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(BiliSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            AppMiuixText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = roles.primaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.heightIn(min = BiliSpacing.xs))
                AppMiuixText(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = roles.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(BiliSpacing.sm))
            trailing()
        }
    }
}
