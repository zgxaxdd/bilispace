package com.naaammme.bbspace.infra.player.cdn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.concurrent.Semaphore

data class CdnNodeTransfer(
    val host: String,
    val active: Int = 0,
    val bytes: Long = 0,
    val speedBps: Long = 0,
    val firstByteMs: Long? = null,
    val completed: Int = 0,
    val failures: Int = 0,
    val cooldownUntilMs: Long = 0,
    val updatedAtMs: Long = 0,
    val lastStatus: Int? = null
)

data class CdnTransferSnapshot(
    val nodes: List<CdnNodeTransfer> = emptyList(),
    val preferredHost: String? = null,
    val networkBytes: Long = 0,
    val deliveredBytes: Long = 0,
    val fallbackCount: Int = 0,
    val resumedCount: Int = 0,
    val rescueCount: Int = 0,
    val bufferMs: Long = 0,
    val bitrateBps: Long = 0,
    val connections: Int = 1,
    val speedHistory: List<Long> = emptyList(),
    val parallelEnabled: Boolean = false,
    /**
     * **属地/自定义线路插件**这次真正下发给引擎的候选表（W4 收口，2026-10-18）。
     *
     * 为什么不放在会话私有态：诊断面板要显示"真正用了哪条"，而面板读的是本快照。
     * 若面板自己按设置重算一遍，显示的是"预测顺序"而不是"下发顺序" —— 那种面板会说谎。
     * 只存 **host 名**（不含签名 URL），与既有 `preferredHost` 同一隐私口径。
     */
    val regionEnabled: Boolean = false,
    val regionHosts: List<String> = emptyList(),
    val regionSources: List<String> = emptyList(),
    val updatedAtMs: Long = 0
)

/**
 * 卡顿诊断行（`Media3PlayerEngine` 的 `StallDiag`）要的一次性快照。
 *
 * 刻意**不进** [CdnTransferSnapshot]、也不 `publish()`：这些字段只服务"卡住时打一行日志"，
 * 放进可观察状态会让每个 500ms tick 都多一次 StateFlow 写入（反而制造卡顿）。
 */
data class CdnRuntimeDiag(
    val permitsAvailable: Int = 0,
    val parallelEnabled: Boolean = false,
    val parallelSession: Boolean = false,
    val routeFresh: Boolean = false,
    val routeCount: Int = 0,
    val lastWindow: String = "-",
    val lastError: String = "-",
    val fallbackCount: Int = 0,
    val rescueCount: Int = 0,
    val bufferMs: Long = 0,
    val bitrateBps: Long = 0
)

/** Process-local, bounded diagnostics. URLs/signatures never enter the observable state or disk. */
object CdnTransferRuntime {
    private data class Route(val urls: List<String>, val registeredAtMs: Long)
    private val routes = linkedMapOf<String, Route>()
    private var preferredPaths: Set<String> = emptySet()
    private val observedMedia = linkedMapOf<String, Pair<String, Long>>()
    private val mutableState = MutableStateFlow(CdnTransferSnapshot())
    val state = mutableState.asStateFlow()
    val permits = Semaphore(CDN_MAX_CONNECTIONS, true)
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var parallelEnabled: Boolean = false
        private set
    private var networkIdentity: String? = null
    private var limitedUntilMs = 0L
    private var lastPublishMs = 0L
    private var lastSampleMs = 0L
    private var lastSampleBytes = 0L
    private var snapshot = CdnTransferSnapshot()

    // ---- 卡顿诊断的旁路记账（见 [diag]）----
    // 全部 @Volatile、不加锁写：这些只在"卡住时打一行"里被读，不值得让高频传输路径去抢锁。
    @Volatile private var diagParallelSession = false
    @Volatile private var diagLastWindow = "-"
    @Volatile private var diagLastError = "-"

    @Synchronized
    fun configure(active: Boolean, parallel: Boolean) {
        enabled = active
        parallelEnabled = active && parallel
        snapshot = snapshot.copy(parallelEnabled = parallelEnabled)
        if (!active) { routes.clear(); observedMedia.clear(); snapshot = snapshot.copy(preferredHost = null) }
        publish(true)
    }

