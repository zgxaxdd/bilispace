package com.naaammme.bbspace.core.swipe.session

import com.naaammme.bbspace.core.swipe.SwipeDislikeRemovalPolicy
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.SwipeQueueBoundPolicy
import com.naaammme.bbspace.core.swipe.SwipeQueueTail
import com.naaammme.bbspace.core.swipe.appendSwipeEntries
import com.naaammme.bbspace.core.swipe.resolveSwipeQueueTail
import com.naaammme.bbspace.core.swipe.model.SwipeId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 刷视频会话的**单一真值源**（任务书 §4.3 / SW-02）。
 *
 * 纯状态机：不知道网络、不知道播放器、不知道 Compose。谁拥有它（VideoViewModel）
 * 负责在恰当的时机喂事件；它只负责**收敛状态**并保证四条不变式：
 *
 * 1. `Bootstrapping` 期间锚点流保持 null、种子随身携带——UI 的加载层判据在迁移期
 *    不变（见 [SwipeSessionState] 类注释）；且种子非空，"Bootstrapping 无封面"在
 *    类型上不可能发生。
 * 2. `retry()` 后必回 `Ready`（网络重开由调用方做；这里只管状态）。
 * 3. `exit()` 清空**一切**会话内状态：锚点/队列/补货尾态/自动翻页幂等键/预取去重表
 *    ——后两者留在会话里跨会话泄漏是历史 bug（"播完跳两支""重复预取"）的根因。
 * 4. `restore()` 只灌状态、结构上不可能触发网络（没有回调、没有挂起点）——
 *    进程重建恢复"不重放网络请求"由签名保证。
 *
 * 队列合并（去重/UP 多样性/确定性洗牌）统一走 [appendSwipeEntries]：源内不各自去重
 * （任务书 §4.7 规则②的落点），VM 不再自己拼队列。
 */
class SwipeSessionController {

    private val _anchor = MutableStateFlow<SwipeEntry?>(null)

    /** 当前会话锚点（页 0）；`Ready` 前为 null（迁移期 UI 判据，见 [SwipeSessionState]）。 */
    val anchor: StateFlow<SwipeEntry?> = _anchor.asStateFlow()

    private val _queue = MutableStateFlow<List<SwipeEntry>>(emptyList())

    /** 接力队列（页 1..n）；锚点不在其中（`pages = listOf(anchor) + queue` 是调用方的拼法）。 */
    val queue: StateFlow<List<SwipeEntry>> = _queue.asStateFlow()

    /**
     * **页序的唯一拼法**：`[锚点] + 接力队列`（D11）。
     *
     * ## 为什么必须收成一处
     * 重构前 pager 与 VM **各拼一份**：
     * - `PortraitSwipePager.kt`：`remember(anchor, queue) { listOf(anchor) + queue }`
     * - `VideoViewModel.prefetchSwipeQueue()`：`listOf(anchor) + swipeSession.queue.value`
     *
     * 两处写法一致时无事；但这是**两条独立的拼接**，任何一边改动（例如将来把锚点
     * 也放进 queue、或跳过某类条目）都会让"pager 的第 N 页"与"预取的第 N 页"
     * 指向不同视频 —— 而预取错了**没有任何症状**，只是那一页滑过去要多等一拍。
     *
     * 现在调用方一律走这里。索引语义只有一处定义，改这里就是改全部。
     *
     * @return 页序；锚点未发布（`Idle` / story 在途）时返回空列表 —— 与旧判据
     *   「anchor == null 时不动队列」一致。
     */
    fun pages(): List<SwipeEntry> {
        val anchor = _anchor.value ?: return emptyList()
        return listOf(anchor) + _queue.value
    }

    private val _tail = MutableStateFlow(SwipeQueueTail.Idle)

    /** 补货尾态（"正在为你找更多视频…" / "已经是最后一个了"的驱动）。 */
    val tail: StateFlow<SwipeQueueTail> = _tail.asStateFlow()

    private val _state = MutableStateFlow<SwipeSessionState>(SwipeSessionState.Idle)
    val state: StateFlow<SwipeSessionState> = _state.asStateFlow()

    private val _lastAutoAdvancedKey = MutableStateFlow<String?>(null)

    /**
     * 播完自动翻页的幂等键（"这一支已经因 `STATE_ENDED` 翻过一次"）。
     * 从 pager 的 `remember` 提升到这里（SW-02）：随 `exit()` 清空，
     * 进程重建后也从空开始——不存在"上一场会话的键挡住这一场翻页"的可能。
     */
    val lastAutoAdvancedKey: StateFlow<String?> = _lastAutoAdvancedKey.asStateFlow()

