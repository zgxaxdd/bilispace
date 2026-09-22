package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
     * 视频卡片圆角（iOS 26 大圆角语言；12 → 16 → **20dp**，终版方案 5.1）。
     * **一个角色只有一个来源**：首页卡容器、卡片封面、无缝打开的锚点与动画半径，
     * 全部取这个值。[player] 刻意与本值相等（无缝 morph 两端同值不跳变），
     * 改这里必须两端同步 —— 因为 [player] 直接写成 `= card`，同步是结构性保证的。
     *
     * **16 → 20 的理由（2026-09-22 真机截图对比）**：16dp 在 1272px / density 560 下
     * 折算约 57px 半径，落在 4:3 封面上弧长偏短，整屏看下来卡片仍偏"方"，
     * 与 iOS 26 的连续大圆角语汇不一致；20dp（约 71px）能与 Dock 32 / 弹层 28
     * 形成明确的半径阶梯，同时不激进到吃掉封面有效面积。
     */
    val card = 20.dp

    /**
     * 详情页播放器（展开态）圆角。
     *
     * 刻意与 [card] 取**同一个值**：无缝动画从卡片矩形 morph 到播放器矩形，两端圆角
     * 相同才不会在中途"跳一下"。此前播放器用 `MaterialTheme.shapes.extraLarge`
     * （STANDARD 28dp / ROUNDED 32dp）而卡片是 12dp，两端差 16~20dp，正是用户反馈的
     * "退出播放时卡片与播放器圆角不统一、十分割裂"。
     */
    val player = card

    /**
     * 详情页竖排（非展开宽度）时播放器区域的底部圆角。
     *
     * 与 [card] 取同值：竖排下同一屏里同时出现卡片与播放器，两个半径不一致会读作
     * "两套语言"。展开态另有 [player] 走 morph，三者同源才能真正成体系。
     */
    val playerCompact = card

    /**
     * iOS Inset Grouped 分组卡圆角（方案 2.5）。iOS 模式下 CapsuleGroup /
     * 设置分组使用；不随 CornerStyle 漂移（iOS 无该维度）。
     */
    val groupedList = 16.dp

    /** 弹层（BottomSheet / Dialog）顶部圆角（iOS 26 大圆角语言，终版方案 五）。 */
    val sheetTop = 28.dp

    /**
     * 悬浮 Dock（底部导航容器）圆角。iOS 26 大圆角语言定 32dp（终版方案 五）。
     *
     * 现代大圆角统一：**两种 [UiStyle] 共用本值**（此前 Material 档取
     * `MaterialTheme.shapes.extraLarge`，在 STANDARD 下只有 28dp、且随 CornerStyle
     * 漂移，同一底栏在不同设置下圆角不一致）。Dock 是全局唯一的主导航 chrome，
     * 它的圆角属于「组件约定」而非「主题档位」，因此收口到 token。
     */
    val dock = 32.dp

    /**
     * Dock 内部子控件（导航分段胶囊 / 搜索钮 / 首页图标格 / 小横条）圆角。
     *
     * **同心推导**：`dock(32) − 内边距(8)` = 24dp。外圆角与内圆角同心时，
     * 两条弧线曲率中心重合，视觉上才是「一块玻璃里嵌了几块玻璃」；
     * 若内层沿用 32dp，弧线会在 48dp 高的控件上过度收口，形成「方角套圆角」。
     */
    val dockChip = 24.dp

    /**
     * 顶栏玻璃容器圆角，**只作用于下边两角**（形状见 [topBarShape]）。
     *
     * 为什么是 24dp：顶栏底边之内还要放 Tab 行（自带 3dp 下内边距），倒圆半径越大，
     * 底边可用的直线段越短——24dp 弧线在「Tab 胶囊底边」那个高度上只向内收 ~4dp，
     * 而胶囊左边缘距玻璃左边缘有 8dp（顶栏左右内边距），净空足够；
     * 32dp 则收到 ~8dp，正好贴住胶囊边角，会切出「方角套圆角」。
     */
    val topBar = 24.dp

    /**
     * 顶栏玻璃形状：上边两角**刻意**留在 0dp，只有下边两角倒圆。
     *
     * 顶栏是贴屏幕顶、左右出血到屏幕边的整条 chrome。上角一旦倒圆，状态栏两侧
     * 就会漏出背后滚动的页面内容（时钟/电量底下透出图文），所以上角必须是直角；
     * 下边两角倒圆，顶栏才读作「从内容上方浮出的一张玻璃片」，
     * 与底栏 [dock]、芯片 [dockChip] 同属一套大圆角语汇。
     *
     * 玻璃链路对本形状的两点约束（改这里之前必须复查 `GlassSurfaceType.HEADER`）：
     * - LIQUID 档 `liquidGlassSurface` 的 `lens()` 依赖 CornerBasedShape 的角半径，
     *   本形状仍是 RoundedCornerShape（下角 24dp），折射沿下角正常收口；
     * - 该处的 `highlight` 必须继续为 null：BloomStroke 沿**整条轮廓**描边，
     *   而顶边与左右两条边贴着屏幕边/状态栏，描边会变成一圈怪异亮框。
     *   这个理由与圆角无关（顶边永远是直角），所以改圆角后**不要**恢复描边。
     */
    val topBarShape: Shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = topBar,
        bottomStart = topBar,
    )

    /**
     * 顶栏搜索框圆角。
     *
     * 搜索框实测高 ≈42dp（iOS `bodyLarge` 行高 22sp + 上下 10dp 内边距），
     * 取 20dp 已接近半高（21dp）：两端是完整圆弧，只剩 1dp 直段，观感即胶囊。
     * 旧的 10dp 是「输入框」语汇，与底栏 32dp 不在同一套语汇里。
     *
     * **两种 [UiStyle] 共用本值**：顶栏与底栏是同一条纵向 chrome，圆角必须同语汇；
     * 此前 Material 档走 `MaterialTheme.shapes.large`（随 [CornerStyle] 在 12/16/20dp
     * 之间漂移），同一顶栏在不同圆角设置下形状不一致。搜索页/历史搜索/直播/设置/
     * 发布记录那五处搜索框是**页内**输入控件，不属于本条 chrome，仍走各自主题档位。
     */
    val searchPill = 20.dp

    /**
     * 播放页控制条按钮圆角。按钮实测高 34dp，取高度一半得全圆端。
     *
     * 播放器浮层与主题色解耦（见 `VideoPlayerPane` 顶部约定：统一「黑遮罩 + 白前景」），
     * 但**圆角属于全局形状语言**，因此仍然收口到本 token，不随 [CornerStyle] 漂移。
     * 此前该处是裸字面量 `RoundedCornerShape(15.dp)`，与 34dp 高度不匹配（差 2dp 才全圆）。
     */
    val playerControl = 17.dp

    /**
     * 卡片**上**两角圆角（封面用）。
     *
     * 为什么卡片要拆成上下两半各自圆角：卡片是「封面 + 信息区」两块内容拼成的，
     * 若两块都用 [card] 全圆角，接缝处会各圆各的、中间露出一道背景色，读起来像
     * 「图 + 贴在下面的小面板」而不是一张卡片（BiliPai 上游同款结构：
     * `coverShape = topRounded` + `infoSurfaceShape = bottomRounded`）。
     * 拆开之后上下两块在接缝处都是直角，拼合无缝，整卡仍由外层全圆角收圆。
     *
     * **不要用 `RoundedCornerShape(topStart = r, topEnd = r)` 手写替代本函数**：
     * 那样写形状上确实等价（已用 javap 反查 androidx.compose.foundation 1.12.0 的
     * `RoundedCornerShape$default(FFFF…)` 确认：四个角各自默认 `fconst_0` 即 0dp，
     * **不会**从顶角继承），但它读起来像"只圆顶角"却是靠默认值碰巧成立，
     * 而且会把半径字面量散落到调用点。本仓库曾因此有 7 处
     * `RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)` 各写各的 28dp。
     */
    fun topRounded(radius: Dp): Shape = RoundedCornerShape(
        topStart = radius,
        topEnd = radius,
        bottomEnd = 0.dp,
        bottomStart = 0.dp,
    )

    /** 卡片**下**两角圆角（信息区用）。与 [topRounded] 配对，详见其注释。 */
    fun bottomRounded(radius: Dp): Shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = radius,
        bottomStart = radius,
    )
}
