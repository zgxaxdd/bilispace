package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import com.naaammme.bbspace.core.designsystem.glass.liquid.LiquidGlassTuningPolicy
import com.naaammme.bbspace.core.designsystem.glass.liquid.lens
import com.naaammme.bbspace.core.designsystem.glass.liquid.vibrancy
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 液态玻璃背板（BiliPai 真实现路线，v0.2.3-beta.40）：悬浮玻璃不采样 Haze，
 * 而是采样 miuix 的 [LayerBackdrop] —— 内容侧用 `Modifier.layerBackdrop` 录制，
 * 玻璃侧用 `Modifier.drawBackdrop` 做 blur + SDF 边缘折射 + vibrancy + 高光。
 * BiliPai 的底栏/顶栏液态玻璃正是此组合；Haze 只是其回退路径。
 *
 * 默认 null：宿主未录制背板时（如无内容区的预览），液态档自动回退 Haze 磨砂。
 */
val LocalLiquidBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 液态玻璃表面（LIQUID 档主路线）：miuix drawBackdrop = blur + SDF 边缘折射（lens）+
 * vibrancy + Fresnel 高光边 + 容器染色。[shape] 须为圆角族（透镜 SDF 依赖
 * CornerBasedShape 角半径）。参数由 [LiquidGlassTuningPolicy] 解析，默认值逐值等于
 * 改造前的 BALANCED 锚点；整条 modifier 链必须 remember（否则每次重组都让 miuix
 * `invalidateDrawCache()` —— 详见文件末尾的「两段历史说明」）。
 */
@Composable
fun Modifier.liquidGlassSurface(
    backdrop: LayerBackdrop,
    surfaceType: GlassSurfaceType,
    shape: Shape,
    containerColor: Color,
    isDarkTheme: Boolean,
    strength: Float = 1f
): Modifier {
    // W3 玻璃参数层：把「用户强度 × 档位进度 × 明暗 × 关动效 × 设备上限」解析成一组
    // 光学参数（纯逻辑见 LiquidGlassTuningPolicy）。默认入参（strength=1 / balanced /
    // 无 reduceMotion / cap=1）解析出的值与改造前**逐值相同** —— 由
    // `LiquidGlassTuningPolicyTest.defaultsMatchCurrentRendering` 断言。
    val reduceMotion = LocalAnimations.current.reduceMotion
    val tuning = remember(strength, isDarkTheme, reduceMotion) {
        LiquidGlassTuningPolicy.resolve(
            strength = strength,
            progress = LiquidGlassTuningPolicy.BalancedProgress,
            isDarkTheme = isDarkTheme,
            reduceMotion = reduceMotion,
            // 本轮**显式传 1**：设备分档（MotionTier 的 glassStrengthCap）已在策略里
            // 支持并有单测，但无真机复评前不接进来 —— 否则低端机观感会当场改变。
            glassStrengthCap = 1f
        )
    }
    // 染色 alpha 由参数层给出（含明暗系数 0.75/0.95），与改造前 0.40×系数 同式
    val tint = containerColor.copy(alpha = tuning.surfaceAlpha)
    val glassChain = remember(backdrop, surfaceType, shape, tint, tuning) {
        Modifier
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    // BackdropEffectScope 本身是 Density：dp→px 在作用域内换算
                    blur(tuning.blurRadiusDp.dp.toPx(), tuning.blurRadiusDp.dp.toPx())
                    vibrancy(tuning.saturation)
                    // 统一液态：HEADER（直角）同样折射，底边滚动内容有透镜感
                    lens(
                        refractionHeight = tuning.refractionHeightDp.dp.toPx(),
                        refractionAmount = tuning.refractionAmountDp.dp.toPx(),
                        chromaticAberration = tuning.chromaticAberration
                    )
                },
                highlight = {
                    // HEADER（直角边缘、贴屏幕顶）不描高光边：BloomStroke 沿整个形状
                    // 轮廓描边，会在状态栏顶与侧边形成怪异亮框；Dock/胶囊圆角壳才描。
                    if (surfaceType == GlassSurfaceType.HEADER) null
                    else if (isDarkTheme) Highlight.GlassStrokeBigDark
                    else Highlight.GlassStrokeBigLight
                },
                onDrawSurface = {
                    drawRect(tint)
                    // 可读性 scrim 只在磨砂端（progress > balanced）出现；默认 0 ⇒ 不绘制，
                    // 与改造前逐像素一致（多一次 `if` 判断，不产生绘制指令）。
                    if (tuning.scrimAlpha > 0f) {
                        drawRect(Color.Black.copy(alpha = tuning.scrimAlpha))
                    }
                }
            )
    }
    // 把 remember 住的玻璃链接到调用方传入的 modifier 之后（保留上游链）
    return this.then(glassChain)
}

// ── 「两段历史说明」（从函数 KDoc 挪到这里：见文件顶部的行号约定）──────────────
//
// ⚠️ 行号约定：`.glass/liquidGlassSurface` 里的 `.drawBackdrop(` 仍在第 67 行 ——
// 这是 `GlassMountRegistryTest` 登记的行号，本轮**禁改登记表**，所以新增说明一律
// 写在挂载点之后（函数尾/文件尾），不往挂载点上方加行。下一轮若调整本文件，
// 要么保持行号，要么同批更新登记表并补 W27 记录。
//
// 1) 参数（改造前的 BALANCED 锚点，现由 LiquidGlassTuningPolicy 输出）：
//    blur 4dp、折射 24/24dp、saturation 1.5、容器 alpha = 0.40×(浅 0.95/深 0.75)。
//    ⚠️ 24dp 大折射必须配合底色并入录制（AppNavHost Crossfade 的 background）与
//    滚动逐帧重录（glassFrameTick），否则黑色文字区会被折射拉成大片暗带
//    （历史教训，三件套缺一不可）。
//
// 2) 为什么整条链要 remember：本函数给 `drawBackdrop` 传了 4 个 lambda
//    （shape / effects / highlight / onDrawSurface）。miuix 的
//    `DrawBackdropElement.equals` 对 lambda 做的是**引用比较**，普通函数每次调用
//    都新建实例 → 必不相等 → Compose 走 `update(node)`，而该方法末尾**无条件**
//    `node.invalidateDrawCache()` —— 即每次重组都让 miuix 丢弃并重建模糊/sharp/
//    crossfade 的绘制缓存。LIQUID 档下会被放大：调用方（顶栏 / Dock）只要因为任何
//    原因重组一次，玻璃缓存就整份作废重建。remember 住整条链后，只有真正影响外观
//    的入参（backdrop / shape / surfaceType / 明暗 / 参数）变化时才重建。
