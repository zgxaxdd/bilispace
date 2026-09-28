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
import kotlin.math.cos
import kotlin.math.sin

/**
 * 加载指示器（刷视频模式等取流时用）——「光尘轨道」式。
 *
 * ## 观感
 * 三层叠出来的现代感（全部相对 `size` 计算，放到任意尺寸都成立）：
 * 1. **核心光球**：径向渐变柔光 + 中心亮点，两拍一次呼吸；
 * 2. **外圈扫光弧**：240° 渐变弧（两端透明）每周期转一圈，像在"扫描"；
 * 3. **两层轨道粒子**：内圈 8 颗反向快转、外圈 14 颗正向慢转，每颗带 4 段拖尾
 *    （位置回退采样 + alpha 递减），读起来是两股反向流动的光尘。
 *
 * 不用 Material 转圈：那是"系统忙"的语义，这里要的是"内容马上来"的等待感。
 *
 * ## 性能约定（与 `FrameBudgetLintTest` 同一口径）
 * - 相位**只经绘制期读取**：`mutableFloatStateOf` 在 `Canvas` 的 draw lambda 里读，
 *   失效只落到绘制阶段、**不触发重组**；因此既不用 `rememberInfiniteTransition`
 *   （棘轮上限 2），也不用 `animate*AsState`（组合期读值 = 每帧重组宿主）；
 * - 粒子参数在 `remember` 里一次性算进 `FloatArray`（确定性 LCG，无 `Random`、
 *   无 List、无逐帧分配），绘制循环里只有算术与 `drawCircle` / `drawArc`；
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
    val field = remember { ParticleField() }
    LaunchedEffect(Unit) {
        while (true) {
            // 与系统动画时长缩放联动：无障碍设置里关掉动画时不会空转刷帧
            withInfiniteAnimationFrameMillis { millis ->
                phase.value = (millis % LOOP_MS).toFloat() / LOOP_MS.toFloat()
            }
        }
    }
    Canvas(modifier = modifier) {
        drawOrbitLoader(field = field, phase = phase.value, color = color)
    }
}

/** 一颗粒子的静态参数；全部在 `remember` 里算好，绘制期只读。 */
private class ParticleField {
    val baseAngle = FloatArray(PARTICLE_COUNT)
    val radius = FloatArray(PARTICLE_COUNT)
    val turns = FloatArray(PARTICLE_COUNT)
    val delay = FloatArray(PARTICLE_COUNT)
    val sizeFactor = FloatArray(PARTICLE_COUNT)

    init {
        // 确定性 LCG：同一版本下粒子排布稳定（不用 Random，避免每次组合都不一样）
        var seed = 0x2F6E2B1
        fun next(): Float {
            seed = seed * 1_664_525 + 1_013_904_223
            return ((seed ushr 8) and 0xFFFF) / 65_535f
        }
        for (i in 0 until PARTICLE_COUNT) {
            val outer = i % 5 < 3 // 3:2 分布到外/内两层，避免两层数量一样显得呆板
            baseAngle[i] = (i.toFloat() / PARTICLE_COUNT) * TWO_PI * 1.7f + next() * 0.6f
            radius[i] = (if (outer) OUTER_RING else INNER_RING) * (0.94f + 0.12f * next())
            // 内圈反向且更快：两股光尘交错，视觉上"活"
            val speed = if (outer) OUTER_TURNS else -INNER_TURNS
            turns[i] = speed * (0.9f + 0.2f * next())
            delay[i] = next()
            sizeFactor[i] = DOT_BASE * (0.55f + 0.9f * next())
        }
    }
}

