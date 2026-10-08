package com.naaammme.bbspace.infra.player

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.naaammme.bbspace.core.common.cache.PlaybackCacheControl
import com.naaammme.bbspace.core.common.cache.PlaybackHeadPrewarmer
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.infra.player.cdn.CdnPlaybackDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 播放媒体**字节缓存**（PB-03，语义移植自上游 BiliPai `core/player/PlaybackMediaCache.kt`）。
 *
 * ## 为什么需要它
 * 播放过的字节跨会话复用：回看同一支视频、拖动回退、重进详情页都不再重新下载。
 * bbpace 此前只有**播放地址**的 90s 内存 LRU（`PlayUrlCache`），没有任何**字节**缓存。
 *
 * ## 三个必须守住的点（任务书 §3 PB-03 前置条件）
 * 1. **插入位置在最外层**：`CacheDataSource` 必须包在 `CdnFailoverDataSource` **之外**。
 *    换线的 URI 改写发生在 `CdnFailoverDataSource` 里，而缓存键取的是 `open()` 收到的
 *    DataSpec —— 套在内侧会让"备用线路"用另一把键存同一分片（重复下载 + 换线命中率归零）。
 * 2. **自定义键、丢掉 host/scheme/query**：bbpace 有两个换线维度（故障切换 + 用户切
 *    `VideoCdnMode` 的 20+ 镜像 host），键里含 host 就等于"换线即缓存全废"。
 * 3. **单例 + 懒建 + 工作线程**：`SimpleCache` 有静态目录锁，同目录第二个实例直接抛
 *    `IllegalStateException`；构造器带 `@WorkerThread`（DI 图在 `Application.onCreate` 主线程，
 *    debug 还开着 StrictMode 的磁盘检查）→ 必须懒建并落在 IO。
 *
 * 另外：**不在内存 trim 里清磁盘缓存**（`BiliApplication` 明文"不清磁盘缓存"，
 * `MemoryTrimPolicy` 是内存级语义），清理入口只在设置页。
 */