    @Synchronized
    fun register(video: List<String>, audio: List<String>) {
        val now = nowMs()
        if (snapshot.preferredHost != null && video.isNotEmpty() && video.none { path(it) in preferredPaths }) {
            snapshot = snapshot.copy(preferredHost = null)
            preferredPaths = emptySet()
            publish(true)
        }
        (video + audio).filter { url ->
            // **适配（2026-10-06）**：源仓库只登记 `https`，但 B 站下发的 CDN 候选**大量是 `http://`**
            // （PCDN 与部分 upos 镜像都是明文，正是本仓此前"部分视频打不开"的那批 URL）。
            // 只认 https ⇒ routes 永远为空 ⇒ candidates() 返回空 ⇒ **并行静默不触发** ✗。
            // 这里放宽为 http/https：并行分片走的是**同一条** CDN URL，安全性不因 scheme 改变，
            // 而本仓明文策略已按源仓库口径放行（network_security_config base-config=true）✓。
            runCatching { URI(url).let { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() && !it.rawPath.isNullOrBlank() } }.getOrDefault(false)
        }.groupBy(::path).forEach { (path, urls) ->
            if (path.isNotBlank()) routes[path] = Route(urls.distinct(), now)
        }
        while (routes.size > 64) routes.remove(routes.keys.first())
    }

    /** Actual media used by audio/mini players is diagnostic input, not new parallel authorization. */
    @Synchronized
    fun observeMediaUrl(url: String): Boolean {
        if (!enabled || !isDiagnosticMediaUrl(url)) return false
        observedMedia.remove(path(url))
        observedMedia[path(url)] = url to nowMs()
        while (observedMedia.size > 2) observedMedia.remove(observedMedia.keys.first())
        return true
    }

    @Synchronized
    fun recentMediaUrls(): List<String> {
        val now = nowMs()
        return observedMedia.values.toList().asReversed()
            .filter { now - it.second <= 10 * 60_000L }
            .flatMap { (url, _) -> candidates(url).ifEmpty { listOf(url) } }
            .distinct().take(3)
    }

    @Synchronized
    fun candidates(url: String): List<String> {
        if (!enabled) return emptyList()
        val route = routes[path(url)] ?: return emptyList()
        // Only exact, original signed URLs registered by playurl are eligible.
        if (url !in route.urls) return emptyList()
        if (!isRouteFresh(route.registeredAtMs, nowMs())) return emptyList()
        return rank(route.urls)
    }

    @Synchronized
    fun rank(urls: List<String>): List<String> {
        val now = nowMs()
        val nodes = snapshot.nodes.associateBy { it.host }
        return urls.sortedWith(compareBy<String> {
            (nodes[hostFromCdnUrl(it)]?.cooldownUntilMs ?: 0) > now
        }.thenByDescending { hostFromCdnUrl(it) == snapshot.preferredHost }.thenByDescending {
            val node = nodes[hostFromCdnUrl(it)]
            if (node == null || now - node.updatedAtMs > 120_000) 0L else node.speedBps
        })
    }

    @Synchronized
    fun networkChanged(identity: String) {
        if (networkIdentity == identity) return
        networkIdentity = identity
        limitedUntilMs = 0
        snapshot = snapshot.copy(
            nodes = snapshot.nodes.map { it.copy(speedBps = 0, firstByteMs = null, cooldownUntilMs = 0) },
            speedHistory = emptyList(),
            bufferMs = 0
        )
        lastSampleMs = 0
        lastSampleBytes = snapshot.networkBytes
        publish(true)
    }

    @Synchronized
    fun connectionLimit(): Int {
        val speed = snapshot.speedHistory.lastOrNull()?.takeIf { it > 0 }
            ?: snapshot.nodes.sumOf { it.speedBps * it.active }
        val count = resolveCdnConnections(snapshot.bufferMs, speed, snapshot.bitrateBps, nowMs() < limitedUntilMs)
        snapshot = snapshot.copy(connections = count)
        publish()
        return count
    }

