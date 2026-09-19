package com.naaammme.bbspace.core.designsystem.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb

/**
 * AGSL 边缘折射（移植自 BiliPai `LiquidGlassShader.kt` / `FullBarLiquidGlassModifier.kt`，
 * 方案 1.2 L4）。仅 API 33+（RuntimeShader / AGSL 门槛，见 1.5 分档表）；
 * 调用方无需自行判版本，modifier 内部门控。
 *
 * 对元素自身绘制内容做**边缘采样偏移**：底色由调用方绘制，着色器只折射边缘，
 * 避免双重染色。用于 Dock / 胶囊等玻璃表面的"液态"边缘质感。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.liquidGlassEdge(
    refractIntensity: Float = 0.2f,
    backgroundColor: Color = Color.Transparent
): Modifier = composed {
    val shader = remember { RuntimeShader(LIQUID_GLASS_BACKGROUND_SHADER) }
    val liquidGlassRenderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
    }
    this.graphicsLayer {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("refract_intensity", refractIntensity)
        val bgColor = backgroundColor.toArgb()
        val a = android.graphics.Color.alpha(bgColor) / 255f
        val r = android.graphics.Color.red(bgColor) / 255f * a
        val g = android.graphics.Color.green(bgColor) / 255f * a
        val b = android.graphics.Color.blue(bgColor) / 255f * a
        shader.setFloatUniform("background_color", r, g, b, a)
        renderEffect = liquidGlassRenderEffect
    }
}

private const val LIQUID_GLASS_BACKGROUND_SHADER = """
    uniform shader img;

    uniform float2 resolution;
    uniform float refract_intensity;
    uniform float4 background_color;  // Premultiplied

    half4 main(in float2 fragCoord) {
        half2 uv = fragCoord;
        half2 minUV = half2(0.0);
        half2 maxUV = half2(resolution.x - 1.0, resolution.y - 1.0);

        if (refract_intensity <= 0.001) {
            half4 s = img.eval(uv);
            return s * (1.0 - background_color.a) + background_color;
        }

        // 边缘波纹折射：距边缘越近偏移越大，中段叠加色散
        float2 fromEdge = min(uv, resolution - uv);
        float edgeDist = min(fromEdge.x, fromEdge.y);
        float edgeFactor = 1.0 - clamp(edgeDist / (resolution.y * 0.08), 0.0, 1.0);
        float2 dir = normalize(uv - resolution * 0.5 + float2(0.0001));
        float2 offset = dir * edgeFactor * (refract_intensity * 24.0);
        uv = clamp(uv + offset, minUV, maxUV);

        float aberration = refract_intensity * edgeFactor * 0.35;
        float2 aberrOffset = float2(aberration * 4.0, aberration * 1.5);
        half r = img.eval(clamp(uv + aberrOffset, minUV, maxUV)).r;
        half4 centerSample = img.eval(uv);
        half b = img.eval(clamp(uv - aberrOffset, minUV, maxUV)).b;
        half4 sampled = half4(r, centerSample.g, b, centerSample.a);

        return sampled * (1.0 - background_color.a) + background_color;
    }
"""
