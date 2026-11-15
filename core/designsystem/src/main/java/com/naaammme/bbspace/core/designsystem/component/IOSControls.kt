package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSwitch

/**
 * iOS 风格开关（方案 2.3）。
 *
 * ## 2026-10-18（W2 批次 2）：改由 **Miuix 渲染层**绘制
 * 保留原签名与禁用语义（`onCheckedChange == null` ⇒ 禁用），内部委托 [AppMiuixSwitch]。
 * 收益是"换实现不换调用点"：`SettingItem` 一处接入，设置页所有开关立刻统一到 miuix 的
 * 轨道形变 + 滑块弹簧（库内动画，不占本仓 `animate*AsState` 棘轮，也不新增 dp 字面量）。
 *
 * 旧实现（自绘 51×31dp 轨道 + 27dp 白滑块 + Animatable 位移 + 1dp 阴影）已删除：
 * 它当年是为了绕开 M3 Switch 的观感差异；现在 miuix 本身就是 iOS 语汇，再自绘一层
 * 只会让两套语言并存。**不要为了"保险"把旧实现留成死代码。**
 */
@Composable
fun IOSSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    AppMiuixSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
    )
}

/**
 * iOS 返回 / 列表行尾 chevron（">"）。细线圆头，默认取 outline
 * （iOS tertiaryLabel 语义色）。返回箭头比 M3 ArrowBack 更细更轻。
 */
@Composable
fun IOSChevron(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
    pointingRight: Boolean = true
) {
    Canvas(
        modifier.size(width = 9.dp, height = 16.dp).drawWithCache {
            // AP-29 ③：原来 `Stroke(...)` 在绘制 lambda 里 —— 每帧一次分配
            //（同文件注释自称"每帧一次分配"却仍在新建）。改 drawWithCache 后只在
            // 密度（dp→px）变化时重建；drawLine 本身仍无分配。
            val strokeWidth = 2.dp.toPx()
            val w = size.width
            val h = size.height
            val midX = if (pointingRight) w * 0.42f else w * 0.58f
            val tipX = if (pointingRight) w * 0.88f else w * 0.12f
            onDrawBehind {
                // 两段线代替 Path：chevron 就是两条直线，drawPath 需要先 new 一个 Path
                // （绘制 lambda 每帧执行一次 → 每帧一次分配）。drawLine 无分配。
                drawLine(
                    color = color,
                    start = Offset(midX, h * 0.12f),
                    end = Offset(tipX, h * 0.5f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(tipX, h * 0.5f),
                    end = Offset(midX, h * 0.88f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        },
        // 绘制全部发生在 drawWithCache 的 onDrawBehind 里，Canvas 自身的 onDraw 留空
        onDraw = {}
    )
}
