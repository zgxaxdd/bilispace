package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.model.DANMAKU_SEGMENT_DURATION_MS
import com.naaammme.bbspace.core.model.DanmakuSessionState
import com.naaammme.bbspace.core.model.DanmakuWindow
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.VodDanmakuRequest
import com.naaammme.bbspace.core.model.VodDanmakuSegment
import com.naaammme.bbspace.core.model.danmakuWindowStartMs
import com.naaammme.bbspace.core.model.segmentWindowForPosition
import com.naaammme.bbspace.core.model.shouldCancelPendingDanmakuWindow
import com.naaammme.bbspace.core.model.shouldReplaceDanmakuWindow
import com.naaammme.bbspace.core.model.shouldRequestDanmakuWindow
import com.naaammme.bbspace.core.model.toDanmakuWindowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * VOD 弹幕分段会话。
 *
 * ## 这一版做对了什么（把移植的窗口策略变成**承载路径**）
 *
 * 之前这里是"单窗口 + 自己手写的守卫"：位置跨段才去拉那一段，拉到就换；
 * 移植进来的 [shouldReplaceDanmakuWindow] / [shouldRequestDanmakuWindow] /
 * [shouldCancelPendingDanmakuWindow]（BiliPai `DanmakuSegmentWindowPolicy.kt`）
 * **只在测试里被调用**（见 `docs/port-analysis/10-移植件生效核对总表.md`），
 * 于是"移植了但没生效"和"没移植"在行为上完全一样。
 *
 * 现在三条判据都是真实决策点，并且会话按源仓库的**±1 窗口**语义工作：
 *
 * 1. **加载的是锚点 ± 1 三段**（[segmentWindowForPosition]），不是一段；
 * 2. 目标窗口未变且已全部在内存 ⇒ 一个请求都不发（[shouldReplaceDanmakuWindow] 的否定）；
 * 3. 要发请求时先问 [shouldRequestDanmakuWindow]：同窗口同批次不发第二次，
 *    窗口变了（哪怕旧批次还没回来）必须重新发；
 * 4. 回到已显示的窗口时，[shouldCancelPendingDanmakuWindow] 判定"在途批次指向别的窗口"
 *    ⇒ 取消它，不再白跑一趟流量。
 *
 * 用户可见的结果：**跨段那一刻直接上屏**（该段早已在内存），不出现"旧段已卸载、新段还没到"的
 * 空窗；且**不重复拉同一段**、**回看不白跑流量**。
 *
 * ## 测试缝
 *
 * 取段通过构造参数 `fetchSegment` 注入（源 `VodDanmakuRepository` 是 final class，无法 mock），
 * 生产接线是 `danmakuRepository::fetchSegment`，测试注入假实现 —— 于是这条链路**有单测**了
 * （`VodDanmakuSessionTest`）。
 *
 * ## 内存
 *
 * 已取回的分段放在会话内存里、**不落盘**：一段弹幕很小，落盘要处理与既有缓存的键冲突，
 * 收益只是省一次跨段请求。用访问序 LRU 只保留最近 [MAX_CACHED_SEGMENTS] 段，会话结束即释放。
 */
