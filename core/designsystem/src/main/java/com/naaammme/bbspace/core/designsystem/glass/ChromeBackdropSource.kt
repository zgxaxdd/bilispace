package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.CompletableDeferred
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 液态玻璃背板源 + 其录制 Modifier；[modifier] 恰好挂在一个内容容器上。
 * 自 BiliPai ChromeBackdropSource（v0.2.3-beta.40）原样移植，仅改包名与可见性。
 *
 * 为什么不能直接用裸的 `Modifier.layerBackdrop`：miuix 的录制节点只在**自身**
 * 重绘时重新录制，而 Compose 绘制失效是作用域化的——信息流列表滚动只重绘列表
 * 节点，挂录制器的父容器不重绘，背板层因此停留在旧帧（玻璃里出现历史滚动位置
 * 的内容残影，PLC110 实测）。BiliPai 的解法：在内层先用 `drawWithContent` 把内容
 * 录进独立的 contentLayer，背板层只持有这一个"活引用"——子内容失效时 contentLayer
 * 重录，背板与玻璃自动跟随，任何时刻采样到的都是最新内容。
 *
 * 这不是位图快照或帧缓存：子内容失效仍会录进新内容。replay 层与背板层分离，
 * 避免录制自身；replay 层不加 RenderEffect / alpha / 裁剪，不触发额外离屏光栅。
 * 坐标与背板生命周期仍由 miuix 负责。
 */
@Stable
class ChromeBackdropSource(
    val backdrop: LayerBackdrop,
    val modifier: Modifier,
    private val recorded: State<Boolean>,
) {
    val isReady: Boolean get() = recorded.value
}

@Composable
fun rememberChromeBackdropSource(): ChromeBackdropSource {
    val contentLayer = rememberGraphicsLayer()
    val recorded = remember(contentLayer) { mutableStateOf(false) }
    val firstRecording = remember(contentLayer) { CompletableDeferred<Unit>() }
    LaunchedEffect(firstRecording) {
        firstRecording.await()
        recorded.value = true
    }
    val backdrop = rememberLayerBackdrop(onDraw = {
        drawLayer(contentLayer)
        // 发布时机 = 源真正录过第一帧之后，绝不用延时猜
        firstRecording.complete(Unit)
    })
    return remember(backdrop, contentLayer) {
        ChromeBackdropSource(
            backdrop = backdrop,
            recorded = recorded,
            modifier = Modifier
                .layerBackdrop(backdrop)
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                },
        )
    }
}
