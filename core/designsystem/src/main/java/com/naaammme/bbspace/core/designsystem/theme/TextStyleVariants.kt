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

/**
 * 播放器时间文本（`mm:ss / h:mm:ss`）：`labelMedium` + **加粗** + **等宽数字**。
 *
 * 为什么需要它（2026-10-16 播放页 UI 现代化）：
 * 1. **等宽数字**（`tnum`）：时间文本是控制条里唯一每 500ms 就换一次内容的元素。
 *    不等宽时 `0:03` → `1:23:45` 的字形宽度变化会把右侧按钮组推着横移；`tnum`
 *    让数字位宽恒定，整排不抖（与列表里时长/播放量用同一套语言，见 [TABULAR_NUMS]）。
 * 2. **加粗**：纯白方案下"非默认值"靠**不透明度 + 字重**区分，时间作为只读信息
 *    需要与可点控件拉开层级。
 *
 * ⚠️ 刻意写成**普通 `@Composable` 函数**而不是 `fun TextStyle.xxx()` 扩展：
 * `ListAllocationStructureTest` 把扩展形式的出口数量钉死为 3
 * （`asBold` / `withFontSize` / `withTextColor`），新增扩展会让那条断言变红。
 * 写法与 [softShadowLabelSmall] 一致：先在组合里取基样式、再以它为键 `remember`，
 * 保证同一基样式只算一次（内联 `typography.x.copy(` 会被棘轮拦，且每次重组新建重对象）。
 */
@Composable
fun boldTabularLabelMedium(): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    return remember(base) {
        base.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = TABULAR_NUMS
        )
    }
}

/**
 * 播放器时间文本的**降级档**（2026-10-17 现代化重绘）：`labelSmall` + **Light** + 等宽数字。
 *
 * ## 为什么要从 [boldTabularLabelMedium] 降下来
 * 控制行长成"三级层级"之后（L1 主操作圆底 / L2 档位组连体胶囊 / L3 只读信息），
 * 时间文本是**唯一的只读信息**。它此前是 12sp **Bold**，与档位文字（12sp Medium）、
 * 图标（实心字形 ≈700 视觉重量）挤在同一档 —— 这正是用户说「文字太单调」的物理原因：
 * **一整排没有任何一个元素比别的更轻**。降成 11sp Light 后，"可点 / 可读"在字重维度上
 * 立刻分开（规格见 `docs/design/d-ios11-控制行重绘规格.md` §2）。
 *
 * ## 为什么还必须保留 `tnum`
 * 它是控制条里唯一每 500ms 就换一次内容的元素。不等宽时 `0:03` → `1:23:45` 的字形宽度
 * 变化会把右侧档位组推着横移；`tnum` 让数字位宽恒定，整排不抖（与列表里的时长/播放量
 * 同一套语言，见 [TABULAR_NUMS]）。
 *
 * ⚠️ 与 [boldTabularLabelMedium] 同一写法：普通 `@Composable` 函数（不是 `TextStyle` 扩展），
 * 且以基样式为键 `remember` —— 扩展形式会让 `ListAllocationStructureTest` 的
 * "出口数量 = 3" 断言变红，内联 `.copy(` 会被棘轮拦。
 */
@Composable
fun lightTabularLabelSmall(): TextStyle {
    val base = MaterialTheme.typography.labelSmall
    return remember(base) {
        base.copy(
            fontWeight = FontWeight.Light,
            fontFeatureSettings = TABULAR_NUMS
        )
    }
}