private fun DrawScope.drawOrbitLoader(field: ParticleField, phase: Float, color: Color) {
    val minDim = size.minDimension
    if (minDim <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)

    // ── 1) 核心光球：径向渐变 + 呼吸（外圈透明 → 中心亮）
    val pulse = 0.5f + 0.5f * sin(phase * TWO_PI * 2f)
    val glowRadius = minDim * (CORE_FRAC + CORE_PULSE_FRAC * pulse) * CORE_GLOW_MULT
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = CORE_ALPHA_MIN + CORE_ALPHA_SPAN * pulse),
                color.copy(alpha = 0f)
            ),
            center = center,
            radius = glowRadius
        ),
        radius = glowRadius,
        center = center
    )
    drawCircle(
        color = color.copy(alpha = 0.9f),
        radius = minDim * CORE_FRAC * 0.26f,
        center = center
    )

    // ── 2) 外圈扫光弧：渐变两端透明，每周期整圈（连同下面 rotate 一起读相位）
    val arcRadius = minDim * ARC_R
    val arcStroke = minDim * ARC_STROKE
    rotate(degrees = phase * 360f, pivot = center) {
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    color.copy(alpha = 0f),
                    color.copy(alpha = 0.85f),
                    color.copy(alpha = 0f)
                ),
                center = center
            ),
            startAngle = 0f,
            sweepAngle = ARC_SPAN,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = arcStroke, cap = StrokeCap.Round)
        )
    }
    // 内圈细弧反向转：与外圈形成"对切"，比单弧更像仪表盘
    val innerArcRadius = minDim * INNER_ARC_R
    rotate(degrees = -phase * 360f * INNER_ARC_TURNS, pivot = center) {
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 40f,
            sweepAngle = INNER_ARC_SPAN,
            useCenter = false,
            topLeft = Offset(center.x - innerArcRadius, center.y - innerArcRadius),
            size = Size(innerArcRadius * 2f, innerArcRadius * 2f),
            style = Stroke(width = minDim * INNER_ARC_STROKE, cap = StrokeCap.Round)
        )
    }

    // ── 3) 两层轨道粒子 + 拖尾（每颗回退采样 TRAIL_SAMPLES 个位置，alpha/尺寸递减）
    for (i in field.baseAngle.indices) {
        val cycles = phase * field.turns[i] * TWO_PI
        val angle = field.baseAngle[i] + cycles
        val radius = minDim * field.radius[i]
        val dot = minDim * field.sizeFactor[i]
        // 相位错开的闪烁：同一颗粒子也有明暗呼吸，避免"整圈同时亮"
        val blink = 0.65f + 0.35f * sin((phase + field.delay[i]) * TWO_PI)
        for (sample in 0 until TRAIL_SAMPLES) {
            val back = sample * TRAIL_STEP_RAD * (if (field.turns[i] >= 0f) 1f else -1f)
            val fade = 1f - sample.toFloat() / TRAIL_SAMPLES
            val alpha = PARTICLE_ALPHA * blink * fade * fade
            if (alpha <= 0.02f) continue
            val a = angle - back
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dot * (1f - 0.45f * (1f - fade)),
                center = Offset(
                    x = center.x + cos(a) * radius,
                    y = center.y + sin(a) * radius
                )
            )
        }
    }
}

/** 一个完整周期（毫秒）。2.4s：慢到看得清轨道、快到不像卡住。 */
private const val LOOP_MS = 2_400L

/** 粒子总数：3:2 分到外/内两层。 */
private const val PARTICLE_COUNT = 22

private const val TWO_PI = (2.0 * PI).toFloat()

/** 外/内轨道半径（占 `minDimension` 的比例）。 */
private const val OUTER_RING = 0.355f
private const val INNER_RING = 0.225f

/** 每周期转的圈数（内圈为负 = 反向）。 */
private const val OUTER_TURNS = 1.0f
private const val INNER_TURNS = 1.6f

/** 单颗粒子基准半径（占 `minDimension` 的比例，带 0.55~1.45 倍抖动）。 */
private const val DOT_BASE = 0.013f

/** 拖尾：采样段数与每段回退弧度。 */
private const val TRAIL_SAMPLES = 4
private const val TRAIL_STEP_RAD = 0.085f

private const val PARTICLE_ALPHA = 0.9f

/** 核心光球：基准半径、呼吸幅度与光晕倍数。 */
private const val CORE_FRAC = 0.085f
private const val CORE_PULSE_FRAC = 0.022f
private const val CORE_GLOW_MULT = 2.8f
private const val CORE_ALPHA_MIN = 0.16f
private const val CORE_ALPHA_SPAN = 0.22f

/** 外圈扫光弧：半径、线宽、弧长。 */
private const val ARC_R = 0.455f
private const val ARC_STROKE = 0.018f
private const val ARC_SPAN = 250f

/** 内圈细弧：半径、线宽、弧长、每周期圈数（反向）。 */
private const val INNER_ARC_R = 0.30f
private const val INNER_ARC_STROKE = 0.008f
private const val INNER_ARC_SPAN = 130f
private const val INNER_ARC_TURNS = 1.6f
