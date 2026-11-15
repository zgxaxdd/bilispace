package com.naaammme.bbspace.infra.player.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.Callback
import com.naaammme.bbspace.infra.player.CdnLineOutcomeListener
import com.naaammme.bbspace.infra.player.CdnServingHostListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong


@UnstableApi
class CdnPlaybackDataSource(
    private val upstream: DataSource,
    private val background: Boolean = false,
    private val client: OkHttpClient? = null,
    /** 应用上下文：只为判断「是否 Wi-Fi」（并行分片只在 Wi-Fi 下开，省流量）。 */
    private val appContext: Context? = null,
    /**
     * 是否 Wi-Fi。**不引入源仓库的 NetworkUtils**（本仓没有该工具类），
     * 用 ConnectivityManager 实现同一判据：只认 TRANSPORT_WIFI。
     */
    private val isWifi: () -> Boolean = {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    },
    /**
     * 闭环修复（2026-10-06）：这条线路这次**是真通了还是失败了**，回传给健康度。
     * 与 CdnFailoverDataSource 共用同一个回调口 ⇒ 并行/单连接两条路径的实测结果都会入账。
     */
    private val lineOutcomeListener: CdnLineOutcomeListener = CdnLineOutcomeListener.NoOp,
    /**
     * 「实际供数的是哪条 host」回传（2026-10-08 记账口径修正）。
     * **预取链不传**（默认 [CdnServingHostListener.NoOp]）—— 后台预取不该影响"播放在用哪条"。
     */
    private val servingHostListener: CdnServingHostListener = CdnServingHostListener.NoOp
) : BaseDataSource(true) {

    /**
     * 分片读取用的 OkHttp：**直接用注入进来的 app 级 client**（带 UA/Cookie 配置）。
     *
     * 源仓库这里硬取 app 单例；本仓不能那样（infra 不许依赖 app 层），也不许自建
     * （`NetworkTimeoutGuardTest` 要求全仓只有一处 client builder）⇒ 由构造方注入。
     */
    private val rangeClient: OkHttpClient get() = requireNotNull(client) {
        "CdnPlaybackDataSource 需要注入 OkHttpClient（分片读取用）"
    }
    private var spec: DataSpec? = null
    private var candidates: List<String> = emptyList()
    private var consumed = 0L
    private var window = ByteArray(0)
    private var windowOffset = 0
    private var delegateOpen = false
    private var started = false
    private var parallel = false
    /** 一次 open 内不变的"有资格并行"判据；降级后重升只复用这一份（见 [maybeRestoreParallel]）。 */
    private var parallelEligible = false
    /** 降级成单连接的时刻（单调毫秒）；0 = 没降级过。 */
    private var parallelDisabledAtMs = 0L
    /** 本次 open 内已发生的"降级 → 重升"次数（上限见 [MAX_PARALLEL_RESTORES]）。 */
    private var parallelRestoreCount = 0
    private var monitored = false
    private var delegateHost = ""
    /**
     * **实际打开的那条** URL（07 复核报告 N1 修复，2026-10-06）。
     *
     * 内层 [CdnFailoverDataSource.open] 会把 URI 换成真正能用的那条备用线路
     * （`dataSpec.buildUpon().setUri(url)`），所以 `spec.uri` 只是"**请求的**那条"。
     * 健康度记账必须记在实际服务的那条头上，否则坏线路白得一次成功、好线路白加一次分。
     */
    private var delegateUri = ""
    private var delegateBytes = 0L
    private var delegateStartedNs = 0L
    private var delegateFirstByteMs: Long? = null
    @Volatile private var closed = true
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        spec = dataSpec
        consumed = 0
        window = ByteArray(0)
        windowOffset = 0
        if (CdnTransferRuntime.enabled) {
            val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            CdnTransferRuntime.networkChanged(manager?.activeNetwork?.toString().orEmpty())
        }
        candidates = CdnTransferRuntime.candidates(dataSpec.uri.toString())
        val observedMedia = CdnTransferRuntime.observeMediaUrl(dataSpec.uri.toString())
        monitored = candidates.isNotEmpty() || observedMedia
        val wifi = isWifi()
        parallelEligible = candidates.isNotEmpty() && CdnTransferRuntime.parallelEnabled && wifi &&
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
            dataSpec.length >= CDN_MIN_PARALLEL_BYTES &&
            dataSpec.position >= 0 && dataSpec.length - 1 <= Long.MAX_VALUE - dataSpec.position
        parallel = parallelEligible
        parallelDisabledAtMs = 0L
        CdnTransferRuntime.noteParallelSession(parallel)
        // **可证伪证据（2026-10-06）**：没有这行日志就等于"并行没跑" ✗ —— 我不再靠"应该会跑"交差。
        com.naaammme.bbspace.core.common.log.DiagnosticLog.i("CdnParallel") {
            "enabled=${CdnTransferRuntime.enabled} monitored=$monitored parallel=$parallel " +
                "cands=${candidates.size} wifi=$wifi len=${dataSpec.length} pos=${dataSpec.position}"
        }
        transferInitializing(dataSpec)
        val length = if (parallel) dataSpec.length else openDelegate(dataSpec)
        started = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed || Thread.currentThread().isInterrupted) throw IOException("CDN read canceled")
        val current = spec ?: throw IOException("CDN source is not open")
        if (current.length != C.LENGTH_UNSET.toLong() && consumed >= current.length) return C.RESULT_END_OF_INPUT
        val count: Int
        if (!parallel) {
            try {
                count = upstream.read(buffer, offset, length)
            } catch (error: IOException) {
                CdnTransferRuntime.noteWindowError(error.message ?: error::class.simpleName)
                if (monitored) finishDelegate(false, closed)
                throw error
            }
            if (count > 0 && monitored) recordDelegateBytes(count)
            // 降级不可逆是"卡在 5 秒"的直接成因：单连接稳定跑够冷却、缓冲回到 10s 以上时重试并行。
            if (count > 0) maybeRestoreParallel()
        } else {
            if (windowOffset == window.size) {
                try {
                    if (!CdnTransferRuntime.parallelEnabled ||
                        !isWifi()) {
                        throw IOException("CDN parallel mode disabled")
                    }
                    val startupWindow = if (CdnTransferRuntime.state.value.bufferMs < 5_000) CDN_WINDOW_BYTES / 2 else CDN_WINDOW_BYTES
                    val size = minOf(startupWindow, current.length - consumed)
                    window = downloadWindow(current, current.position + consumed, size)
                    windowOffset = 0
                    CdnTransferRuntime.noteWindowResult("ok bytes=$size")
                } catch (error: IOException) {
                    cancelCalls()
                    CdnTransferRuntime.noteWindowError(error.message ?: error::class.simpleName)
                    if (closed || Thread.currentThread().isInterrupted || background) throw error
                    CdnTransferRuntime.fallback()
                    parallel = false
                    parallelDisabledAtMs = nowMs()
                    CdnTransferRuntime.noteParallelSession(false)
                    // Resume at delivered position, never restart or expose a partial window.
                    openDelegate(current.subrange(consumed))
                    return read(buffer, offset, length)
                }
            }
            count = minOf(length, window.size - windowOffset)
            window.copyInto(buffer, offset, windowOffset, windowOffset + count)
            windowOffset += count
        }
        if (closed) throw IOException("CDN read canceled")
        if (count > 0) {
            consumed += count
            bytesTransferred(count)
            if (monitored) CdnTransferRuntime.delivered(count)
        } else if (count == C.RESULT_END_OF_INPUT && monitored) {
            finishDelegate(true)
        }
        return count
    }

    override fun getUri(): Uri? = if (delegateOpen) upstream.uri else spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (delegateOpen) upstream.responseHeaders else emptyMap()

    fun cancelPendingRequests() {
        closed = true
        cancelCalls()
        if (delegateOpen) upstream.close()
    }

    override fun close() {
        closed = true
        cancelCalls()
        try {
            if (delegateOpen) upstream.close()
        } finally {
            // CacheWriter can stop at its finite range without an EOF read.
            val current = spec
            val complete = current != null && current.length >= 0 && consumed >= current.length
            if (monitored) finishDelegate(complete, canceled = true)
            delegateOpen = false
            window = ByteArray(0)
            spec = null
            if (started) { started = false; transferEnded() }
        }
    }

    private fun openDelegate(dataSpec: DataSpec): Long {
        delegateHost = hostFromCdnUrl(dataSpec.uri.toString())
        // 兜底：open 失败时按"请求的那条"报（内层已逐候选报过错，这里只是不静默丢事件）。
        delegateUri = dataSpec.uri.toString()
        delegateBytes = 0
        delegateStartedNs = System.nanoTime()
        delegateFirstByteMs = null
        if (monitored) CdnTransferRuntime.start(delegateHost)
        // Set before open so failed opens can still release upstream resources in close().
        delegateOpen = true
        return try {
            val length = upstream.open(dataSpec)
            // **换线记账修复（07 复核报告 N1）**：`dataSpec.uri` 是"请求的那条"，而内层
            // failover 可能**已经换成备用线路在服务**。用请求 URI 记账会让已经失败的那条
            // 记上一次成功（−40 被 +25 抵消成 −15），并和内层的上报重复计一次 ⇒
            // 智能选线的学习被削弱（坏线路不被降权，正是这个闭环要修的事）。
            // 上游真实 URI 才是"实际服务的那条"；命中预取/缓存时它等于请求 URI，语义不变。
            runCatching { upstream.uri?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { delegateUri = it }
            // 会话层要知道"现在供数的是哪条 host"（记账口径修正 2026-10-08）。
            hostFromCdnUrl(delegateUri).takeIf(String::isNotBlank)?.let(servingHostListener::onServingHost)
            length
        } catch (error: IOException) {
            if (monitored) finishDelegate(false, closed)
            throw error
        }
    }

    private fun recordDelegateBytes(count: Int) {
        delegateBytes += count
        val elapsedMs = ((System.nanoTime() - delegateStartedNs) / 1_000_000).coerceAtLeast(1)
        if (delegateFirstByteMs == null) delegateFirstByteMs = elapsedMs
        CdnTransferRuntime.progress(delegateHost, count, delegateBytes * 1_000 / elapsedMs, delegateFirstByteMs)
    }

    private fun finishDelegate(success: Boolean, canceled: Boolean = false) {
        if (delegateHost.isNotEmpty()) {
            CdnTransferRuntime.finish(delegateHost, success, canceled = canceled)
            // 单连接路径：成功/失败都记账（这里没有竞速歧义 —— 用没用它、成没成，是确定的）。
            // 但记的必须是 [delegateUri]（**实际打开的那条**），不是 `spec.uri`（**请求的那条**）：
            // 内层 failover 换线后两者不同，用后者等于给坏线路补发一次成功（07 复核报告 N1）。
            delegateUri.takeIf { it.isNotBlank() }?.let { url ->
                lineOutcomeListener.onOutcome(url, success)
            }
            delegateHost = ""
        }
    }

    private class Attempt(val range: CdnByteRange, val url: String, prefix: ByteArray = ByteArray(0)) {
        val bytes = ByteArray(range.length.toInt()).also { prefix.copyInto(it) }
        @Volatile var received = prefix.size
        @Volatile var lastProgressNs = System.nanoTime()
        val result = CompletableFuture<ByteArray>()
        @Volatile var call: Call? = null
        var status: Int? = null
    }

    private fun downloadWindow(dataSpec: DataSpec, start: Long, length: Long): ByteArray {
        val urls = CdnTransferRuntime.rank(candidates)
        if (background && CdnTransferRuntime.state.value.bufferMs < CDN_PREFETCH_SAFE_BUFFER_MS) {
            throw IOException("CDN prefetch yields to playback")
        }
        val limit = if (background) 1 else CdnTransferRuntime.connectionLimit()
        val count = minOf(limit, CdnTransferRuntime.permits.availablePermits().coerceAtLeast(1))
        val pieces = splitCdnRange(start, length, count)
        val total = AtomicLong(-1)
        if (background && CdnTransferRuntime.permits.availablePermits() <= 1) throw IOException("CDN prefetch yields its slot")
        // 空切分（非法区间）不能拿一个全零窗口当数据交付：**先判**，否则下面
        // `tryAcquire(0)` 会抛 IllegalArgumentException（permit 数为 0 非法）。
        if (pieces.isEmpty()) throw IOException("CDN empty range")
        // 槽位异常：先等一个有界时间让上一窗口的 OkHttp 回调把槽位还回来，再判失败。
        // 修复前直接 tryAcquire ⇒ 瞬时失败即落进 read() 的 catch ⇒ **本会话永久单连接**，
        // 而并发策略的救援分支（缓冲 <5s → 3 连接）从此不可达。
        // ⚠️ 槽位数必须取**实际片数** `pieces.size`，不能取 `count`：`splitCdnRange` 会被
        // 区间长度夹小（尾窗只剩 1~3 字节时 count=3 而 pieces.size=1），按 count 占位
        // 就会在成功路径上永久漏掉 `count − pieces.size` 个 permit（进程级 Semaphore(4)，
        // 漏几次之后并行静默降档，且是单向泄漏）。评审 A5。
        if (!CdnTransferRuntime.permits.tryAcquire(
                pieces.size,
                CdnConnectionPolicy.WINDOW_SLOT_WAIT_MS,
                TimeUnit.MILLISECONDS
            )
        ) {
            throw IOException("CDN connection budget exhausted")
        }
        if (pieces.isEmpty()) {
            throw IOException("CDN empty range")
        }
        // 异常路径必须归还"已领取但没交给任何 attempt"的槽位（attempt 自己的槽位由回调归还）。
        var launched = 0
        val attempts = try {
            pieces.mapIndexed { index, range ->
                val selected = if (CdnTransferRuntime.state.value.preferredHost != null) urls.first() else urls[index % urls.size]
                val attempt = launchAttempt(dataSpec, range, selected, total, reserved = true)
                    ?: throw IOException("CDN connection budget exhausted")
                launched += 1
                attempt
            }
        } catch (error: Throwable) {
            repeat((pieces.size - launched).coerceAtLeast(0)) { CdnTransferRuntime.permits.release() }
            throw error
        }
        val output = ByteArray(length.toInt())
        var outputOffset = 0
        for (primary in attempts) {
            var rescue: Attempt? = null
            var retry: Attempt? = null
            var winner: Attempt? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (winner == null) {
                if (closed || Thread.currentThread().isInterrupted) throw IOException("CDN read canceled")
                val active = listOfNotNull(primary, rescue, retry)
                winner = active.firstOrNull { it.result.isDone && !it.result.isCompletedExceptionally }
                if (winner != null) break
                if (System.nanoTime() >= deadline) throw IOException("CDN window timed out")
                if (active.all { it.result.isCompletedExceptionally }) {
                    if (retry != null || primary.status == 403 || primary.status == 412 || primary.status == 429) {
                        throw IOException("CDN range failed")
                    }
                    // Only validated contiguous bytes can be carried into a tail retry.
                    val best = active.maxBy { it.received }
                    val nextUrl = urls.firstOrNull { it != best.url } ?: best.url
                    retry = launchAttempt(dataSpec, primary.range, nextUrl, total, best.bytes.copyOf(best.received))
                    if (retry == null) throw IOException("CDN retry budget exhausted")
                    CdnTransferRuntime.resumed()
                } else if (!background && CdnTransferRuntime.state.value.bufferMs < 15_000 &&
                    rescue == null && retry == null && urls.size > 1 &&
                    System.nanoTime() - primary.lastProgressNs > TimeUnit.MILLISECONDS.toNanos(900)) {
                    val nextUrl = urls.firstOrNull { hostFromCdnUrl(it) != hostFromCdnUrl(primary.url) }
                    if (nextUrl != null) {
                        rescue = launchAttempt(
                            dataSpec, primary.range, nextUrl, total,
                            primary.bytes.copyOf(minOf(primary.received, primary.bytes.size - 1))
                        )
                        if (rescue != null) CdnTransferRuntime.rescue()
                    }
                }
                try {
                    CompletableFuture.anyOf(*active.filterNot { it.result.isDone }.map { it.result }.toTypedArray())
                        .get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Wake periodically for cancellation and stalled-piece rescue.
                } catch (_: ExecutionException) {
                    // The loop either finds a valid winner or retries a failed tail.
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("CDN read interrupted", error)
                }
            }
            val completed = winner ?: throw IOException("CDN missing piece")
            listOfNotNull(primary, rescue, retry).filter { it !== completed }.forEach { it.call?.cancel() }
            completed.bytes.copyInto(output, outputOffset)
            outputOffset += completed.bytes.size
        }
        return output
    }

    private fun launchAttempt(
        dataSpec: DataSpec,
        range: CdnByteRange,
        url: String,
        total: AtomicLong,
        prefix: ByteArray = ByteArray(0),
        reserved: Boolean = false
    ): Attempt? {
        if (!reserved && (closed || (background && CdnTransferRuntime.permits.availablePermits() <= 1) ||
                !CdnTransferRuntime.permits.tryAcquire())) return null
        val attempt = Attempt(range, url, prefix)
        if (closed) {
            CdnTransferRuntime.permits.release()
            attempt.result.completeExceptionally(IOException("CDN read canceled"))
            return attempt
        }
        if (prefix.size == attempt.bytes.size) {
            CdnTransferRuntime.permits.release()
            attempt.result.complete(attempt.bytes)
            return attempt
        }
        val requested = CdnByteRange(range.start + prefix.size, range.endInclusive)
        val call = try {
            val request = Request.Builder().url(url)
                .header("Referer", "https://www.bilibili.com")
                .header("User-Agent", "Mozilla/5.0")
                .apply { dataSpec.httpRequestHeaders.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=${requested.start}-${requested.endInclusive}")
                .header("Accept-Encoding", "identity")
                .build()
            (client ?: rangeClient).newCall(request)
        } catch (error: IllegalArgumentException) {
            CdnTransferRuntime.permits.release()
            attempt.result.completeExceptionally(IOException("CDN invalid request", error))
            return attempt
        }
        attempt.call = call
        calls += call
        val host = hostFromCdnUrl(url)
        val startedNs = System.nanoTime()
        CdnTransferRuntime.start(host)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                end(call, false, call.isCanceled())
                attempt.result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                var success = false
                var failure: Exception? = null
                try {
                    response.use {
                        attempt.status = it.code
                        if (!isExactCdnRange(it.code, it.header("Content-Range"), requested)) throw IOException("CDN invalid range")
                        val actual = parseCdnContentRange(it.header("Content-Range")) ?: throw IOException("CDN missing range")
                        total.compareAndSet(-1, actual.total)
                        if (total.get() != actual.total) throw IOException("CDN mirrors disagree on length")
                        val body = it.body ?: throw IOException("CDN empty body")
                        body.byteStream().use { input ->
                            var firstByteMs: Long? = null
                            while (attempt.received < attempt.bytes.size) {
                                if (call.isCanceled() || closed) throw IOException("CDN read canceled")
                                val read = input.read(attempt.bytes, attempt.received, attempt.bytes.size - attempt.received)
                                if (read < 0) throw IOException("CDN truncated range")
                                if (read == 0) continue
                                attempt.received += read
                                attempt.lastProgressNs = System.nanoTime()
                                val elapsedMs = ((attempt.lastProgressNs - startedNs) / 1_000_000).coerceAtLeast(1)
                                if (firstByteMs == null) firstByteMs = elapsedMs
                                CdnTransferRuntime.progress(host, read, (attempt.received - prefix.size) * 1_000L / elapsedMs, firstByteMs)
                            }
                            if (input.read() != -1) throw IOException("CDN oversized range")
                        }
                    }
                    success = true
                } catch (error: Exception) {
                    // Invalid/oversized bodies must not be carried into a resumed request.
                    if (attempt.received == attempt.bytes.size) attempt.received = 0
                    failure = error
                } finally {
                    end(call, success, call.isCanceled())
                }
                // Release the shared slot before the loader schedules another window.
                if (success) attempt.result.complete(attempt.bytes)
                else attempt.result.completeExceptionally(failure ?: IOException("CDN range failed"))
            }

            private fun end(call: Call, success: Boolean, canceled: Boolean) {
                calls -= call
                CdnTransferRuntime.permits.release()
                CdnTransferRuntime.finish(host, success, attempt.status, canceled)
                // 并行分片里真正被用的那条是 attempt.url（不是 dataSpec 的 URI）
                // 并行分片是**竞速**的：输掉的那几片会被 cancel —— 那是"没赢"，不是"线路坏"。
                // 所以只在 (成功 且 未取消) 时记账；失败交由内层 CdnFailoverDataSource 上报
                // （它才是"真的开不起来"的地方，两层不重复计）。
                if (success && !canceled) {
                    attempt.url.takeIf { it.isNotBlank() }?.let { used ->
                        lineOutcomeListener.onOutcome(used, true)
                    }
                }
            }
        })
        if (closed) call.cancel()
        return attempt
    }

    private fun cancelCalls() { calls.forEach { it.cancel() } }

    /**
     * 降级后的**有界恢复**：满足 [CdnConnectionPolicy.shouldRestoreParallel] 时把并行打开。
     *
     * 只在单连接读到字节之后才检查（即"单连接确实在工作"这一手证据），不是每个 read 都查。
     * 重升时先关掉内层 delegate、归还它的记账，下一个 read 会按并行窗口从 `consumed` 继续。
     */
    private fun maybeRestoreParallel() {
        if (parallel || !parallelEligible || closed) return
        // **重升次数上限**（评审 A3）：只有 15s 冷却时，"重升 → 窗口 12s 超时 → 再降级"
        // 在弱网下会无界 churn（每 ~27s 一段"零字节交付"），比一直单连接还差。
        // 一个 open 内最多重升 2 次：够覆盖"一次抖动"，又不会变成周期性卡顿源。
        if (parallelRestoreCount >= MAX_PARALLEL_RESTORES) return
        val bufferMs = CdnTransferRuntime.state.value.bufferMs
        if (!CdnConnectionPolicy.shouldRestoreParallel(
                nowMs = nowMs(),
                disabledAtMs = parallelDisabledAtMs,
                bufferMs = bufferMs,
                parallelAllowed = CdnTransferRuntime.parallelEnabled,
                wifi = isWifi()
            )
        ) {
            return
        }
        runCatching { if (delegateOpen) upstream.close() }
        delegateOpen = false
        // **这不叫"成功"**（评审 A7）：delegate 是在正常供数中被我们主动掐断的，
        // 记成功会给线路健康度白加分、甚至掩盖坏线路。按"取消"记账。
        finishDelegate(success = false, canceled = true)
        parallel = true
        parallelDisabledAtMs = 0L
        parallelRestoreCount += 1
        CdnTransferRuntime.noteParallelSession(true)
        CdnTransferRuntime.noteWindowResult("parallel-restored($parallelRestoreCount) buf=${bufferMs}ms")
        com.naaammme.bbspace.core.common.log.DiagnosticLog.i("CdnParallel") {
            "restored parallel after fallback (#$parallelRestoreCount) buf=${bufferMs}ms pos=$consumed"
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    companion object {
        /** 一个数据源实例（≈一次 open）内允许的"降级 → 重升"次数上限（评审 A3）。 */
        private const val MAX_PARALLEL_RESTORES = 2
        // A shared dispatcher and connection pool; no per-player executor or hidden scope.
    }
}
