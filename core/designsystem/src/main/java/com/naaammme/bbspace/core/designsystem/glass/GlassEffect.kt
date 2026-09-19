package com.naaammme.bbspace.core.designsystem.glass

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/** 玻璃背景源（hazeSource）的进程内共享容器：悬浮层与页面内容经它建立采样关系。 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

private @Composable fun styleFor(
    config: GlassConfig,
    surfaceType: GlassSurfaceType
): HazeBlurStyle = when {
    // 染色底默认取 MaterialTheme.colorScheme.surface（HazeMaterials 内置），
    // 明暗 alpha 由 HazeMaterials 按 isSystemInDarkTheme 自带
    config.style == GlassStyle.RICH && surfaceType == GlassSurfaceType.HEADER -> HazeMaterials.thick()
    config.style == GlassStyle.RICH -> HazeMaterials.regular()
    surfaceType == GlassSurfaceType.HEADER -> HazeMaterials.thin()
    else -> HazeMaterials.ultraThin()
}

/**
 * 玻璃效果的**唯一收口**（方案 1.2 L1，对标 BiliPai UnifiedBlur 单点收口纪律）：
 * 全部玻璃表面必须经此 Modifier，禁止裸调 `hazeEffect`（CR 检查项）。
 *
 * 关门顺序（方案 4.2 防御链）：
 * 1. 用户开关关闭 / 非 iOS 风格 / state 为空 → 原样返回（调用方的 iOS 定稿半透明底即降级态）；
 * 2. API < 31 无 RenderEffect → 原样返回；
 * 3. 预算 maxBlurLevel == 0（帧守卫降级 / Reduced 档）→ 原样返回；
 * 4. 样式构造包 runCatching，异常时回退半透明（照抄 runGuarded 模式）。
 *
 * 预算常量与 inputScale 照搬 BiliPai 实测值（GlassBudget.kt）。滚动 / 转场期
 * 预算只停实时重录、不降等级（Pulsing / Popping 教训）；当前白名单表面均为
 * 常驻悬浮层，调用点暂不区分滚动态。
 */
@Composable
fun Modifier.glassEffect(
    state: HazeState?,
    surfaceType: GlassSurfaceType,
    shape: Shape? = null,
    enabled: Boolean = true
): Modifier {
    val config = LocalGlassConfig.current
    val guard = LocalGlassGuard.current
    if (!enabled || !config.enabled) return this
    if (LocalUiStyle.current != UiStyle.IOS) return this
    if (state == null) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this

    val budget = remember(surfaceType, guard.effectiveMotionTier, guard.forceLowBlurBudget) {
        resolveGlassBudget(
            surfaceType = surfaceType,
            motionTier = guard.effectiveMotionTier,
            isScrolling = false,
            isTransitionRunning = false,
            forceLowBudget = guard.forceLowBlurBudget
        )
    }
    if (budget.maxBlurLevel <= 0) return this

    // 注意：Compose 禁止在 runCatching/try 块内做 @Composable 调用；Haze 版本已
    // 锁定 2.0.0-alpha03（BiliPai 生产同款），LinkageError 风险由版本锁定承担。
    val style = styleFor(config, surfaceType)

    val edgeTreatment = remember(shape) {
        if (shape != null) BlurredEdgeTreatment(shape) else BlurredEdgeTreatment.Rectangle
    }
    val inputScaleFactor = remember(budget, surfaceType) {
        resolveGlassInputScale(budget, surfaceType)
    }

    return (if (shape != null) this.clip(shape) else this).hazeEffect(
        state = state
    ) {
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

/**
 * 玻璃是否实际生效：决定调用方容器底色是否切换为透明（让 Haze 染色接管）。
 * 关闭 / 降级时调用方保持 iOS 定稿的纯半透明底色，一行不浪费。
 */
@Composable
fun isGlassActive(state: HazeState?): Boolean {
    val config = LocalGlassConfig.current
    val guard = LocalGlassGuard.current
    val context = LocalContext.current
    return config.enabled &&
        LocalUiStyle.current == UiStyle.IOS &&
        state != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        guard.effectiveMotionTier != MotionTier.Reduced &&
        context is Activity
}
