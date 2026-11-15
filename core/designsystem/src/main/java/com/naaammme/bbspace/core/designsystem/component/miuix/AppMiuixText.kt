package com.naaammme.bbspace.core.designsystem.component.miuix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.naaammme.bbspace.core.designsystem.theme.biliColorRoles
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * W2 批次 1：**Miuix 渲染层**的 Text 原语（iOS 档唯一出口）。
 *
 * ## 为什么需要它
 * miuix 的 `Text` 与 Material3 的 `Text` 不是同一个实现：字形排布、行高策略与
 * 默认字重都不同。iOS 档的控件若混用两种 Text，同屏会出现"同一句话两套基线"。
 * 本仓的排版 token 仍然只有一套（`iOSTypography`）—— 调用方永远传
 * `MaterialTheme.typography.*`，本函数负责把它交给 miuix 渲染。
 *
 * ## 参数为什么这么少
 * miuix `Text` 有 17 个参数（autoSize / 字距 / 行高 / inlineContent…）。全量透传
 * 等于把 miuix 的 API 面搬进本仓，后续升级要逐参数核对。这里只保留**本仓实际用到**
 * 的 8 个：文本、修饰符、样式、颜色、字重覆盖、对齐、最大行数、溢出。需要新能力时
 * 再加一个**有语义名字**的参数，而不是透传一个 `*`。
 */
@Composable
fun AppMiuixText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = biliColorRoles().primaryLabel,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    MiuixText(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = style
    )
}
