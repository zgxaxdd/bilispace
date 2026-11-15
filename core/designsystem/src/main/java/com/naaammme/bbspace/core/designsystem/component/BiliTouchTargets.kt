package com.naaammme.bbspace.core.designsystem.component

import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

/**
 * 可点件的**命中区高度**（任务 book task-9 C1：「同一 Row 内可点件高度一致」）。
 *
 * ## 为什么需要这一层
 * 同一行里混排 `IconButton`（M3 默认命中区 48dp）与 `TextButton`（M3 默认 40dp）时，
 * 视觉上文字钮比图标钮**矮 8dp**，热区也不一样 —— 用户读作"这两颗不是一类的"。
 * 这类不一致此前集中出现在各页顶栏的 `actions` 槽（历史 / 收藏 / 稍后再看 /
 * 空间 / 动态详情 / 诊断）。本批把口径收成两个名字，而不是在每页各写一遍
 * `defaultMinSize(...)` 的裸算式。
 *
 * ## 数值来源（零新增 dp 字面量）
 * 两个值都由 [BiliSpacing] 表达，`DesignTokenLintTest` 的 dp 棘轮不受影响：
 * - [comfortable] = `xxxl(32) × 1.5` = **48dp**：Material 触控下限，也是 M3 `IconButton`
 *   的默认命中区。**与图标钮同排时必须取这个值** —— 想"统一成 44"反而会让文字钮
 *   比图标钮矮 4dp，制造新的不一致；
 * - [minimum] = `xxxl(32) + md(12)` = **44dp**：iOS HIG 触控下限。**没有图标钮邻居**的
 *   纯文字可点件（对话框动作、列表内动作）按这条达标线走。
 *
 * ## 与既有原语常量的关系（刻意不复用，也不要合并）
 * `AppMiuixIconButton` 的私有 `AppMiuixMinTouchSize` 也是 44dp、`AppMiuixListItem` 的
 * 行高下限也是 48dp，但那是**各自组件的固有规格**（图标钮在顶栏里必须 44 才不撑高
 * 标题行；分组行 48 是 iOS 分组列表行高）。本对象是**跨组件的对齐口径**，两者语义不同：
 * 组件规格变化时不该连带改全站的对话框动作热区。要合并之前先回答：这两个数会不会
 * 一起变？
 *
 * ⚠️ 不要为了"少两个常量"把这两个值塞进 [BiliSpacing]：那是**间距**标度，
 * 触控命中区是**无障碍规格**，跟着间距一起调会是事故。
 */
object BiliTouchTargets {
    /** 48dp：Material 触控下限，等于 M3 `IconButton` 默认命中区。 */
    val comfortable = BiliSpacing.xxxl * 1.5f

    /** 44dp：iOS HIG 触控下限。 */
    val minimum = BiliSpacing.xxxl + BiliSpacing.md
}
