package com.naaammme.bbspace.core.playback

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 共用同一个进程级 `PlayerEngine` 的会话身份。
 *
 * 本仓只有一台播放器：`PlayerEngine` 是 `@Singleton`，流会话（VOD/直播/小窗）与
 * 离线播放控制器都往同一台机器上写。所以"谁在用"是个真实存在的状态，不是抽象。
 */
enum class EngineHolder {
    NONE,

    /** 流会话（`StreamPlaybackSessionImpl`）。 */
    STREAM,

    /** 离线缓存播放（`DownloadPlaybackControllerImpl`）。 */
    DOWNLOAD
}

/**
 * 引擎所有权仲裁：只有当前占用者能释放或停用引擎（BACKLOG ST-07）。
 *
 * ## 病灶
 * 原先 `DownloadPlaybackControllerImpl.release()` 无条件 `playerEngine.release()`，
 * 流会话的 `finishVideoPlayback` / `finishLivePlayback` 也无条件 release/stopForReuse。
 * 于是一侧页面收尾会把**另一侧正在播的** ExoPlayer 整个释放掉 ——
 * 例如正在放缓存视频时直播页收尾，或被小窗接管后缓存页关页。
 * `release()` 之后播放器实例没了，只能重新 setSource，用户表现为声音/画面凭空中断。
 *
 * ## 为什么"抢占式 claim"而不是"引用计数"
 * 一台播放器不可能同时服务两个会话：谁最后写 `setSource`，媒体就是谁的，这是既成事实。
 * 引用计数会假装两者可以并存，反而掩盖了"后写者已经抢走了媒体"这个真实语义。
 * 所以规则是：**写引擎前先 claim；只有 holder 能 release（释放即交还）或 stopForReuse（保留占用）**。
 *
 * ## claim 的时机刻意放在 setSource **之前**
 * 若放在成功后，setSource 抛异常时就没人是 holder，随后那一侧的 release 会被仲裁挡掉，
 * 引擎里的半成品媒体就再也清不掉了。先 claim 保证"谁写的谁负责收尾"，包括失败收尾。
 *
 * ## 并发
 * claim 可能发生在非主线程（`setSource` 是挂起函数，恢复线程取决于实现），
 * 所以状态用 `AtomicReference`：`release` 走 compareAndSet，
 * 两个会话同时收尾也只会有一次真正释放。
 */
@Singleton
class PlayerEngineArbiter @Inject constructor() {

    private val ref = AtomicReference(EngineHolder.NONE)

    /** 当前占用者（诊断与守卫用）。 */
    val holder: EngineHolder get() = ref.get()

    /** 我即将写引擎 / 正在用引擎。 */
    fun claim(candidate: EngineHolder) {
        ref.set(candidate)
    }

    /** 是否仍由 [candidate] 占用（决定是否允许 `stopForReuse`）。 */
    fun isHolder(candidate: EngineHolder): Boolean = ref.get() == candidate

    /**
     * 由 [candidate] 发起的收尾：仅当它确实是占用者时才交还引擎。
     *
     * @return true 表示应当调用 `playerEngine.release()`；false 表示引擎已归别人，不许碰。
     */
    fun release(candidate: EngineHolder): Boolean = ref.compareAndSet(candidate, EngineHolder.NONE)
}
