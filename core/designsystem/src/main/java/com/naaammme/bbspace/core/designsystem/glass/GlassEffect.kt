package com.naaammme.bbspace.core.designsystem.glass

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
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
 */
private fun styleFor(
    config: GlassConfig,
    surfaceType: GlassSurfaceType,
    dark: Boolean,
    container: Color,
    budget: GlassBudget
): HazeBlurStyle {
    val isHeader = surfaceType == GlassSurfaceType.HEADER
    val (lightAlpha, darkAlpha, blurRadius) = when {
        config.material == GlassMaterial.LIQUID -> Triple(0.60f, 0.65f, 24.dp)
        isHeader -> Triple(0.60f, 0.65f, 24.dp)
        else -> Triple(0.35f, 0.55f, 20.dp)
    }
    val base = if (dark) darkAlpha else lightAlpha
    val tintAlpha = (base * budget.backgroundAlphaMultiplier).coerceIn(0f, 1f)
    return HazeBlurStyle(
        backgroundColor = container,
        colorEffects = listOf(
            HazeColorEffect.tint(container.copy(alpha = tintAlpha))
        ),
        blurRadius = blurRadius
    )
}

/**
 * 玻璃效果的**唯一收口**（终版方案 DoD #1）：全部玻璃表面必须经此 Modifier，
 * 禁止裸调 `hazeEffect`（CR 检查项）。
 *
 * 关门顺序：用户 OFF 档 / 非 iOS 风格 / state 为空 → 原样返回（调用方半透明底
 * 即关闭态）；预算 maxBlurLevel==0（帧守卫降级 / Reduced 档）→ 原样返回。
 *
 * LIQUID 档在 hazeEffect **之外**叠加 [liquidGlassSurfaceV2]——graphicsLayer 的
 * RenderEffect 采样的是"模糊背景 + 染色 + 内容"的合成层，折射/高光作用于完整
 * 玻璃观感（终版方案 3.2 修复 #1：liquidGlassEdge 零调用问题）。
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
    val budget = remember(
        surfaceType,
        guard.effectiveMotionTier,
        guard.forceLowBlurBudget,
        isScrolling,
        isTransitionRunning
    ) {
        resolveGlassBudget(
            surfaceType = surfaceType,
            motionTier = guard.effectiveMotionTier,
            isScrolling = isScrolling,
            isTransitionRunning = isTransitionRunning,
            forceLowBudget = guard.forceLowBlurBudget
        )
    }
    if (budget.maxBlurLevel <= 0) return this

    val container = MaterialTheme.colorScheme.surface
    val style = remember(config, surfaceType, dark, container, budget) {
        styleFor(config, surfaceType, dark, container, budget)
    }
    val edgeTreatment = remember(shape) {
        if (shape != null) BlurredEdgeTreatment(shape) else BlurredEdgeTreatment.Rectangle
    }
    val inputScaleFactor = remember(budget, surfaceType) {
        resolveGlassInputScale(budget, surfaceType)
    }

    var modifier = if (shape != null) this.clip(shape) else this
    if (config.material == GlassMaterial.LIQUID) {
        modifier = modifier.liquidGlassSurfaceV2()
    }
    return modifier.hazeEffect(state = state) {
        blurEffect {
            this.style = style
            this.blurEnabled = true
            this.blurredEdgeTreatment = edgeTreatment
        }
        @OptIn(ExperimentalHazeApi::class)
        run {
            inputScale = if (inputScaleFactor >= 1f) {
                HazeInputScale.None
            } else {
                HazeInputScale.Fixed(inputScaleFactor)
            }
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
