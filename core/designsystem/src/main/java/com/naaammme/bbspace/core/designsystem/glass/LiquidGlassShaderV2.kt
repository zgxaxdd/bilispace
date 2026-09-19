package com.naaammme.bbspace.core.designsystem.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 液态玻璃着色器 V2（终版方案 3.3，四层分解对齐 Apple Liquid Glass）：
 * 1. lensing —— 边缘折射，曲率可控的单调衰减 `pow(1-t, 1+curvature*2)`，
 *    色散沿 SDF 法线（边缘垂直方向）扩散；
 * 2. tint —— 由 Haze 的 HazeColorEffect 承担，着色器只透传（背景色透明入参）；
 * 3. highlights —— Fresnel 高光边 `pow(edgeFactor,3)*rim`，按来光方向加权；
 * 4. shadows/glow —— 边缘外发光；另有饱和度补偿找回染色与模糊夺走的色彩。
 *
 * 内容永远在最上层语义：折射只作用于边缘带（约 6% 高度），中心内容无损。
 * 旧实现保留在 LiquidEdgeShader.kt 便于一键回退。
 */
fun Modifier.liquidGlassSurfaceV2(
    refractIntensity: Float = 0.18f,
    rimIntensity: Float = 0.28f,
    glowIntensity: Float = 0.16f,
    saturate: Float = 1.18f
): Modifier = composed {
    val shader = remember { RuntimeShader(LIQUID_GLASS_SHADER_V2) }
    val liquidRenderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
    }
    this.graphicsLayer {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("refract_intensity", refractIntensity)
        shader.setFloatUniform("rim_intensity", rimIntensity)
        shader.setFloatUniform("glow_intensity", glowIntensity)
        shader.setFloatUniform("saturate", saturate)
        renderEffect = liquidRenderEffect
    }
}

private const val LIQUID_GLASS_SHADER_V2 = """
    uniform shader img;

    uniform float2 resolution;
    uniform float refract_intensity;
    uniform float rim_intensity;
    uniform float glow_intensity;
    uniform float saturate;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord;
        float2 size = resolution;

        // 到最近边缘的距离与法线（矩形 SDF 梯度近似）
        float2 d = min(uv, size - uv);
        float edgeDist = min(d.x, d.y);
        float band = max(size.y * 0.06, size.x * 0.04);
        float t = clamp(edgeDist / band, 0.0, 1.0);   // 0=边缘 1=内部

        // 1) lensing：曲率可控单调衰减；法线 = 边缘内向方向
        float curvature = 2.0;
        float bend = pow(1.0 - t, 1.0 + curvature * 2.0) * refract_intensity;
        float2 n = float2(0.0);
        if (d.x < d.y) {
            n.x = (uv.x < size.x * 0.5) ? 1.0 : -1.0;
        } else {
            n.y = (uv.y < size.y * 0.5) ? 1.0 : -1.0;
        }
        uv += n * bend * 18.0;
        uv = clamp(uv, float2(0.0), size - 1.0);

        half4 c = img.eval(uv);

        // 饱和度补偿：补回染色与模糊夺走的色彩
        float luma = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
        c.rgb = half3(mix(luma, c.rgb, saturate));

        // 3) Fresnel 高光边：pow(edgeFactor,3)，左上来光方向加权
        float rim = pow(1.0 - t, 3.0) * rim_intensity;
        float2 toCenter = normalize(uv - size * 0.5 + float2(0.001));
        float lightAngle = clamp(0.5 + 0.5 * dot(toCenter, normalize(float2(-0.6, -0.8))), 0.0, 1.0);
        c.rgb += half3(rim * (0.55 + 0.45 * lightAngle));

        // 4) 外发光：边缘极窄带内柔和提亮
        float glow = pow(1.0 - t, 8.0) * glow_intensity;
        c.rgb += half3(glow * 0.35);

        return c;
    }
"""
