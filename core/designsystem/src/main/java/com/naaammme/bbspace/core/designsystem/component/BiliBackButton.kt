package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/**
 * 风格感知的返回按钮（iOS 升级阶段 A，方案 §7.2 迁移项）。
 *
 * iOS：细线 chevron（tertiaryLabel 语义色）；MATERIAL：M3 ArrowBack。
 * 此前这一分支写在 feature 层（如 SearchTopBar），风格语汇决策收口进
 * designsystem 后，调用方只声明"这里有个返回按钮"。
 */
@Composable
fun BiliBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "返回"
) {
    IconButton(
        onClick = onClick,
        // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
        //
        // 接在本原语上：它是搜索页等页面顶栏返回钮的风格感知出口，
        // 与已接好的 `AppMiuixIconButton` 是**同一角色的两个实现**（Material / iOS 各一），
        // 两边必须同步接，否则同一颗返回钮在两种风格下反馈不一致。
        //
        // 注意本原语 **不会**与 `IOSChevron` 抢形变：chevron 是纯绘制叶子（无 clickable、
        // 无 pointerInput），形变只发生在本 `IconButton` 的 48dp 命中区上。
        modifier = modifier.appElasticPress()
    ) {
        if (LocalUiStyle.current == UiStyle.IOS) {
            IOSChevron(
                color = MaterialTheme.colorScheme.onBackground,
                pointingRight = false
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription
            )
        }
    }
}
