package com.naaammme.bbspace.infra.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * PlayerView ⇄ Player 绑定的唯一管理者（所有权模型）。
 *
 * 此前 UI 三处调用点自行 bind/unbind，引擎 release 时靠 runCatching 逐点兜底，
 * 「陈旧视图快照 × 已释放实例」的时序组合仍然漏网（小窗 × 崩溃）。现在改为单一
 * 所有权规则，从结构上消灭这一类崩溃：
 *
 * 1. 所有 `PlayerView.player` 的读写只经本对象；UI 与引擎不得直接触碰。
 * 2. 引擎释放 player 前必须先调 [onPlayerReleased]（置释放标记 + 摘除仍绑定的
 *    视图），之后才允许 `player.release()`。因此视图层永远只能看到存活实例或 null。
 * 3. 对已释放实例的一切绑定/切换请求都是 no-op（迟到的陈旧 StateFlow 快照、
 *    未及重组摘除的视图，全部安全），不再依赖异常吞噬维持正确性。
 * 4. 同一时刻一个 player 至多一个渲染目标；切换时旧视图先行安全退位。
 *
 * 唯一允许吞异常的位置是 [safeSetPlayer]：PlayerView.setPlayer 对已释放实例的
 * surface 清理路径不做无异常保证（库版本差异），作为最后防线保留。
 */
@UnstableApi
object PlayerViewTargetBinder {

    /** player → 当前渲染视图。 */
    private val targets = IdentityHashMap<Player, PlayerView>()

    /** 视图 → 绑定的 player（反向索引，保证 unbind(view) 恒定 O(1)）。 */
    private val owners = IdentityHashMap<PlayerView, Player>()

    /** 已进入释放流程的实例：之后的一切绑定请求一律 no-op。 */
    // WeakHashMap 而非 IdentityHashMap：语义仍是「按实例身份判等」（Player 未覆写
    // equals/hashCode，故等价于引用相等），但**不再强引用已释放的 player**。
    //
    // 为什么必须改：原实现只写不删，每个被 release 的 ExoPlayer 都被永久钉住。
    // 已释放实例仍持有 renderer 列表、AnalyticsCollector、audio sink、allocator 等
    // 整套对象图（数十~数百 KB）。`Media3PlayerEngine.updateConfig`（改缓冲参数 /
    // 解码模式）与 `recoverSession`（系统级解码错误重建）每次都会重建实例并释放旧的
    // —— 每次都在这里永久累积，是典型的无界泄漏。
    //
    // 用 Weak 是安全的：这个 map 的用途只是「识别迟到的陈旧绑定请求」。一个 player
    // 若已不可达，就不可能再有携带它的 bind 请求送达（调用方必须持有引用来传参），
    // 因此条目被 GC 回收不会放过任何真实场景。
    //
    // 同时加 synchronizedMap：本对象是进程级单例，而 `AppInitializer` 已在 IO 线程上
    // 跑播放准备；IdentityHashMap/WeakHashMap 在多线程 rehash 下会丢条目甚至死循环。
    // 当前 15 个调用点恰好都在主线程，属于「靠调用纪律维持」的约束，这里补上保证。
    private val released: MutableMap<Player, Unit> =
        Collections.synchronizedMap(WeakHashMap<Player, Unit>())

    /**
     * 把 [view] 绑定为 [player] 的渲染目标；player 为 null 等价于 [unbind]。
     * 幂等：重复绑定同一 (view, player) 无任何副作用（AndroidView.update 每次
     * 重组都会调用，不得反复触碰 setPlayer）。
     */
    fun bind(view: PlayerView, player: Player?) {
        if (player == null) {
            unbind(view)
            return
        }
        if (released.containsKey(player)) {
            // 迟到的陈旧绑定请求。只处理「该视图当前确实绑着这个已释放实例」的情况。
            //
            // 关键：不能无条件 detachView(view)。视图可能已经被另一个存活实例重新绑定了 ——
            // 典型时序：本实例在 Media3PlayerEngine.updateConfig 里先发布新实例、再把本实例
            // 记入 released（见 updateConfig 的顺序），而另一处（如应用内小窗）迟到的
            // AndroidView.update 仍带着本实例进来。此时无条件摘除会把「已绑好新实例」的视图
            // 打回未绑定，且组合态已稳定、不会再有 update 重跑，表现为永久黑屏（视频渲染不出来）。
            // 只有视图当前确实持有本实例时才摘除；否则一律不碰：
            // - view.player 已是另一个存活实例 => 视图已被重新绑定，摘了就是永久黑屏；
            // - view.player 为 null        => 视图未被绑定，无需处理。
            if (owners[view] === player || view.player === player) {
                detachView(view)
            }
            return
        }
        if (
            owners[view] === player &&
            targets[player] === view &&
            view.player === player
        ) {
            return // 已绑定，幂等返回
        }
        // 1) 视图与旧 player 解除登记（含视图里残留的已释放引用）
        detachView(view)
        // 2) 该 player 的旧渲染视图退位（一个 player 同时刻只有一个目标）
        targets.remove(player)?.let(::detachView)
        // 3) 绑定；只有 setPlayer 确认成功才登记，避免出现「登记了但没绑上」的幽灵状态
        safeSetPlayer(view, player)
        if (view.player === player) {
            targets[player] = view
            owners[view] = player
        }
    }

    /** 摘除 [view] 的绑定（视图离开组合 / ON_STOP 时调用）。幂等。 */
    fun unbind(view: PlayerView) {
        detachView(view)
    }

    /**
     * player 进入释放流程：置释放标记并摘除全部仍绑定的视图。
     * 引擎必须在 `player.release()` 之前调用；此后视图层的任何 bind 请求都是 no-op。
     */
    fun onPlayerReleased(player: Player) {
        released[player] = Unit
        targets.remove(player)?.let(::detachView)
        // 兜底：owner 反向索引里仍指向该 player 的视图一并摘除
        val stale = owners.entries.filter { it.value === player }.map { it.key }
        stale.forEach(::detachView)
    }

    private fun detachView(view: PlayerView) {
        val owner = owners.remove(view)
        if (owner != null && targets[owner] === view) {
            targets.remove(owner)
        }
        safeSetPlayer(view, null)
    }

    private fun safeSetPlayer(view: PlayerView, player: Player?) {
        runCatching { view.player = player }
    }

    /**
     * **仅供诊断**：当前 [released] 中滞留的已释放 player 数量。
     *
     * 该 map 已改为 `WeakHashMap`（见字段注释），条目会随 player 不可达而自动回收，
     * 因此这个计数现在是「仍被外部强引用的已释放实例数」，不再是无界增长量。
     * 本方法只读、无副作用，供 debug 构建观察；不要在业务逻辑里使用。
     */
    @UnstableApi
    fun releasedCountForDiagnostics(): Int = released.size
}