    @Synchronized
    fun playback(bufferMs: Long, bitrateBps: Long) {
        snapshot = snapshot.copy(bufferMs = bufferMs.coerceAtLeast(0), bitrateBps = bitrateBps.coerceAtLeast(0))
        publish()
    }

    @Synchronized
    fun start(host: String) {
        changeNode(host) { it.copy(active = it.active + 1) }
        publish(true)
    }

    @Synchronized
    fun progress(host: String, bytes: Int, speedBps: Long, firstByteMs: Long?) {
        if (bytes <= 0) return
        snapshot = snapshot.copy(networkBytes = snapshot.networkBytes + bytes)
        changeNode(host) { it.copy(bytes = it.bytes + bytes, speedBps = speedBps, firstByteMs = firstByteMs ?: it.firstByteMs) }
        publish()
    }

    @Synchronized
    fun finish(host: String, success: Boolean, status: Int? = null, canceled: Boolean = false) {
        val now = nowMs()
        if (status == 412 || status == 429) limitedUntilMs = now + 30_000
        changeNode(host) {
            it.copy(
                active = (it.active - 1).coerceAtLeast(0),
                completed = it.completed + if (success) 1 else 0,
                failures = it.failures + if (!success && !canceled) 1 else 0,
                lastStatus = status ?: it.lastStatus,
                cooldownUntilMs = if (!success && !canceled) maxOf(it.cooldownUntilMs, now + 30_000) else it.cooldownUntilMs
            )
        }
        publish(true)
    }

    @Synchronized fun delivered(bytes: Int) {
        snapshot = snapshot.copy(deliveredBytes = snapshot.deliveredBytes + bytes)
        publish()
    }
    @Synchronized fun fallback() { snapshot = snapshot.copy(fallbackCount = snapshot.fallbackCount + 1); publish(true) }
    @Synchronized fun resumed() { snapshot = snapshot.copy(resumedCount = snapshot.resumedCount + 1); publish(true) }
    @Synchronized fun rescue() { snapshot = snapshot.copy(rescueCount = snapshot.rescueCount + 1); publish(true) }

    /** 当前 DataSource 实例是不是在并行模式（降级后可恢复，见 `CdnConnectionPolicy`）。 */
    fun noteParallelSession(active: Boolean) { diagParallelSession = active }

    /** 最近一次窗口下载的结局（成功/降级/重试），只进诊断行。 */
    fun noteWindowResult(result: String) { diagLastWindow = result.take(160) }

    /** 最近一次窗口失败原因，只进诊断行（这是"为什么卡住"唯一的一手证据）。 */
    fun noteWindowError(error: String?) {
        diagLastError = (error ?: "-").take(160)
    }

    /**
     * 发布**本次真正下发**的属地/自定义线路候选表（见 [CdnTransferSnapshot.regionHosts]）。
     *
     * 只在**内容变化时**写入 + publish：开播一次写一次，不是每 500ms 轮询都写
     * （本类的 `publish()` 会进 `StateFlow`，高频写等于自己制造重组）。
     */
    @Synchronized
    fun noteRegionSelection(enabled: Boolean, hosts: List<String>, sources: List<String>) {
        val trimmedHosts = hosts.take(MAX_REGION_HOSTS)
        val trimmedSources = sources.take(MAX_REGION_HOSTS)
        if (snapshot.regionEnabled == enabled &&
            snapshot.regionHosts == trimmedHosts &&
            snapshot.regionSources == trimmedSources
        ) {
            return
        }
        snapshot = snapshot.copy(
            regionEnabled = enabled,
            regionHosts = trimmedHosts,
            regionSources = trimmedSources
        )
        publish(true)
    }