    /** 本次会话已预取过的 `aid#cid`：同一支只预取一次。并发集合：预取协程与翻页回调并发触达。 */
    private val prefetchedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var via: SwipeEntryVia = SwipeEntryVia.DetailPullDown

    /** 当前页号；`Ready`/粘滞态（Extending/Exhausted）都要带着它恢复与快照。 */
    private var page: Int = 0

    /** 详情全屏路径（简介区下滑）：种子队列同步就绪，直接 `Ready`。 */
    fun enterReady(seed: SwipeEntry, via: SwipeEntryVia, seedQueue: List<SwipeEntry>) {
        clearSession(state = SwipeSessionState.Ready(page = 0, size = seedQueue.size), anchor = seed)
        this.via = via
        _queue.value = seedQueue
    }

    /** story 入口：流在途。锚点流保持 null（迁移期 UI 判据），种子进 `Bootstrapping`。 */
    fun enterBootstrapping(seed: SwipeEntry, via: SwipeEntryVia) {
        clearSession(state = SwipeSessionState.Bootstrapping(via = via, anchor = seed), anchor = null)
        this.via = via
    }

    /**
     * story 流到达（含失败——失败传空列表，降级为"单视频全屏"即 `Ready(0, 0)`，
     * 与旧 VM"锚点仍设为入口视频"的语义一致）。仅在 `Bootstrapping` 下生效：
     * 流优先级高于任何在途接力，所以队列**从空重建**（覆盖，而非追加）。
     */
    fun seedArrived(entries: List<SwipeEntry>) {
        val bootstrapping = _state.value as? SwipeSessionState.Bootstrapping ?: return
        val seed = bootstrapping.anchor
        // 锚点的展示信息（标题/封面/UP）优先取流内同视频条目，取不到留空：
        // 页 0 是播放器页，这些字段只有封面页/veil 消费（旧 VM bootstrap 的同款查找）。
        val info = entries.firstOrNull { it.isCurrentVideo(seed.bvid, seed.target.aid) }
        page = 0
        _anchor.value = seed.copy(
            title = info?.title.orEmpty(),
            cover = info?.cover.orEmpty(),
            author = info?.author,
            ownerMid = info?.ownerMid,
            durationSec = info?.durationSec,
            dimension = info?.dimension
        )
        _queue.value = appendSwipeEntries(
            existing = emptyList(),
            incoming = entries,
            anchorKey = seed.legacyKey()
        )
        _state.value = SwipeSessionState.Ready(page = 0, size = _queue.value.size)
    }

    /**
     * 新详情的 relates 接力（幂等：同批重复到达无害）。锚点未发布（`Idle` /
     * story 流在途）时 no-op——与旧 VM"anchor == null 时不动队列"的判据一致。
     */
    fun mergeRelates(entries: List<SwipeEntry>) {
        val anchor = _anchor.value ?: return
        // P1-8③：追加后**从尾部**截到上限。绝不截当前页及其之前——
        // pager 以页号为身份，头部截断会让用户正在看的那一页变成别的视频。
        _queue.value = SwipeQueueBoundPolicy.bound(
            entries = appendSwipeEntries(
                existing = _queue.value,
                incoming = entries,
                anchorKey = anchor.legacyKey()
            ),
            currentPage = page
        )
        val current = _state.value
        if (current is SwipeSessionState.Ready) {
            _state.value = current.copy(size = _queue.value.size)
        }
    }

    /** 翻页落定。粘滞态（Extending/Exhausted）不清除——补货尾态跨翻页保留是既有行为。 */
    fun pageChanged(newPage: Int) {
        if (newPage < 0) return
        page = newPage
        val current = _state.value
        if (current is SwipeSessionState.Ready) {
            _state.value = current.copy(page = newPage)
        }
    }

    fun refillStarted() {
        if (_anchor.value == null) return
        _tail.value = SwipeQueueTail.Extending
        _state.value = SwipeSessionState.Extending
    }

    /**
     * @param appended 本轮补货净增条数：> 0 回 `Ready`（尾态 Idle，不再打扰）；
     *   == 0 进粘滞 `Exhausted`（用户之后滑到最后一页时提示"没有更多了"）。
     */
    fun refillResult(appended: Int) {
        if (_anchor.value == null) return
        _tail.value = resolveSwipeQueueTail(appended = appended)
        _state.value = if (appended > 0) {
            SwipeSessionState.Ready(page = page, size = _queue.value.size)
        } else {
            SwipeSessionState.Exhausted
        }
    }