@UnstableApi
@Singleton
class PlaybackMediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
    /** PB-04：预热要用与播放同一条上行链（同一个 OkHttp 客户端）。 */
    private val okHttpClient: OkHttpClient,
    /**
     * 预取下载的**真实结果**也要入账（2026-10-07 缓存审查）：
     * 预取与播放走同一个 CDN 传输层、同一条候选线路，它的失败同样是"这条线没通"的证据。
     * 之前这条链没传监听器 ⇒ 预取的成功/失败只进进程内 `rank()` 统计，不进持久健康度（不对称）。
     */
    private val lineOutcomeListener: CdnLineOutcomeListener = CdnLineOutcomeListener.NoOp
) : PlaybackCacheControl, PlaybackHeadPrewarmer {

    @Volatile
    private var cache: SimpleCache? = null

    private val mutex = Mutex()

    /**
     * 是否有**正在使用**的媒体源（由 [Media3PlayerEngine] 在 setSource / 停止时维护）。
     *
     * 清理磁盘时必须先看它：抽掉正在读的文件会让播放中断甚至崩。这里用"引擎自己上报"
     * 而不是让设置页去读 `PlayerEngine.currentSource` —— 缓存与引擎在同一个模块内，
     * 反过来注入 `PlayerEngine` 会构成 Hilt 循环依赖（引擎依赖缓存）。
     */
    @Volatile
    var isSourceActive: Boolean = false
        private set

    /** 由引擎在 `setSource` 成功进入 / 停止与释放时调用。 */
    fun markSourceActive(active: Boolean) {
        isSourceActive = active
    }

    /** 懒建单例缓存（首次调用落在 IO；之后直接返回）。 */
    suspend fun awaitCache(): SimpleCache {
        cache?.let { return it }
        return mutex.withLock {
            cache?.let { return@withLock it }
            val built = withContext(Dispatchers.IO) { buildCache() }
            cache = built
            built
        }
    }

    @WorkerThread
    private fun buildCache(): SimpleCache {
        val dir = File(context.cacheDir, DIR_NAME)
        Logger.d(TAG) { "init playback media cache dir=${dir.absolutePath} maxBytes=$MAX_BYTES" }
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES))
    }

    /**
     * 把播放数据源链包上一层字节缓存（**必须传最外层的换线工厂**，见类注释第 1 条）。
     *
     * 不设 `FLAG_BLOCK_ON_CACHE`：预取写入与起播读取会并发，阻塞会让起播等下完。
     * 设 `FLAG_IGNORE_CACHE_ON_ERROR`：缓存自身出错（索引损坏等）时退回纯网络，
     * **绝不因为缓存把播放搞挂**。
     */
    suspend fun wrapForPlayback(upstream: DataSource.Factory): DataSource.Factory {
        val simpleCache = awaitCache()
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(PlaybackCacheKeyFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 当前磁盘占用（设置页展示用）。 */
    override suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) {
        cache?.cacheSpace ?: runCatching { File(context.cacheDir, DIR_NAME).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
    }

    /**
     * 清空缓存。**播放中拒绝**（返回 false，由调用方给出提示）。
     *
     * 实现：把 `SimpleCache` 实例放掉（`release()` 会关掉索引）再递归删目录 ——
     * 只删文件不 release 会让索引指向不存在的文件。
     */
    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        if (isSourceActive) {
            Logger.d(TAG) { "clear playback media cache refused: source active" }
            return@withContext false
        }
        mutex.withLock {
            val releasing = cache
            cache = null
            runCatching { releasing?.release() }
                .onFailure { Logger.w(TAG) { "release playback cache failed: ${it.message}" } }
            val dir = File(context.cacheDir, DIR_NAME)
            val removed = runCatching { dir.deleteRecursively() }
                .onFailure { Logger.w(TAG) { "delete playback cache dir failed: ${it.message}" } }
                .getOrDefault(false)
            Logger.d(TAG) { "playback media cache cleared removed=$removed" }
            true
        }
    }

    // ===== PB-04：起播头部预热 =====

    /**
     * 把视频/音频的头部若干字节先读进缓存（"真正开播前"少等一段网络）。
     *
     * 实现方式是**通过缓存链做一次有界读取**（读多少 = 预热多少，读完即弃）——
     * 缓存本身就在读路径上写入，所以不需要额外的 CacheWriter：
     * 用 `CacheWriter` 也行，但它的契约（要求上游是 CacheDataSource 实例）在没有真机的情况下
     * 无法验证，而有界读取只用 DataSource 的标准 API，行为可预期。
     *
     * 失败一律吞掉（尽力而为）；蜂窝下 [PlaybackHeadPrewarmPolicy] 直接返回空目标表，不下载。
     */
    override suspend fun prewarm(videoUrl: String?, audioUrl: String?, isUnmetered: Boolean) =
        withContext(Dispatchers.IO) {
            val targets = PlaybackHeadPrewarmPolicy.targets(videoUrl, audioUrl, isUnmetered)
            if (targets.isEmpty()) return@withContext
            targets.forEach { target ->
                runCatchingCancellable { readHeadIntoCache(target) }
                    .onFailure { Logger.d(TAG) { "prewarm failed url=${target.url.take(64)} msg=${it.message}" } }
            }
            Logger.d(TAG) { "prewarm done targets=${targets.size} unmetered=$isUnmetered" }
        }

    /** 有界读取：读满 [PlaybackHeadPrewarmPolicy.PrewarmTarget.lengthBytes] 或流结束即停。 */
    private suspend fun readHeadIntoCache(target: PlaybackHeadPrewarmPolicy.PrewarmTarget) {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(awaitCache())
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .setCacheKeyFactory(PlaybackCacheKeyFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSource = cacheFactory.createDataSource()
        try {
            val spec = DataSpec(Uri.parse(target.url), 0L, target.lengthBytes)
            dataSource.open(spec)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var read = 0L
            while (read < target.lengthBytes) {
                val n = dataSource.read(buffer, 0, buffer.size)
                if (n < 0) break
                read += n
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    // ===== P0-3：让 DASH 索引段也走这条缓存 =====

    /**
     * 经字节缓存读 [url] 的前 [lengthBytes] 字节并**返回读到的字节**。
     *
     * 用途：`SingleFileDashMediaSourceFactory.readHttpIndex` —— 起播前拉的那段
     * `Range: bytes=0-91535` 索引（本地解析 `sidx` 得到分包起点/长度）此前走的是
     * **裸 `okHttpClient`**，绕开了本缓存，于是**跨会话必重发**（同会话靠
     * `PrefetchedRange` 复用，进程重启/清缓存后就不复用了）。
     *
     * 缓存键仍由 [PlaybackCacheKeyFactory] 决定：`media:<path>`，**剥掉 host 与 query**
     * ——所以换 CDN 镜像、签名轮换都不会把这片字节作废。
     *
     * ⚠️ 这里的链条构造与 [readHeadIntoCache] 的写法**刻意保持一致**（同
     * `setCache(awaitCache())` + 同 `PlaybackCacheKeyFactory()` + 同旗标）：
     * `PlaybackHeadPrewarmWiringGuardTest.prewarmWritesIntoTheSameCache` 会逐字校验
     * 预热那一处的链条，两条链必须落在同一份 `SimpleCache` 上，否则写进去也命中不到。
     *
     * 失败一律**返回 null 而不抛**：索引读不到时调用方 `buildDashStream` 会 `error(...)`
     * 走既有的失败路径，与改造前 `!resp.isSuccessful → null` 的语义一致；
     * 而缓存自身出错由 `FLAG_IGNORE_CACHE_ON_ERROR` 兜底退回纯网络。
     */
    /**
     * 预取一段区间进缓存（逐字移植自 BiliPai `PlaybackMediaCache.kt:110-150`）。
     *
     * 与源仓库的两处必要差异：
     * 1. 源是 `object` + 每次传 `context`；本仓是注入的实例，缓存自己持有上下文；
     * 2. 源把并行数据源写成 `background = true` —— 这一位就是"预取让路给播放"的开关
     *    （见 `CdnPlaybackDataSource.downloadWindow` 里的 `CDN_PREFETCH_SAFE_BUFFER_MS` 判断），
     *    所以**必须**保留，否则预取会和播放抢带宽。
     */
    @androidx.media3.common.util.UnstableApi
    suspend fun prefetchRange(
        upstreamFactory: androidx.media3.datasource.DataSource.Factory,
        url: android.net.Uri,
        cacheKey: String,
        position: Long,
        length: Long
    ) {
        if (length <= 0L) return
        val cache = awaitCache()
        // 源仓库同款：预取是 **caller-owned、可取消** 的操作 —— 取消时要能立刻打断在途传输
        // （跳转/缓冲变薄/物色关掉设置都会取消它）。bbspace 首次移植时漏了下面这段
        // `writer.cancel() + cancelPendingRequests()`，预取一旦开跑就必须读完整段才停。
        val activeUpstream = AtomicReference<CdnPlaybackDataSource?>(null)
        val upstreamOfPrefetch = androidx.media3.datasource.DataSource.Factory {
            CdnPlaybackDataSource(
                upstream = upstreamFactory.createDataSource(),
                appContext = context,
                background = true,
                // **07 复核报告 N4**：这里不传 `client` 时，`CdnPlaybackDataSource.rangeClient`
                // 的 `requireNotNull` 会在"将来某次改动让预取走到并行分支"时抛
                // IllegalArgumentException —— 而 `read()` 只 catch IOException，非 IO 异常会
                // 直接冒到 media3。补 `client` 是底线。
                client = okHttpClient,
                // 预取的成败同样入账（见构造函数注释）。
                lineOutcomeListener = lineOutcomeListener
            ).also { activeUpstream.set(it) }
        }
        val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamOfPrefetch)
            .setCacheKeyFactory(PlaybackCacheKeyFactory())
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSourceForDownloading()
        val writer = androidx.media3.datasource.cache.CacheWriter(
            cacheDataSource,
            androidx.media3.datasource.DataSpec.Builder()
                .setUri(url)
                .setKey(cacheKey)
                .setPosition(position)
                .setLength(length)
                .build(),
            null,
            null
        )
        kotlinx.coroutines.coroutineScope {
            val reader = async(Dispatchers.IO) { writer.cache() }
            try {
                reader.await()
            } finally {
                writer.cancel()
                // CacheWriter 自己负责 CacheDataSource.close()；这里只打断它的在途传输。
                activeUpstream.get()?.cancelPendingRequests()
            }
        }
    }

    suspend fun readPrefixThroughCache(
        url: String,
        lengthBytes: Long,
        upstreamFactory: DataSource.Factory
    ): PrefixRead? = withContext(Dispatchers.IO) {
        if (lengthBytes <= 0L || lengthBytes > MAX_PREFIX_BYTES) return@withContext null
        val uri = Uri.parse(url)
        val cacheKey = playbackCacheKeyForPath(uri.path, url)
        val cache = awaitCache()
        // 命中/未命中留痕（`DiagnosticLog`：debug 包始终可见，量产包在
        // 「设置 → 性能 → 现场诊断日志」打开后可见，关闭时**连字符串都不拼**）。
        // 为什么值得留：P0-3 的收益就是"跨会话不再重发索引"，而这件事**只能**从
        // "这条日志是 HIT 还是 MISS"看出来 —— 网络层观测到的是"没发请求"，
        // 与"根本没读索引"外观完全相同（本轮实测踩过这个坑）。
        val cached = runCatching { cache.isCached(cacheKey, 0L, lengthBytes) }.getOrDefault(false)
        DiagnosticLog.i(TAG) {
            "index cache=${if (cached) "HIT" else "MISS"} len=$lengthBytes key=${cacheKey.takeLast(40)}"
        }
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(PlaybackCacheKeyFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        try {
            val buffer = ByteArray(lengthBytes.toInt())
            dataSource.open(DataSpec(uri, 0L, lengthBytes))
            var total = 0
            while (total < buffer.size) {
                val n = dataSource.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            if (total <= 0) null else PrefixRead(buffer.copyOf(total), fromCache = cached)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // **诊断必须 release 可见**（2026-10-06 复盘）：这里原来是 `Logger.d`（仅 debug），
// 于是量产包上"为什么读不到索引"永远查不到 —— 用户只看到「单文件 dash 索引缺失」，
// 真实原因（HTTP 状态码 / 超时 / 签名失效）被静默丢弃。改用 DiagnosticLog（release 可开关）。
            DiagnosticLog.w(TAG) {
                "readPrefixThroughCache FAILED url=${url.take(96)} " +
                    "type=${t.javaClass.simpleName} msg=${t.message}"
            }
            null
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /**
     * 前缀读的结果：字节 + **是否完全命中缓存**。
     *
     * 为什么要带 `fromCache`：调用方（起播索引读）会把"这次读通了/读不通"回传给健康度，
     * 而**缓存命中的字节与"这条 host 通不通"无关** —— 不带这个标记就会给某条线白记成功
     * 或白记失败（换线后尤其明显）。
     */
    class PrefixRead(val bytes: ByteArray, val fromCache: Boolean)

    private companion object {
        const val TAG = "PlaybackMediaCache"

        /** 预热读取缓冲（64KiB：一次读满一个常见分片头）。 */
        const val READ_BUFFER_BYTES = 64 * 1024
        /** 有界前缀读取的上限（防御：调用方传入离谱长度时直接拒绝）。 */
        const val MAX_PREFIX_BYTES = 4L * 1024L * 1024L
        /** 与上游同量级：128MiB（上游注释记录 512MB 曾涨到 500MB+ 被下调）。 */
        const val MAX_BYTES = 128L * 1024L * 1024L

        const val DIR_NAME = "playback_media_cache"
    }
}

/**
 * 缓存键策略（PB-03 前置条件 2）：`media:<path>` —— **丢掉 scheme / host / query**。
 *
 * - **丢 host**：换线（故障切换 + 用户切 CDN 模式）后仍要命中同一份字节。
 *   B 站 `upgcxcode` 的 path 是内容寻址的（含 av 号与流 id），换 host 不换 path。
 * - **丢 query**：query 是签名与时效（会变），带上就永远不命中。
 *
 * ⚠️ **"哪些源进缓存"不在这里判**：media3 1.11 的 `CacheKeyFactory.buildCacheKey`
 * 返回**非空** `String`，没有"返回 null 即绕过"这条路 → 闸门落在
 * [PlaybackCacheGate]（决定包不包 `CacheDataSource`）。这里只负责"键长什么样"。
 */
@UnstableApi
internal class PlaybackCacheKeyFactory : CacheKeyFactory {

    override fun buildCacheKey(dataSpec: DataSpec): String =
        playbackCacheKeyForPath(dataSpec.uri.path, dataSpec.uri.toString())
}

/**
 * 缓存键的**纯内核**（JVM 可测，不依赖 `android.net.Uri`）。
 *
 * 单独抽出来的理由与 `PlaybackResumePolicy` 同：`DataSpec` 的构造要 `android.net.Uri`，
 * 而它在 JVM 单测里是桩（调用即抛）。把"键长什么样"抽成纯字符串函数后，
 * 这条**最容易静默错**的判据（漏了 host 就换线失效）可以被直接断言。
 */
internal fun playbackCacheKeyForPath(path: String?, fallbackUri: String): String =
    if (path.isNullOrEmpty()) "media:$fallbackUri" else "media:$path"
