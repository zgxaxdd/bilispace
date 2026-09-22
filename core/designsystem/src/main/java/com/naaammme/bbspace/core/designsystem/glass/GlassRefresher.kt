package com.naaammme.bbspace.core.designsystem.glass

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * 玻璃源/玻璃消费端的重录触发器（PLC110 实测两处坑的统一修复）：
 *
 * 1. **源侧**：hazeSource / miuix layerBackdrop 的录制节点只在自身重绘时重录，
 *    信息流列表滚动不令其重绘 → 背板停在旧帧。把 [nestedScrollConnection] 挂到
 *    宿主容器，滚动（拖拽+惯性）逐帧计 [frame]；宿主在源节点外层
 *    `drawWithContent { refresher.frame; drawContent() }` 读它，滚动帧强制重录。
 *
 * 2. **消费端**：玻璃节点（Dock/顶栏/胶囊）被移出屏幕的期间（如 Dock 滚动隐藏）
 *    停止重采样，滑回来时复用隐藏前的旧模糊光栅。消费端链上挂
 *    [glassFrameRefresh] 读同一 tick，每滚动帧强制重采样。
 *
 * 消费端经 [LocalGlassRefresher] 取宿主下发的实例；无宿主（预览）时为 null，
 * [glassFrameRefresh] 直接跳过。
 */
@Stable
class GlassSourceRefresher {
    var frame by mutableIntStateOf(0)
        private set

    /**
     * 驱动重录 +1（滚动帧 / 胶囊滚动耦合连接器共用）。
     *
     * **试过隔帧抽帧（stride=2）来给滚动减负，实测无效，已回退**：
     * release 包首页信息流三轮对比，未抽帧 jank 0.85/1.06/1.18%、p95 15/15/14ms；
     * 抽帧 jank 1.27/0.95/1.47%、p95 12/14/13ms——两者在噪声范围内，而抽帧还引入
     * "背板最多落后 1 帧"的观感风险。结论：本页 jank 不是**逐帧绘制成本**超预算，
     * 而是固定约 11 帧的重帧（`Number Slow issue draw commands` 与 jank 帧数一一对应），
     * 属于首帧合成 / GC 一类的偶发停顿，压每帧成本换不到收益。
     */
    fun tick() {
        frame++
    }

    val nestedScrollConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.y != 0f || available.y != 0f) tick()
                return Offset.Zero
            }
        }
}

/** 宿主下发的玻璃重录触发器（源侧 tick + 消费端重采样共用同一个实例）。 */
val LocalGlassRefresher = androidx.compose.runtime.staticCompositionLocalOf<GlassSourceRefresher?> { null }

/**
 * 玻璃消费端重采样观察：绘制期读 [GlassSourceRefresher.frame]，滚动帧触发本
 * 玻璃节点重绘（重新采样当帧背板）。必须挂在 glassEffect **外层**。
 */
fun Modifier.glassFrameRefresh(refresher: GlassSourceRefresher?): Modifier {
    if (refresher == null) return this
    return drawWithContent {
        refresher.frame
        drawContent()
    }
}
