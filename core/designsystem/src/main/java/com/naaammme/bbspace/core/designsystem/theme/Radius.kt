package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * 组件级圆角 token。
 *
 * 与 [buildShapes] 的分工：
 * - [buildShapes] 产出 Material3 的 `Shapes` **档位集合**（extraSmall…extraLarge），
 *   随主题 [CornerStyle] 整体变化，供 Material 组件与通用容器使用。
 * - 本对象是**具体组件的圆角约定**：同一组件在所有 [CornerStyle] 下保持一致，
 *   不随档位漂移。卡片（封面/信息卡）的 12dp 就是这类约定——它此前作为
 *   `RoundedCornerShape(12.dp)` 字面量散落在首页与排行榜等处（8 处），
 *   改动一处要全局搜替换，且无法被主题感知。
 *
 * 注意：**不要**把这里的值换成 `MaterialTheme.shapes.medium` 等档位——
 * `medium` 在 STANDARD 是 12dp，但在 ROUNDED 是 16dp、CIRCULAR 是 20dp，
 * 直接替换会改变当前视觉（属于回归，而非收敛）。需要随主题变化的容器请继续
 * 使用 `MaterialTheme.shapes.*`。
 */
object BiliRadius {
    /**
     * 视频卡片圆角（iOS 26 大圆角语言 12→16dp，终版方案 5.1）。**一个角色只有
     * 一个来源**：首页卡容器、卡片封面、无缝打开的锚点与动画半径，全部取这个值。
     * [player] 刻意与本值相等（无缝 morph 两端同值不跳变），改这里必须两端同步。
     */
    val card = 16.dp

    /**
     * 详情页播放器（展开态）圆角。
     *
     * 刻意与 [card] 取**同一个值**：无缝动画从卡片矩形 morph 到播放器矩形，两端圆角
     * 相同才不会在中途"跳一下"。此前播放器用 `MaterialTheme.shapes.extraLarge`
     * （STANDARD 28dp / ROUNDED 32dp）而卡片是 12dp，两端差 16~20dp，正是用户反馈的
     * "退出播放时卡片与播放器圆角不统一、十分割裂"。
     */
    val player = card

    /** 详情页竖排（非展开宽度）时播放器区域的底部圆角。 */
    val playerCompact = 16.dp

    /**
     * iOS Inset Grouped 分组卡圆角（方案 2.5）。iOS 模式下 CapsuleGroup /
     * 设置分组使用；不随 CornerStyle 漂移（iOS 无该维度）。
     */
    val groupedList = 16.dp

    /** 弹层（BottomSheet / Dialog）顶部圆角（iOS 26 大圆角语言，终版方案 五）。 */
    val sheetTop = 28.dp

    /**
     * 悬浮 Dock（底部导航容器）圆角。iOS 26 大圆角语言定 32dp（终版方案 五）。
     * 注意 Material 模式仍取 MaterialTheme.shapes.extraLarge，勿改回。
     */
    val dock = 32.dp
}
