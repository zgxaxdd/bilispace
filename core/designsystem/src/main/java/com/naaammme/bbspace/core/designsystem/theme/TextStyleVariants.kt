package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * 排版变体的**唯一出口**（BACKLOG UI-02）。
 *
 * ## 为什么要有这一层
 * `MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)` 直接写在参数上，
 * 是**每次重组新建一个 TextStyle** —— 它是重对象（约 20 个字段，内含 SpanStyle 等）。
 * 列表里每可见一行、每滚一帧都在造。
 *
 * 光靠 code review 拦不住，所以：
 * 1. 这里给三个带 `remember` 的出口，键是基样式本身 —— 主题/字体档变了才重新算；
 * 2. `DesignTokenLintTest` / `ListAllocationStructureTest` 把"业务层内联 `.copy(`"
 *    的计数钉成 **0**，要新用法必须先来这里加一个具名出口。
 *
 * 刻意不做一个通用的 `copy { … }` 透传：那等于把内联 copy 换个写法继续存在，
 * 棘轮也就白设（这是 W18 的反面 —— 规则要能被绕过就没意义）。
 */
@Composable
fun TextStyle.asBold(): TextStyle = remember(this) { copy(fontWeight = FontWeight.Bold) }

@Composable
fun TextStyle.withFontSize(size: TextUnit): TextStyle = remember(this, size) { copy(fontSize = size) }

@Composable
fun TextStyle.withTextColor(color: Color): TextStyle = remember(this, color) { copy(color = color) }

/**
 * 粗体正文的常用组合。写在这层而不是各调用点拼，是为了让"次要正文加粗"
 * 在全站只有一个值（同 V1 的角色色原则）。
 */
@Composable
fun boldBodyMedium(): TextStyle = MaterialTheme.typography.bodyMedium.asBold()

/**
 * 会话常驻计数（SW-30）：`labelSmall` + **柔和文字阴影**。
 *
 * 用途：竖屏连播的互动栏计数没有底衬，靠阴影在任何画面上保持可读
 * （对标 BiliPai 竖屏栏 `labelSmall + shadow(黑 50%, blur 8)`）。
 * 放这里而不是调用点内联 `copy(`：棘轮（`DesignTokenLintTest`）把业务层内联
 * copy 钉成 0，且 `remember` 保证同一基样式只算一次。
 */
@Composable
fun softShadowLabelSmall(): TextStyle {
    // MaterialTheme.typography 是组合期读取，必须先在组合里取值、再以它为键 remember
    // （与 asBold/withFontSize 的写法一致）
    val base = MaterialTheme.typography.labelSmall
    return remember(base) {
        base.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 8f))
    }
}
