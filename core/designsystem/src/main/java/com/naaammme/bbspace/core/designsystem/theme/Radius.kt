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
    /** 视频卡片／信息卡圆角。首页双列卡、排行榜卡片、Feed 骨架共用。 */
    val card = 12.dp
}
