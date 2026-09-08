package com.naaammme.bbspace.infra.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.IdentityHashMap

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
    private val released = IdentityHashMap<Player, Unit>()

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
            // 迟到的陈旧绑定请求：视图若仍持有旧引用则安全摘除，绝不再绑
            if (owners.containsKey(view) || view.player != null) {
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
}
