package com.naaammme.bbspace.feature.video.player

import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.model.toUgcTarget
import java.util.IdentityHashMap

/**
 * 页面持有播放器 / 会话的引用计数（上游 #892 `40690bd19` 移植，B 级适配）。
 *
 * ## 上游形态
 * BiliPai 的每个视频详情页各自创建一个 `ExoPlayer`（`VideoPlayerState`），页面之间
 * 共享的是 `MiniPlayerManager` 里的**外部播放器引用**。`40690bd19` 修的是：
 * 相关推荐跳到子视频时，子页面的 `setVideoInfo` / `syncCurrentVideoInfo` 会
 * **无条件释放/改写**父页面还在用的那台播放器 —— 返回父页时父页的播放器已被释放
 * （原视频定帧），或媒体会话还停在子视频上（退回首页串播推荐视频）。
 * 修法两件：
 * 1. `PlaybackPageOwnership`：按实例（`IdentityHashMap`，不是 equals）给播放器记
 *    页面引用数 —— **页面还持有它时，任何页面都不许释放它**；
 * 2. `shouldReclaimVideoPlaybackSession`：可见页面发现"会话不是我这台
 *    播放器 / 不是我这支视频"时，重新认领会话；被盖住的页面（isVisible=false）
 *    不许抢。
 *
 * ## 本仓差异（为什么是 B 级、不是逐行照搬）
 * | 上游 | 本仓 |
 * |---|---|
 * | 每页一个 `ExoPlayer`，页面 DisposableEffect 里 retain/release | **单一** `Media3PlayerEngine`（`infra/player`，本仓写域外），`StreamPlaybackSession` 进程级单例持有；页面**没有任何** `player.release()`（已 grep 核实：`feature/video` 与 `app/playback` 零处页面级释放） |
 * | `MiniPlayerManager` 在小窗/详情间转移外部播放器引用 | `PlayerEngineArbiter`（`core/playback`）按"占用者"仲裁，`stopForReuse` 也要过闸；`App` 层只有 `PlaybackHostViewModel` 转发同一个 session |
 * | 媒体会话由 `MiniPlayerManager` 推送通知元数据 | 元数据由 `StreamPlaybackSessionImpl.applyDetailResult` 写进引擎（会话单源） |
 * | 页面身份 = (页面播放器实例, bvid, cid) | 播放器实例跨视频**稳定**（`ensurePlayer` 只建一次），所以本仓的"不同播放器"分支恒为 false；真正会分叉的是**页面栈里的 target 与会话快照里的 ids** |
 *
 * ⇒ 结论：上游"释放顺序被子页面拆掉"这一根因在本仓结构上不存在，**释放顺序不需要改**；
 * 但"**页面身份与会话身份失配**"这个二阶不变量在本仓有一处真实入口 ——
 * `VideoViewModel` 的页内导航栈（`_targetStack`）与进程级会话是两套状态：
 * 无缝路径从胶囊/覆盖层展开时（`AppNavHost.openVideoRoute` 只做 `warmOpen`，
 * 不走 `videoViewModel.openRoot`），VM 的栈是**空的**，而会话正在播某支视频；
 * 空栈会让 `openTarget` 丢掉父页（相关推荐返回后页面身份消失）且
 * `openSwipeTarget` 直接 early-return（全屏上下滑没反应，读作"卡死/串播"）。
 * 本文件提供判据，`VideoViewModel.adoptPlaybackSessionPage()` 负责把会话身份
 * **认领**成页面身份。
 *
 * 判据本身与上游逐字一致（含相等性/可见性语义），类型放宽为 `Any?` 以便 JVM 单测
 * 直接构造实例；引用计数同样用 `IdentityHashMap`（`Any` 的 equals 语义会让
 * "同 bvid 的两个页面实例"被误判成同一个持有者）。
 */
internal class PlaybackPageOwnership {
    private val references = IdentityHashMap<Any, Int>()

    fun retain(player: Any) {
        references[player] = (references[player] ?: 0) + 1
    }

    fun release(player: Any) {
        val count = references[player] ?: return
        if (count == 1) {
            references.remove(player)
        } else {
            references[player] = count - 1
        }
    }

    fun isRetained(player: Any): Boolean = references.containsKey(player)
}

/**
 * 可见页面是否应重新认领会话（上游 `40690bd19` 逐字移植）。
 *
 * 判据拆开读：
 * - `!isVisible` → false：被盖住的页面（父页压在返回栈里）**不许**抢子页的会话，
 *   否则就是"父页在后台把子视频打断"；
 * - `!isSessionActive` → true：会话已经空了，可见页面必须重新接上；
 * - 播放器实例不同 / bvid 不同 / cid 不同 → true：会话不是这一页的（同视频换集
 *   也算，因为媒体身份变了）。
 */
internal fun shouldReclaimVideoPlaybackSession(
    isVisible: Boolean,
    isSessionActive: Boolean,
    currentPlayer: Any?,
    currentBvid: String?,
    currentCid: Long,
    pagePlayer: Any,
    pageBvid: String,
    pageCid: Long,
): Boolean = isVisible && (
    !isSessionActive ||
        currentPlayer !== pagePlayer ||
        currentBvid != pageBvid ||
        currentCid != pageCid
    )

/**
 * 本仓适配：从会话快照还原"这一页此刻在播哪个 target"。
 *
 * 上游每页自带 player，最坏也能用页面自己的 `VideoPlayerState` 回答；本仓页面不持有
 * 播放器，唯一可用的会话快照是 [VideoPlaybackState]（ids + biz）。因此认领时必须先
 * 把 `ids` 还原成 `VideoTarget` —— 这正是上游"页面身份"三元组在本仓的对应物。
 *
 * 返回 null 表示"会话里还没有任何视频"（进程刚起 / 已 close），此时不该认领。
 */
internal fun playbackPageTargetOf(
    biz: PlayBiz,
    ids: ResolvedVideoIds,
): VideoTarget? {
    if (!ids.hasAny) return null
    val src = VideoTargetTool.default()
    return when (biz) {
        PlayBiz.UGC -> ids.toUgcTarget(src)
        PlayBiz.PGC -> VideoTarget.Pgc(
            aid = ids.aid,
            cid = ids.cid,
            epId = ids.epId,
            seasonId = ids.seasonId.takeIf { it > 0L },
            src = src
        )
        PlayBiz.PUGV -> VideoTarget.Pugv(
            aid = ids.aid,
            epId = ids.epId,
            seasonId = ids.seasonId.takeIf { it > 0L },
            src = src
        )
    }
}
