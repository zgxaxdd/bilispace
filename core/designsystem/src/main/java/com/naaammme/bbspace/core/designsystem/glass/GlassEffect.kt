package com.naaammme.bbspace.core.designsystem.glass

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/** 玻璃背景源（hazeSource）的共享容器：悬浮层与页面内容经它建立采样关系。 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
private fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f || isSystemInDarkTheme()

/**
 * 手动构造 HazeBlurStyle（替代 HazeMaterials 预设）：让 [GlassBudget.backgroundAlphaMultiplier]
 * 真正消费——染色 alpha = 材质基础 alpha × 预算乘数（终版方案 3.2 修复 #2）。
 * 基础 alpha 取自 HazeMaterials 同名材质的实测常量（ultraThin .35/.55、thin .60/.65）。
 *
 * haze 2.0 起 `HazeBlurStyle` 由**不可变配置 + lambda 构造**（旧的可变属性构造器已移除），
 * 所以这里用 `HazeBlurStyle { ... }` 写法；Style 本身不可变、可跨 modifier 复用。
 */
private fun styleFor(
    config: GlassConfig,
    surfaceType: GlassSurfaceType,
    dark: Boolean,
    container: Color,
    budget: GlassBudget
): HazeBlurStyle {
    val isHeader = surfaceType == GlassSurfaceType.HEADER
    val isLiquid = config.material == GlassMaterial.LIQUID
    val (lightAlpha, darkAlpha, blurRadius) = when {
        isHeader -> Triple(0.60f, 0.65f, 24.dp)
        // LIQUID 清玻璃：低模糊 + 低染色，内容从玻璃后流过（BiliPai BALANCED 观感）
        isLiquid -> Triple(0.38f, 0.30f, 6.dp)
        else -> Triple(0.35f, 0.55f, 20.dp)
    }
    val base = if (dark) darkAlpha else lightAlpha
    val tintAlpha = (base * budget.backgroundAlphaMultiplier).coerceIn(0f, 1f)
    return HazeBlurStyle {
        backgroundColor(container)
        colorEffects(
            listOf(HazeColorEffect.tint(container.copy(alpha = tintAlpha)))
        )
        blurRadius(blurRadius)
    }
}

/**
 * 玻璃效果的**唯一收口**（终版方案 DoD #1）：全部玻璃表面必须经此 Modifier，
 * 禁止裸调 `hazeEffect`（CR 检查项）。
 *
 * 关门顺序：用户 OFF 档 / 非 iOS 风格 / state 为空 → 原样返回（调用方半透明底
 * 即关闭态）；预算 maxBlurLevel==0（帧守卫降级 / Reduced 档）→ 原样返回。
 *
 * LIQUID 档主路线（BiliPai v0.2.3-beta.40 官方观感）：悬浮玻璃采样 miuix 的
 * [LocalLiquidBackdrop] 背板（宿主 ChromeBackdropSource 录制 + 滚动逐帧 tick 重录，
 * 见 AppNavHost glassFrameTick），经 [liquidGlassSurface] 做 blur + SDF 边缘折射 +
 * vibrancy + 高光。宿主未录制背板或无形状（如 HEADER）时回退 Haze 路径。
 *
 * 历史教训（勿重蹈）：在 hazeEffect 外再包 RuntimeShader graphicsLayer 会把整个
 * 表面渲染成空；miuix 背板若不配滚动逐帧重录，衔接处会采样到上一帧内容。
 *
 * [isScrolling] / [isTransitionRunning] 由调用方传入（修复 #3）：预算只停实时
 * 重录、不降等级（Pulsing / Popping 教训）。
 */
