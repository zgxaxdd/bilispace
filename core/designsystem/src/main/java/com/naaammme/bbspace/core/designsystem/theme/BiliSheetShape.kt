package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * 弹层（ModalBottomSheet）顶部形状的唯一来源（iOS 升级阶段 A，方案 §2-G5）。
 *
 * 此前 7 处 feature 文件各写一份 `if (isIOS) BiliRadius.topRounded(sheetTop) else
 * BottomSheetDefaults.ExpandedShape`——语汇决策散落在 feature 层，改一处只像一处。
 * 收口后各弹层只调 [biliSheetShape]，风格差异由 designsystem 内部决策。
 * 纯机械迁移：两种风格下的输出与迁移前逐像素一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun biliSheetShape(): Shape =
    if (LocalUiStyle.current == UiStyle.IOS) {
        BiliRadius.topRounded(BiliRadius.sheetTop)
    } else {
        BottomSheetDefaults.ExpandedShape
    }

/**
 * iOS 档"只圆上两角"的弹层形状；MATERIAL 档不该调用（无此语汇），保留以便
 * 需要 top-rounded 容器的场景显式取用。
 */
fun biliTopRoundedShape(radius: androidx.compose.ui.unit.Dp): Shape =
    RoundedCornerShape(topStart = radius, topEnd = radius)
