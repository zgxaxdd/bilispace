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
     * 视频卡片圆角。**一个角色只有一个来源**：首页卡容器、卡片封面、无缝打开的锚点
     * 与动画半径，全部取这个值。此前这四处各用各的（容器随主题 24dp、双列封面固定
     * 12dp、单列封面是直角、动画又是另一套），导致卡片自身圆角不一致、且与播放器
     * 动画圆角对不上——用户可见的"割裂感"即来源于此。
     */
    val card = 12.dp

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
}
