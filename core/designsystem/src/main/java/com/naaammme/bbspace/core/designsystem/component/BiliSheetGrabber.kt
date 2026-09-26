package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.BiliColorRoles
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles

/**
 * 弹层顶部 grabber（iOS 升级阶段 C，方案 §2-G5）。
 *
 * iOS 规格（对照 HIG 与 compose-cupertino 的 sheet 语汇）：
 * 36×5dp、全圆角、tertiaryLabel 灰——比 M3 默认把手（32×4、onSurfaceVariant）
 * 更宽更扁，配合 iOS 28dp 大圆角弹层阅读为"可拖动的抽屉沿"。
 *
 * 风格自适应：MATERIAL 档渲染 M3 默认 [BottomSheetDefaults.DragHandle]，
 * 迁移前后 Material 弹层逐像素一致；调用方用
 * `ModalBottomSheet(dragHandle = { BiliSheetGrabber() })` 一行接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliSheetGrabber(modifier: Modifier = Modifier) {
    if (LocalUiStyle.current != UiStyle.IOS) {
        BottomSheetDefaults.DragHandle(modifier = modifier)
        return
    }
    val roles: BiliColorRoles = biliColorRoles()
    Box(
        modifier = modifier
            .padding(vertical = BiliSpacing.sm)
            .size(width = 36.dp, height = 5.dp)
            .background(roles.tertiaryLabel, RoundedCornerShape(2.5.dp))
    )
}
