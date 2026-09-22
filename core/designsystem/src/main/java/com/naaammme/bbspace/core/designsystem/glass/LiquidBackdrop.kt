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
import com.naaammme.bbspace.core.designsystem.glass.liquid.lens
import com.naaammme.bbspace.core.designsystem.glass.liquid.vibrancy

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
 * 液态玻璃表面（LIQUID 档主路线，BiliPai 官方观感）：
 * miuix drawBackdrop = blur + SDF 边缘折射（lens）+ vibrancy + Fresnel 高光边
 * （BloomStroke）+ 容器染色。
 *
 * 参数：blur 4dp、折射 24/24dp、saturation 1.5、容器 alpha = 0.40×(浅 0.95/深 0.75)
 * 全部照搬 BiliPai BALANCED 锚点实测值。注意：24dp 大折射必须配合底色并入录制
 * （AppNavHost Crossfade 的 background）与滚动逐帧重录（glassFrameTick），否则
 * 黑色文字区会被折射拉成大片暗带（历史教训，三件套缺一不可）。
 *
 * [shape] 须为圆角族，透镜 SDF 依赖 CornerBasedShape 角半径。
 *
 * **为什么是 @Composable + remember**：本函数给 `drawBackdrop` 传了 4 个 lambda
 * （shape / effects / highlight / onDrawSurface）。miuix 的 `DrawBackdropElement.equals`
 * 对 lambda 做的是**引用比较**（`Intrinsics.areEqual`），普通函数每次调用都新建实例
 * → 必不相等 → Compose 走 `update(node)`，而该方法末尾**无条件
 * `node.invalidateDrawCache()`** —— 即每次重组都让 miuix 丢弃并重建模糊/sharp/crossfade
 * 的绘制缓存。
 *
 * 这在 LIQUID 档下会被放大：调用方（顶栏 / Dock）只要因为任何原因重组一次，
 * 玻璃缓存就整份作废重建。remember 住整条 modifier 链后，只有真正影响外观的
 * 入参（backdrop / shape / surfaceType / 明暗 / 强度）变化时才重建。
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
    val s = strength.coerceIn(0.5f, 2f)
    // BiliPai resolveBottomBarGlassMaterialContainerColor：浅色 0.95 档、深色 0.75 档
    val alphaScale = if (isDarkTheme) 0.75f else 0.95f
    val tint = containerColor.copy(alpha = (0.40f * alphaScale).coerceIn(0f, 1f))
    val glassChain = remember(backdrop, surfaceType, shape, tint, s, isDarkTheme) {
        Modifier
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    // BackdropEffectScope 本身是 Density：dp→px 在作用域内换算
                    blur((4f * s).dp.toPx(), (4f * s).dp.toPx())
                    vibrancy(1.5f)
                    // 统一液态：HEADER（直角）同样折射，底边滚动内容有透镜感
                    lens(
                        refractionHeight = (24f * s).dp.toPx(),
                        refractionAmount = (24f * s).dp.toPx(),
                        chromaticAberration = 0f
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
                }
            )
    }
    // 把 remember 住的玻璃链接到调用方传入的 modifier 之后（保留上游链）
    return this.then(glassChain)
}
