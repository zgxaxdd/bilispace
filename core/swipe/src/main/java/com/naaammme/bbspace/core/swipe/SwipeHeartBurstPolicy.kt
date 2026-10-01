package com.naaammme.bbspace.core.swipe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 双击点赞的**爱心粒子**纯策略（任务书 SW-30）。
 *
 * 现状是一颗爱心放大上浮淡出；这里补"粒子版"：主心之外再散出若干小心形，
 * 位置/角度/尺度/延迟**全部由种子（token）决定** —— 同一次点赞永远同一组粒子
 * （可复现、可单测），不同次点赞各不相同（观感不重复）。
 *
 * 动效纪律：
 * - 只给数值，渲染一律在 `graphicsLayer`（绘制阶段）；
 * - `reduceMotion` 不是这里的参数：调用方在降级时**不取粒子**、主心也不缩放上浮
 *   （见 pane 的接线），策略只需保证数值本身有界；
 * - 粒子总数与半径有上限（不随屏幕尺寸无限放大）。
 */
object SwipeHeartBurstPolicy {

    /** 一次爆开的粒子数（抖音同款：少量、克制；太多会被读作"卡"）。 */
    const val PARTICLE_COUNT = 6

    /** 粒子最远位移（屏幕短边比例）：0.18 ≈ 1/5 屏，仍在手指位置附近。 */
    const val MAX_DISTANCE_FRACTION = 0.18f

    /** 单颗粒子的确定性参数。 */
    data class Particle(
        /** 散开角度（度，0 = 右，逆时针）。 */
        val angleDeg: Float,
        /** 最大位移（屏幕短边比例，0..[MAX_DISTANCE_FRACTION]）。 */
        val distanceFraction: Float,
        /** 尺度（1 = 基准小尺寸）。 */
        val scale: Float,
        /** 起跑延迟（进度比例，0..0.35：错落感来自这里）。 */
        val delayFraction: Float,
    )

    /**
     * 由种子生成一组粒子（确定性）。`count <= 0` 返回空表。
     * 角度在整圈内均匀铺开 + 抖动（避免"六芒星"式的机械均匀感）。
     */
    fun particles(seed: Long, count: Int = PARTICLE_COUNT): List<Particle> {
        if (count <= 0) return emptyList()
        val random = Random(seed)
        val step = 360f / count
        return List(count) { index ->
            val base = index * step
            Particle(
                angleDeg = base + random.nextFloat() * step * 0.7f,
                distanceFraction = (0.55f + random.nextFloat() * 0.45f) * MAX_DISTANCE_FRACTION,
                scale = 0.6f + random.nextFloat() * 0.5f,
                delayFraction = random.nextFloat() * 0.35f
            )
        }
    }

    /**
     * 粒子位移（屏幕短边比例 → 调用方乘 `min(width, height)`）：
     * 延迟期内不动，之后按 (progress - delay) / (1 - delay) 线性外扩。
     */
    fun particleOffset(particle: Particle, progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        if (p <= particle.delayFraction) return 0f to 0f
        val span = (1f - particle.delayFraction).coerceAtLeast(1e-3f)
        val local = ((p - particle.delayFraction) / span).coerceIn(0f, 1f)
        val distance = particle.distanceFraction * local
        val radians = Math.toRadians(particle.angleDeg.toDouble())
        return (cos(radians) * distance).toFloat() to (-sin(radians) * distance).toFloat()
    }

    /** 粒子透明度：前 15% 快速出现，后 40% 淡出。 */
    fun particleAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < 0.15f -> p / 0.15f
            p > 0.60f -> ((1f - p) / 0.40f).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /**
     * 主心缩放：0 → 1 的前 35% 放大到峰值 [PEAK_SCALE]，之后回落到 1。
     * 用两条线性段表达"弹一下"（真正的缓动交给调用方的 Animatable 插值器）。
     */
    const val PEAK_SCALE = 1.25f

    fun mainHeartScale(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val peakAt = 0.35f
        return if (p <= peakAt) {
            // 从 0.4 冲到峰值（起手别从 0 开始，会显得"凭空冒出来"）
            val local = p / peakAt
            0.4f + (PEAK_SCALE - 0.4f) * local
        } else {
            val local = (p - peakAt) / (1f - peakAt)
            PEAK_SCALE - (PEAK_SCALE - 1f) * local
        }
    }

    /** 主心透明度：最后 25% 淡出。 */
    fun mainHeartAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (p <= 0.75f) 1f else ((1f - p) / 0.25f).coerceIn(0f, 1f)
    }

    /** 整个动效是否已跑完（调用方据此卸载节点）。 */
    fun isDone(progress: Float): Boolean = progress >= 1f
}
