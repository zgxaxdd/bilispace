package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    IconButton(onClick = onClick, modifier = modifier) {
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