@Composable
fun Modifier.glassEffect(
    state: HazeState?,
    surfaceType: GlassSurfaceType,
    shape: Shape? = null,
    enabled: Boolean = true,
    isScrolling: Boolean = false,
    isTransitionRunning: Boolean = false
): Modifier {
    val config = LocalGlassConfig.current
    val guard = LocalGlassGuard.current
    if (!enabled || config.material == GlassMaterial.OFF) return this
    if (LocalUiStyle.current != UiStyle.IOS) return this
    if (state == null) return this

    val dark = isDarkTheme()
    // AP-28 ②：用户把动效设为「关闭」（reduceMotion）时，玻璃按 Reduced 档预算走 ——
    // 与骨架呼吸（FL-01）/FL 书 E-02 同一口径："关闭动效" = 不启动常驻动画、不跑
    // 实时重录。折叠进同一个 budget 计算，避免第二套降级语义。
    val anim = LocalAnimations.current
    val effectiveTier = if (anim.reduceMotion) {
        minMotionTier(guard.effectiveMotionTier, MotionTier.Reduced)
    } else {
        guard.effectiveMotionTier
    }
    val budget = remember(
        surfaceType,
        effectiveTier,
        guard.forceLowBlurBudget,
        isScrolling,
        isTransitionRunning
    ) {
        resolveGlassBudget(
            surfaceType = surfaceType,
            motionTier = effectiveTier,
            isScrolling = isScrolling,
            isTransitionRunning = isTransitionRunning,
            forceLowBudget = guard.forceLowBlurBudget
        )
    }
    if (budget.maxBlurLevel <= 0) return this

    val container = MaterialTheme.colorScheme.surface

    // LIQUID 档主路线：miuix 背板（Dock / 胶囊悬浮层由宿主录制背板）
    if (config.material == GlassMaterial.LIQUID) {
        val liquidBackdrop = LocalLiquidBackdrop.current
        if (liquidBackdrop != null && shape != null) {
            return this.liquidGlassSurface(
                backdrop = liquidBackdrop,
                surfaceType = surfaceType,
                shape = shape,
                containerColor = container,
                isDarkTheme = dark,
                strength = config.strength
            )
        }
    }

    // FROSTED 档 & LIQUID 回退（HEADER / 无背板宿主）：Haze 路径
    val style = remember(config, surfaceType, dark, container, budget) {
        styleFor(config, surfaceType, dark, container, budget)
    }
    // FL-04：原 `inputScaleFactor = resolveGlassInputScale(budget, surfaceType)` 已删除
    //（死代码：haze 2.0 移除 HazeInputScale 后从未被读取，全仓仅声明处与定义处两个命中）。
    // 连带影响见 GlassBudget.kt 中 resolveGlassInputScale 的删除记录。
    // haze 2.0：采样策略由 HazePerformanceMode 表达（旧的 HazeInputScale 已移除）。
    // 滚动/转场期间降档以省算力；静止时用 Balanced（等价于原先的按比例降采样）。
    val performanceMode = remember(budget) {
        if (budget.allowRealtime) HazePerformanceMode.Balanced else HazePerformanceMode.Performance
    }

    val modifier = if (shape != null) this.clip(shape) else this
    val liquid = config.material == GlassMaterial.LIQUID
    // haze 2.0：`hazeEffect { blurEffect { ... } }` 的可变 DSL 已废弃，改为
    // 「输入 + 不可变 Style + 性能模式」三件套的 hazeBlur。
    // HazeInput.Sources(state) = 采样 hazeSource 捕获的内容（原 hazeEffect(state) 语义）。
    // 圆角由上面 clip(shape) 负责，不再需要 BlurredEdgeTreatment。
    val base = modifier.hazeBlur(
        input = HazeInput.Sources(state),
        style = style,
        performanceMode = performanceMode
    )
    // 液态档圆角壳追加 Fresnel 高光边（方向光近似：左上亮、右下淡）
    return if (liquid && shape != null) base.liquidRimStroke(shape, dark) else base
}

/**
 * 液态玻璃高光边：沿形状轮廓描一道左上→右下衰减的细边，模拟光线掠射的
 * Fresnel 高光。纯绘制（不采样背景），滚动/静止都无采样正确性问题。
 *
 * 用 `drawWithCache` 而不是 `drawWithContent`：brush（含 listOf(3 Color)）、
 * outline、Stroke 都只该在**尺寸/形状变化**时重建 —— 写在绘制块里等于每帧分配
 * 一份（与 HomeVideoPage 封面微光同一个坑，那边已改 drawWithCache）。
 */
private fun Modifier.liquidRimStroke(shape: Shape, isDarkTheme: Boolean): Modifier =
    drawWithCache {
        val stroke = 1.5.dp.toPx()
        val highlightAlpha = if (isDarkTheme) 0.35f else 0.55f
        val brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha),
                Color.White.copy(alpha = highlightAlpha * 0.35f),
                Color.White.copy(alpha = highlightAlpha * 0.12f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        val outline = shape.createOutline(size, layoutDirection, this)
        val strokeStyle = Stroke(width = stroke)
        onDrawWithContent {
            drawContent()
            when (outline) {
                is Outline.Rounded -> drawRoundRect(
                    brush = brush,
                    cornerRadius = outline.roundRect.topLeftCornerRadius,
                    style = strokeStyle
                )
                is Outline.Rectangle -> drawRect(brush = brush, style = strokeStyle)
                is Outline.Generic -> drawPath(outline.path, brush, style = strokeStyle)
            }
        }
    }

/** 玻璃是否实际生效：决定调用方容器底色是否切为透明（Haze 染色接管）。 */
@Composable
fun isGlassActive(state: HazeState?): Boolean {
    val config = LocalGlassConfig.current
    val guard = LocalGlassGuard.current
    val context = LocalContext.current
    return config.material != GlassMaterial.OFF &&
        LocalUiStyle.current == UiStyle.IOS &&
        state != null &&
        guard.effectiveMotionTier != MotionTier.Reduced &&
        context is Activity
}