    /** 取流/解码失败（接线在 SW-13；状态先行收敛在此）。 */
    fun playbackFailed(id: SwipeId, retryable: Boolean) {
        if (_anchor.value == null) return
        _state.value = SwipeSessionState.PlaybackFailed(id = id, retryable = retryable)
    }

    /** 重试：状态必回 `Ready`（不变式 2）；重开播放是调用方的事（openVideo 自增 openId）。 */
    fun retry() {
        val current = _state.value
        if (current is SwipeSessionState.PlaybackFailed) {
            _state.value = SwipeSessionState.Ready(page = page, size = _queue.value.size)
        }
    }

    /** 记录"这一支已因播完自动翻过页"。 */
    fun noteAutoAdvanced(key: String) {
        _lastAutoAdvancedKey.value = key
    }

    /**
     * 「不感兴趣」退队（SW-24）：把条目从队列摘掉（锚点不可摘，见 [SwipeDislikeRemovalPolicy]）。
     * @return true = 真的摘掉了（调用方据此补货/提示）
     */
    fun removeQueueEntry(entry: SwipeEntry, currentPage: Int): Boolean {
        val anchor = _anchor.value
        val removal = SwipeDislikeRemovalPolicy.remove(
            queue = _queue.value,
            anchor = anchor,
            target = entry,
            currentPage = currentPage
        )
        if (!removal.removed) return false
        _queue.value = removal.queue
        // 当前页索引补偿（摘掉的是"已滑过的前方条目"时；摘当前页/后方条目都不补）
        if (removal.shiftCurrentPageDown) {
            pageChanged((currentPage - 1).coerceAtLeast(0))
        } else {
            val current = _state.value
            if (current is SwipeSessionState.Ready) {
                _state.value = current.copy(size = _queue.value.size)
            }
        }
        return true
    }

    /** 「撤回不感兴趣」把条目插回原位置（SW-24 要点①：与首页一致的可撤回）。 */
    fun restoreQueueEntry(index: Int, entry: SwipeEntry) {
        val anchor = _anchor.value ?: return
        if (entry.legacyKey() == anchor.legacyKey()) return
        val safeIndex = index.coerceIn(0, _queue.value.size)
        _queue.value = _queue.value.toMutableList().apply { add(safeIndex, entry) }
        val current = _state.value
        if (current is SwipeSessionState.Ready) {
            _state.value = current.copy(size = _queue.value.size)
        }
    }

    /** 队列里某条目的下标（不含锚点）；-1 = 不在队列。 */
    fun queueIndexOf(entry: SwipeEntry): Int =
        _queue.value.indexOfFirst { it.legacyKey() == entry.legacyKey() }

    /** @return true = 这支此前没预取过（调用方应继续执行预取）。 */
    fun markPrefetched(key: String): Boolean = prefetchedKeys.add(key)

    /**
     * 进程重建恢复（不变式 4）：只灌状态，零网络。页号 + 锚点 + 队列由快照还原，
     * 直接落 `Ready`——起步判据（锚点非空 + 队列非空）自然成立，不会重放
     * fetchFeed / story / relates 中的任何一条。
     */
    fun restore(page: Int, anchor: SwipeEntry, queue: List<SwipeEntry>) {
        val safePage = page.coerceAtLeast(0)
        clearSession(
            state = SwipeSessionState.Ready(page = safePage, size = queue.size),
            anchor = anchor
        )
        this.page = safePage
        _queue.value = queue
    }

    /** 退出会话（不变式 3）：清空一切，下次进入重新锚定。 */
    fun exit() {
        clearSession(state = SwipeSessionState.Idle, anchor = null)
    }

    /** 还原最小快照（进程重建持久化用）；锚点未发布时返回 null。 */
    fun snapshot(): SwipeSessionSnapshot? {
        val anchor = _anchor.value ?: return null
        return SwipeSessionSnapshot(via = via, page = page, anchor = anchor, queue = _queue.value)
    }

    private fun clearSession(state: SwipeSessionState, anchor: SwipeEntry?) {
        via = SwipeEntryVia.DetailPullDown
        page = 0
        _anchor.value = anchor
        _queue.value = emptyList()
        _tail.value = SwipeQueueTail.Idle
        _state.value = state
        _lastAutoAdvancedKey.value = null
        prefetchedKeys.clear()
    }
}
