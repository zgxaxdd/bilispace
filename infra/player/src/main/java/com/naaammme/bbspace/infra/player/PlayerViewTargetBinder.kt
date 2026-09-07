package com.naaammme.bbspace.infra.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.IdentityHashMap

@UnstableApi
object PlayerViewTargetBinder {

    private val boundViews = IdentityHashMap<Player, PlayerView>()

    fun bind(
        view: PlayerView,
        player: Player?
    ) {
        player ?: return unbind(view)
        removeViewEntry(view, keepPlayer = player)
        val oldView = boundViews[player]
        if (oldView === view) {
            if (view.player !== player) {
                view.player = player
            }
            return
        }
        PlayerView.switchTargetView(player, oldView, view)
        boundViews[player] = view
    }

    fun unbind(view: PlayerView) {
        val player = view.player
        if (player != null && boundViews[player] === view) {
            boundViews.remove(player)
            // 兜底：player 已被 release 时 switchTargetView 会抛
            // IllegalStateException("Player is released")，解绑语义下无需上抛
            runCatching { PlayerView.switchTargetView(player, view, null) }
            return
        }
        removeViewEntry(view, keepPlayer = null)
        view.player = null
    }

    /**
     * 释放 player 之前调用：把所有仍绑定它的视图安全摘除。
     * 小窗 × 关闭等「player 在视图仍绑定时被释放」的场景，若不先解绑，
     * 视图 dispose 时的延迟 unbind 会对已 release 的 player 调 switchTargetView 而崩溃。
     */
    fun unbindAll(player: Player) {
        val views = boundViews.entries.filter { it.key === player }.map { it.value }
        boundViews.entries.removeAll { it.key === player }
        views.forEach { view ->
            runCatching { PlayerView.switchTargetView(player, view, null) }
            if (view.player === player) {
                view.player = null
            }
        }
    }

    private fun removeViewEntry(
        view: PlayerView,
        keepPlayer: Player?
    ) {
        boundViews.entries.removeAll { it.value === view && it.key !== keepPlayer }
    }
}
