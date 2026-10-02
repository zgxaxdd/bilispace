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
class GlassSourceRefresher(
    /**
     * 是否还需要重录。**默认恒为 true**（主框架的 Dock/胶囊常驻可见，必须逐帧录）。
     *
     * 给首页这类"消费者是悬浮顶栏"的宿主用：顶栏完全滚出屏幕后，这一路玻璃源
     * 没有任何可见消费者，继续 tick 只是让每帧一次全屏离屏重录白跑。
     *
     * 契约：本 lambda 在滚动分发里被调用，因此**只做读取、不得有副作用**；
     * 读 snapshot state 是安全的（在组合期之外读不会登记观察、不会触发重组）。
     */
    private val tickGate: () -> Boolean = { true }
) {
    var frame by mutableIntStateOf(0)
        private set

    /**
     * 驱动重录 +1（滚动帧 / 胶囊滚动耦合连接器共用）。[tickGate] 关闭时直接跳过——
     * 不写 [frame] 就不产生绘制失效，那一帧的全屏重录根本不会发生。
     *
     * ## 关于"抽帧无效"这个结论：它是**误诊**，别再拿它当依据（2026-09-22 复核）
     *
     * 原结论：*"试过隔帧抽帧（stride=2）减负，实测无效……本页 jank 不是逐帧绘制成本
     * 超预算，而是固定约 11 帧的重帧（`Number Slow issue draw commands` 与 jank 帧数
     * 一一对应），属于首帧合成 / GC 一类的偶发停顿。"*
     *
     * 实测数据本身是可信的，保留在这里（release 首页信息流三轮）：
     *   未抽帧 jank 0.85 / 1.06 / 1.18%、p95 15 / 15 / 14 ms
     *   抽帧   jank 1.27 / 0.95 / 1.47%、p95 12 / 14 / 13 ms
     *
     * 但由它推出的结论站不住，两处独立缺陷：
     *
     * 1. **论据用反了。** `Number Slow issue draw commands` 统计的正是
     *    "**提交绘制命令**这一阶段超过帧预算"的帧数，而不是"合成/GC 停顿"。
     *    每滚动帧做两遍 1272×2800 全屏离屏重录，恰好就是往这个阶段里加活。
     *    把它读成"与逐帧绘制成本无关"，等于拿一个指向 A 的指标去证明非 A。
     * 2. **样本量根本不足以支撑"无效"。** 同一构建、不做任何改动的四轮里，
     *    jank 能从 1.70% 掉到 0.00%（见 `docs/performance-baseline.md` 与交接书
     *    §2 的方差说明）。两组各 3 轮、中位数 1.06% vs 1.27% 的差异完全落在
     *    这个噪声带内 —— 这不是"证明无效"，是"测不出来"。
     *
     * 因此当前立场是：**抽帧是否有效未知，待设备重测**（本轮无设备，不声称任何数字）。
     * 重测方案与预期信号写在交付报告的"待设备验证"一节。在结论重新确立之前，
     * 不要再以"逐帧绘制成本不是瓶颈"为由拒绝减少全屏重录 —— 本次 T1 正是在减少
     * 全屏重录的**次数**（而非降低单帧成本），与抽帧是两条不同的路径。
     */
    fun tick() {
        if (tickGate()) frame++
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
 *
 * [visible]（FL-06）：消费端可见性谓词 —— 已移出屏幕的玻璃面不再每滚动帧重采样
 * （源侧有 [GlassSourceRefresher.tickGate]，消费侧此前**没有**，是不对称缺口）。
 * 契约同 tickGate：**只做读取、不得有副作用**，在绘制期调用（读 snapshot state
 * 不登记观察、不触发重组）。谓词为 false 时不读 [GlassSourceRefresher.frame]，
 * 该节点不产生绘制失效、那一帧的重采样不会发生。
 *
 * 默认 `{ true }` 保持向后兼容（无可见性状态的调用方行为不变）。
 */
fun Modifier.glassFrameRefresh(
    refresher: GlassSourceRefresher?,
    visible: () -> Boolean = { true }
): Modifier {
    if (refresher == null) return this
    return drawWithContent {
        if (visible()) {
            refresher.frame
        }
        drawContent()
    }
}