    /**
     * 卡顿诊断快照。
     *
     * `routeFresh` 直接复算"最近一次 register 是否还在 TTL 内"——路由过期是长会话并行
     * 静默降级的另一个成因，卡住时这一位必须是可判定的 true/false 而不是靠猜。
     */
    @Synchronized
    fun diag(): CdnRuntimeDiag {
        val now = nowMs()
        val newestRoute = routes.values.maxOfOrNull { it.registeredAtMs }
        return CdnRuntimeDiag(
            permitsAvailable = permits.availablePermits(),
            parallelEnabled = parallelEnabled,
            parallelSession = diagParallelSession,
            routeFresh = newestRoute != null && isRouteFresh(newestRoute, now),
            routeCount = routes.size,
            lastWindow = diagLastWindow,
            lastError = diagLastError,
            fallbackCount = snapshot.fallbackCount,
            rescueCount = snapshot.rescueCount,
            bufferMs = snapshot.bufferMs,
            bitrateBps = snapshot.bitrateBps
        )
    }
    @Synchronized fun avoid(host: String) {
        changeNode(host) { it.copy(cooldownUntilMs = nowMs() + 60_000) }; publish(true)
    }
    @Synchronized fun preferHost(host: String?) {
        preferredPaths = if (host.isNullOrBlank()) emptySet() else routes.keys.toSet()
        snapshot = snapshot.copy(preferredHost = host?.takeIf { it.isNotBlank() })
        publish(true)
    }
    @Synchronized fun restoreRoutes() {
        preferredPaths = emptySet()
        snapshot = snapshot.copy(preferredHost = null, nodes = snapshot.nodes.map { it.copy(cooldownUntilMs = 0) })
        limitedUntilMs = 0
        publish(true)
    }

    private fun changeNode(host: String, change: (CdnNodeTransfer) -> CdnNodeTransfer) {
        val previous = snapshot.nodes.firstOrNull { it.host == host } ?: CdnNodeTransfer(host)
        val next = change(previous).copy(updatedAtMs = nowMs())
        val nodes = snapshot.nodes.filterNot { it.host == host } + next
        // Never discard an active transfer when bounding history.
        snapshot = snapshot.copy(nodes = nodes.filter { it.active > 0 } + nodes.filter { it.active == 0 }.takeLast(24))
    }

    /** 诊断快照里最多保留多少条候选（只是展示用；本条线路的真实候选在数据层，不进 UI 状态）。 */
    private const val MAX_REGION_HOSTS = 8

    private fun publish(force: Boolean = false) {
        val now = nowMs()
        if (!force && now - lastPublishMs < 250) return
        if (lastSampleMs == 0L) { lastSampleMs = now; lastSampleBytes = snapshot.networkBytes }
        if (now - lastSampleMs >= 1_000) {
            val speed = (snapshot.networkBytes - lastSampleBytes) * 1_000 / (now - lastSampleMs)
            snapshot = snapshot.copy(speedHistory = (snapshot.speedHistory + speed).takeLast(12))
            lastSampleMs = now
            lastSampleBytes = snapshot.networkBytes
        }
        lastPublishMs = now
        mutableState.value = snapshot.copy(updatedAtMs = now)
    }

    private fun path(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
    private fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/** Restrict diagnostics to actual Bilibili media, excluding manifests, images and arbitrary URLs. */
fun isDiagnosticMediaUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host.orEmpty().lowercase()
    val path = uri.rawPath.orEmpty().lowercase()
    uri.scheme.equals("https", true) &&
        listOf("bilivideo.com", "bilivideo.cn", "akamaized.net").any { host == it || host.endsWith(".$it") } &&
        (path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".flv"))
}.getOrDefault(false)

/**
 * 路由授权的**有效期**：`register()` 登记的是"本次 playurl 给出的合法候选"，
 * 超过这个时长就不再采信（源仓库同口径：防止过期的 playurl 授权被长期沿用）。
 *
 * ⚠️ 它也是"长会话静默降级"的成因：调用方必须周期性续期，
 * 否则一次播放超过 10 分钟后 `candidates()` 恒空 ⇒ 并行下载降级成单连接。
 * 续期点见 `Media3PlayerEngine.refreshCdnRoutesIfStale()`（5 分钟一次），
 * 回归守卫见 `CdnRouteRefreshTest`。
 */
const val CDN_ROUTE_TTL_MS: Long = 10L * 60L * 1000L

/** 路由是否还在有效期内（纯函数，便于单测边界）。 */
internal fun isRouteFresh(registeredAtMs: Long, nowMs: Long): Boolean =
    nowMs - registeredAtMs <= CDN_ROUTE_TTL_MS