internal class VodDanmakuSession(
    private val scope: CoroutineScope,
    private val fetchSegment: suspend (VodDanmakuRequest) -> VodDanmakuSegment
) {
    private val _state = MutableStateFlow(DanmakuSessionState())
    val state: StateFlow<DanmakuSessionState> = _state.asStateFlow()

    /** 当前批次的取段任务（一批 = 一个 ±1 窗口里缺的那几段，并发取）。 */
    private var fetchJob: Job? = null

    /** 在途批次**覆盖的窗口**（源 `pendingSegments` 语义：是"这一批要的窗口"，不是零散的在途段）。 */
    private var pendingWindow: Set<Int> = emptySet()

    /** 已取回的分段（访问序，最近用过的在最后）。 */
    private val loaded = LinkedHashMap<Long, VodDanmakuSegment>(0, 0.75f, true)

    private var currentIds: ResolvedVideoIds? = null
    private var currentDurationMs = 0L
    private var currentWindowId: Long? = null

    fun setSource(
        ids: ResolvedVideoIds?,
        durationMs: Long
    ) {
        if (ids == null || !ids.danmakuReady) {
            reset()
            return
        }

        if (currentIds != ids) {
            resetSource(ids, durationMs)
        } else {
            currentIds = ids
            currentDurationMs = durationMs.coerceAtLeast(0L)
            updateSourceKey(ids.toDanmakuSourceKey())
        }
    }

    fun seekTo(positionMs: Long) {
        ensureWindowAt(positionMs)
    }

    fun onProgress(positionMs: Long) {
        ensureWindowAt(positionMs)
    }

    /**
     * 位置推进的唯一入口：判断"要不要换窗口 / 要不要发请求 / 要不要取消在途批次"。
     *
     * 判据全部来自移植的窗口策略，本函数只做"把策略结论落到 IO"。
     */
    private fun ensureWindowAt(positionMs: Long) {
        val ids = currentIds ?: return
        val total = totalSegments()
        val anchorId = positionMs.coerceAtLeast(0L).toDanmakuWindowId()
        val requested = segmentWindowForPosition(positionMs, total)
        val active = loaded.keys.toList()

        // ① 回到已显示的窗口 ⇒ 撤销指向别的窗口的在途批次（源 shouldCancelPendingDanmakuWindow）
        if (shouldCancelPendingDanmakuWindow(pendingWindow, positionMs, total)) {
            DiagnosticLog.i("BbDanmaku") {
                "cancel pending batch=$pendingWindow (position=$positionMs target=$requested)"
            }
            cancelFetch()
        }

        if (anchorId != currentWindowId) {
            currentWindowId = anchorId
            updateSourceKey(ids.toDanmakuSourceKey())
        }

        // ② 目标 ±1 窗口都已在内存 ⇒ 不必换窗口（源 shouldReplaceDanmakuWindow 的否定）；
        //    顺手把锚点段上屏（可能是上一批刚回来、还没轮到上屏的那一段）。
        if (!shouldReplaceDanmakuWindow(active.map(Long::toInt), positionMs, total)) {
            applyLoadedToWindow()
            return
        }

        // 锚点段在路上/已在内存时先上屏：命中内存就是**零延迟**（跨段不空窗的关键）
        applyLoadedToWindow()

        // ③ 还缺哪些段
        val missing = requested.map(Int::toLong).filterNot { it in loaded }
        val inFlight = fetchJob?.isActive == true
        val shouldRequest = shouldRequestDanmakuWindow(
            activeSegments = active.map(Long::toInt),
            pendingSegments = pendingWindow.toList(),
            requestInFlight = inFlight,
            positionMs = positionMs,
            totalSegments = total,
            hasMissingSegments = missing.isNotEmpty()
        )
        val targets = missing.filterNot { it.toInt() in pendingWindow }
        if (shouldRequest && targets.isNotEmpty()) {
            requestSegments(targets = targets, window = requested)
        }
    }

    /** 视频总段数（时长未知时按 1 段处理，与源 `totalSegments.coerceAtLeast(1)` 同口径）。 */
    private fun totalSegments(): Int {
        if (currentDurationMs <= 0L) return 1
        return ((currentDurationMs + DANMAKU_SEGMENT_DURATION_MS - 1L) / DANMAKU_SEGMENT_DURATION_MS)
            .toInt()
            .coerceAtLeast(1)
    }

    /** 发一批取段（并发；取消上一批）。[window] 是这一批对应的目标窗口（源 `pendingSegments`）。 */
    private fun requestSegments(targets: List<Long>, window: List<Int>) {
        fetchJob?.cancel()
        val batch = targets.toSet()
        pendingWindow = window.toSet()
        DiagnosticLog.i("BbDanmaku") { "request segments=$targets (window=$pendingWindow)" }
        fetchJob = scope.launch {
            try {
                coroutineScope {
                    batch.map { id ->
                        async { fetchOne(id) }
                    }.forEach { it.await() }
                }
            } finally {
                // 只有"这一批还是最新的那一批"时才清账（被打断时 pendingWindow 已被新批次覆盖）
                if (pendingWindow == window.toSet()) pendingWindow = emptySet()
            }
        }
    }

    private suspend fun fetchOne(windowId: Long) {
        val request = buildRequest(windowId) ?: return
        val idsAtRequest = currentIds
        runCatchingCancellable { fetchSegment(request) }
            .onSuccess { segment ->
                if (segment.request.ids != currentIds) return@onSuccess
                loaded[windowId] = segment
                trimCache()
                DiagnosticLog.i("BbDanmaku") {
                    "segment ok windowId=$windowId items=${segment.items.size}"
                }
                applyLoadedToWindow()
            }
            .onFailure { error ->
                if (idsAtRequest != currentIds) return@onFailure
                DiagnosticLog.w("BbDanmaku", "segment fail windowId=$windowId: ${error.message}")
                // 只有"当前正在显示的那一段"失败才弹错误；邻段预取失败静默
                // （它是优化，失败就当没预取，下次跨段仍走正常请求）
                if (windowId == currentWindowId) {
                    _state.update {
                        it.copy(lastError = error.message ?: "Failed to load danmaku segment")
                    }
                }
            }
    }

    /** 锚点段在内存里就上屏（跨段命中 = 同步完成，不等 IO）。 */
    private fun applyLoadedToWindow() {
        val anchorId = currentWindowId ?: return
        val segment = loaded[anchorId] ?: return
        val current = _state.value.window
        if (current?.id == anchorId && current.items === segment.items) return
        _state.update {
            it.copy(window = DanmakuWindow(id = anchorId, items = segment.items), lastError = null)
        }
    }

    private fun trimCache() {
        if (loaded.size <= MAX_CACHED_SEGMENTS) return
        val iterator = loaded.keys.iterator()
        while (loaded.size > MAX_CACHED_SEGMENTS && iterator.hasNext()) {
            val key = iterator.next()
            // 显示中的锚点段永远保留（其余按访问序老的先走）
            if (key != currentWindowId) iterator.remove()
        }
    }

    private fun buildRequest(windowId: Long): VodDanmakuRequest? {
        val ids = currentIds ?: return null
        val startMs = danmakuWindowStartMs(windowId)
        val positionMs = if (currentDurationMs > 0L) {
            startMs.coerceAtMost((currentDurationMs - 1L).coerceAtLeast(0L))
        } else {
            startMs
        }
        return VodDanmakuRequest(
            ids = ids,
            positionMs = positionMs,
            durationMs = currentDurationMs
        )
    }

    private fun cancelFetch() {
        fetchJob?.cancel()
        fetchJob = null
        pendingWindow = emptySet()
    }

    fun clear() {
        reset()
    }

    private fun resetSource(
        ids: ResolvedVideoIds,
        durationMs: Long
    ) {
        DiagnosticLog.i(
            "BbDanmaku",
            "setSource reset aid=${ids.aid} cid=${ids.cid} durationMs=$durationMs"
        )
        cancelFetch()
        loaded.clear()
        currentIds = ids
        currentDurationMs = durationMs.coerceAtLeast(0L)
        currentWindowId = null
        _state.value = DanmakuSessionState(sourceKey = ids.toDanmakuSourceKey())
    }

    private fun updateSourceKey(sourceKey: String) {
        _state.update { state ->
            if (state.sourceKey == sourceKey) state else state.copy(sourceKey = sourceKey)
        }
    }

    private fun reset() {
        cancelFetch()
        loaded.clear()
        currentIds = null
        currentDurationMs = 0L
        currentWindowId = null
        _state.value = DanmakuSessionState()
    }
}

/** 会话内存里保留的分段数上限（访问序 LRU；当前锚点段不会被逐出）。 */
private const val MAX_CACHED_SEGMENTS = 8

private fun ResolvedVideoIds.toDanmakuSourceKey(): String {
    return buildString {
        append("vod:")
        append(aid)
        append(':')
        append(cid)
        bvid?.takeIf(String::isNotBlank)?.let {
            append(':')
            append(it)
        }
    }
}
