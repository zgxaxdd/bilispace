package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin

/**
 * 加载指示器（刷视频取流时用）——**一枚彗尾弧**。
 *
 * ## 观感（做减法之后的形态）
 * 整个动画只有一个元素：一枚 **278° 的细弧**，头部亮、尾部渐隐（扫光渐变），
 * 恒速旋转 + 极缓的整体呼吸。没有粒子、没有内圈、没有实心光球 ——
 * 粒子与多层叠光在 155dp 的尺寸里读起来是"噪"，而单弧的**方向感**才是"在加载"。
 *
 * 为什么这样更"现代"：
 * - 单一主体 = 视觉噪音低，符合当前系统级加载指示（iOS / Material 3 expressive）的取法；
 * - 头部亮尾部透明 = 有方向、有速度感，不需要靠"转得快"表达"在动"；
 * - 恒速 + 呼吸（而不是缓入缓出） = 长时间盯着也不会觉得"一卡一卡"。
 *
 * ## 性能约定（与 `FrameBudgetLintTest` 同一口径）
 * - 相位**只经绘制期读取**：`mutableFloatStateOf` 在 `Canvas` 的 draw lambda 里读，
 *   失效只落到绘制阶段、**不触发重组**；既不用 `rememberInfiniteTransition`（棘轮上限 2），
 *   也不用 `animate*AsState`（组合期读值 = 每帧重组宿主）；
 * - 每帧只有一次 `drawArc`（单元素，比之前的"核心 + 双弧 + 22 颗粒子 × 4 段拖尾"
 *   少了约 90 次 draw 调用）；
 * - 尺寸全部取 `size.minDimension` 的比例，**不引入任何 dp/sp 字面量**
 *   （DesignTokenLintTest 的棘轮不为本组件让路）。
 *
 * @param color 前景色。默认白色 —— 它用于播放器/封面之上的深色遮罩。
 */
@Composable
fun BiliParticleLoader(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            // 与系统动画时长缩放联动：无障碍设置里关掉动画时不会空转刷帧
            withInfiniteAnimationFrameMillis { millis ->
                phase.value = (millis % LOOP_MS).toFloat() / LOOP_MS.toFloat()
            }
        }
    }
    Canvas(modifier = modifier) {
        drawCometArc(phase = phase.value, color = color)
    }
}

/**
 * 单弧绘制：先按"轨道槽"画一圈极淡的底环（告诉眼睛这是一枚环，不是一段飘着的线），
 * 再叠主体彗尾弧。
 *
 * 弧的几何：角 0（3 点钟）起、顺时针 [ARC_SPAN]，所以渐变也按同方向铺，
 * 头部落在 `ARC_SPAN / 360` 处 —— 这是"渐变要与弧对齐"的关键，写错就会变成
 * "亮点在中间、两头一样暗"。
 */
private fun DrawScope.drawCometArc(phase: Float, color: Color) {
    val minDim = size.minDimension
    if (minDim <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minDim * ARC_R
    val stroke = minDim * ARC_STROKE

    // 极淡底环：全周 8% 白，给"环"一个参照物（也避免深色底上只有一段线显得碎）
    drawArc(
        color = color.copy(alpha = TRACK_ALPHA),
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )

    // 呼吸：整体透明度在两拍之间缓慢起伏（不是尺寸抖动 —— 尺寸变化会读成"闪"）
    val breath = BREATH_MIN + BREATH_SPAN * (0.5f + 0.5f * sin(phase * TWO_PI * BREATH_CYCLES))
    val headStop = ARC_SPAN / 360f

    rotate(degrees = phase * 360f, pivot = center) {
        drawArc(
            brush = Brush.sweepGradient(
                // 与弧同向：尾部几乎透明 → 中段渐亮 → 头部最亮 → 收尾归零。
                // 用 `vararg Pair<Float, Color>` 这个重载（带 stops 的那个签名在 sweepGradient 上
                // 不存在，写错会直接报"没有候选重载"）。
                0f to color.copy(alpha = 0f),
                headStop * 0.45f to color.copy(alpha = 0.30f * breath),
                headStop * 0.92f to color.copy(alpha = 0.95f * breath),
                headStop to color.copy(alpha = 1f * breath),
                1f to color.copy(alpha = 0f),
                center = center
            ),
            startAngle = 0f,
            sweepAngle = ARC_SPAN,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** 一个完整周期（毫秒）。2.6s：恒速转一圈，慢到不焦虑、快到看得出在动。 */
private const val LOOP_MS = 2_600L

private const val TWO_PI = (2.0 * PI).toFloat()

/** 弧半径与线宽（占 `minDimension` 的比例）。 */
private const val ARC_R = 0.44f
private const val ARC_STROKE = 0.013f

/** 主体弧的张角：278° 留出 82° 缺口 —— 缺口是方向感的来源，别再加大。 */
private const val ARC_SPAN = 278f

/** 底环透明度：够看出"是一枚环"，又不与主体抢。 */
private const val TRACK_ALPHA = 0.08f

/** 呼吸：两拍一次、幅度 12%（只改透明度）。 */
private const val BREATH_CYCLES = 2f
private const val BREATH_MIN = 0.86f
private const val BREATH_SPAN = 0.14f
