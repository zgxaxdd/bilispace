package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 角色色深浅判定：**只看 App 当前主题**。
 * 不得掺入 isSystemInDarkTheme()（GlassEffect 的口径是为玻璃渲染定的）——
 * 系统深色 + App 浅色时，掺入会导致角色层拿到深色值（浅灰字画在浅灰底上，
 * 对比度 1.02:1 完全隐形，真机实测「设置页文字看不清」即此）。 */
@Composable
private fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * 语义角色色（iOS 升级阶段 A，方案 §2-G2）。
 *
 * ## 为什么需要它
 * 此前组件拿 `onSurfaceVariant` / `outlineVariant` "拼凑"二级文字与分隔线——
 * 它们是 Material You 的槽位语义，不是设计角色。iOS 设计语言里这些是**一等角色**：
 * 一级/二级/三级文字 + separator + 分组标题/脚注各有固定灰阶。角色层补上之后，
 * 组件按角色取色，风格切换时角色值整体换源，feature 层不用再关心风格。
 *
 * ## 取值与现状的关系（零视觉回归）
 * - iOS 档：secondaryLabel 与现行 `onSurfaceVariant` 完全同值（0x993C3C43 /
 *   0x99EBEBF5——这正是 Apple HIG 的 secondaryLabel 定义），separator 与现行
 *   `outlineVariant` 同值；tertiary/groupHeader/groupFooter 是**新增角色**，
 *   只被新调用点消费，不改变任何现有像素。
 * - MATERIAL 档：全部回落 MaterialTheme 派生值，与组件此前手写的取值一致。
 */
@Immutable
data class BiliColorRoles(
    /** 一级文字：标题/正文主体。 */
    val primaryLabel: Color,
    /** 二级文字：副标题、说明、次要信息。 */
    val secondaryLabel: Color,
    /** 三级文字：占位符、禁用、时间戳等最弱信息。 */
    val tertiaryLabel: Color,
    /** 分隔线：iOS 细线语义（半透明灰，不随内容变化）。 */
    val separator: Color,
    /** 分组列表的 section 标题（iOS grouped list 惯例用小写强调色）。 */
    val groupHeader: Color,
    /** 分组列表的 section 脚注。 */
    val groupFooter: Color,
    /** 填充色：输入框/分段控件底（iOS systemFill 族，半透明中性灰）。 */
    val fill: Color,
    /** 破坏性操作（删除/取消关注）：iOS systemRed（浅 FF3B30 / 深 FF453A）。 */
    val destructive: Color
)

/** iOS 档固定灰阶（Apple HIG 取值，见 iOSColor.kt 顶部说明）。internal 供对比度审计测试直调。 */
internal fun iosColorRoles(dark: Boolean): BiliColorRoles = if (dark) {
    BiliColorRoles(
        primaryLabel = Color.White,
        secondaryLabel = Color(0x99EBEBF5),
        tertiaryLabel = Color(0x4DEBEBF5),
        separator = Color(0xA6545458),
        groupHeader = Color(0x99EBEBF5),
        groupFooter = Color(0x4DEBEBF5),
        fill = Color(0x7676802E),
        destructive = Color(0xFFFF453A)
    )
} else {
    BiliColorRoles(
        // 浅色次要文字已提到 AA（4.58:1）—— 用户反馈"浅色下设置页部分文字看不清"，
        // 3.1~3.4:1 的旧值不再使用。取值只从 SecondaryLabelAAVariants 来（UI-09：
        // 它一度是"平行的一份预案常量"，与这里的字面量重复，改一处就会漏另一处）。
        primaryLabel = Color.Black,
        secondaryLabel = Color(SecondaryLabelAAVariants.LIGHT),
        tertiaryLabel = Color(0x4D3C3C43),
        separator = Color(0x4A3C3C43),
        groupHeader = Color(SecondaryLabelAAVariants.LIGHT),
        groupFooter = Color(0x4D3C3C43),
        fill = Color(0x7676801F),
        destructive = Color(0xFFFF3B30)
    )
}

/**
 * MATERIAL 档回落：与组件此前手写取值一致，切换零回归。
 */
@Composable
private fun materialColorRoles(): BiliColorRoles {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    return BiliColorRoles(
        primaryLabel = onSurface,
        secondaryLabel = onSurfaceVariant,
        tertiaryLabel = onSurfaceVariant.copy(alpha = 0.72f),
        separator = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        groupHeader = MaterialTheme.colorScheme.primary,
        groupFooter = onSurfaceVariant,
        fill = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f),
        destructive = MaterialTheme.colorScheme.error
    )
}

/**
 * 次要文字的 AA 档取值（方案 §3-B 的"两版 token"）。
 *
 * **现状（UI-09 复核后修正）**：这不是"两份待翻转的平行 token"了 ——
 * [LIGHT] 已经是浅色 production 值（`materialColorRoles()` 的 secondaryLabel / groupHeader
 * 直接取它，字面量不再重复一份）；[DARK] 仍是**待拍板预案**，深色 production 还停在
 * 对齐 Apple secondaryLabel 的 0x99EBEBF5。
 *
 * 深色为什么不跟着翻：现状 secondaryLabel 刻意对齐 Apple secondaryLabel
 * （浅色实测 3.13–3.44:1，仅达大字号档 3:1 下限），浅色那档是因为有真实用户反馈
 * "设置页文字看不清"才提的（B0 归档时用户拍板）。深色没有对应的用户反馈，
 * 而没有"改前改后"真机对照就不擅自翻转全站文字深浅。
 *
 * 数值本身按 ColorContrastTest 的合成口径验证过（LIGHT 4.58:1 / DARK >7:1）。
 */
object SecondaryLabelAAVariants {
    // 浅色 0xBC(0.737) 合成到 grouped 背景 F2F2F7 上 ≈ 4.58:1（0xB8 只有 4.47，不达 AA——
    // 方案文档的 4.72 是按纯白算的，实测口径见 ColorContrastTest.secondaryLabelAAVariantActuallyMeetsAA）
        // 0xC8(0.784) 在最暗容器底上也有 ~5:1（0xBC 在 surfaceContainerHighest 上只有 4.38:1）
const val LIGHT = 0xC83C3C43L
    // 深色 0xB8 合成到 1C1C1E 上 >7:1，远超 AA
    const val DARK = 0xB8EBEBF5L
}

/** 语义角色色的唯一读取入口：组件层用它替代"拿 onSurfaceVariant 拼凑"。 */
@Composable
fun biliColorRoles(): BiliColorRoles =
    if (LocalUiStyle.current == UiStyle.IOS) {
        iosColorRoles(dark = isDarkTheme())
    } else {
        materialColorRoles()
    }
