package com.naaammme.bbspace.infra.player

import com.naaammme.bbspace.core.common.log.DiagnosticLog

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import androidx.media3.exoplayer.source.MediaSource
import java.io.IOException
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.naaammme.bbspace.infra.player.cdn.CdnPlaybackDataSource
import com.naaammme.bbspace.infra.player.cdn.CdnTransferRuntime
import com.naaammme.bbspace.infra.player.cdn.hostFromCdnUrl
import okhttp3.OkHttpClient

/**
 * [SingleFileDashMediaSourceFactory.create] 的返回：媒体源 + **预取计划**。
 *
 * 计划必须跟着媒体源一起交出去：sidx 的绝对起点/长度是**建源时才解析出来**的
 * （`SingleFileDashIndexParser` 只吃头部字节），引擎要拿它调度
 * `CdnDashSegmentPrefetcher`（源仓库是从 playurl 的 `segmentBase.indexRange` 直接拿到）。
 */
internal data class SingleFileDashBuild(
    val mediaSource: MediaSource,
    val prefetch: CdnDashPrefetchPlan?,
    /**
     * 本次建源时**索引是不是真的走了网络**（至少一条轨道是 MISS）。
     *
     * 用途：给会话层记账提供"起播这一刻算不算网络服务"的证据（见
     * `CdnHealthPolicy.shouldRecordSessionEvent`）。索引 MISS ⇒ 这个视频的字节缓存是空的，
     * 起播那段必然走网络 ⇒ 起播时的 BUFFERING 可以算到线上；索引 HIT（复用缓存）⇒ 不算。
     * 没有它，"起播那一刻"永远没有网络证据（字节计数还没涨），起播卡顿信号会整个丢掉。
     */
    val indexFromNetwork: Boolean = false
)

@Suppress("UnsafeOptInUsageError")
@UnstableApi
internal class SingleFileDashMediaSourceFactory(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    /** PB-03：播放字节缓存（包在换线工厂之外，见 [PlaybackMediaCache] 类注释）。 */
    private val playbackMediaCache: PlaybackMediaCache,
    /**
     * 换线/索引读取的**真实结果**回传给选线层（闭环修复，2026-10-06）。
     * 默认不记账，便于无 DI 场景与单测。
     */
    private val lineOutcomeListener: CdnLineOutcomeListener = CdnLineOutcomeListener.NoOp,
    /** 「实际供数的是哪条 host」回传（会话层记账口径修正，2026-10-08）。 */
    private val servingHostListener: CdnServingHostListener = CdnServingHostListener.NoOp
) {

    suspend fun create(
        source: EngineSource.SingleFileDash,
        metadata: MediaMetadata,
        /** CDN 并行分片下载开关（设置项，源 `parallelDownloadEnabled`）。 */
        cdnParallel: Boolean = true
    ): SingleFileDashBuild {
        // 候选表（主线路 → 依次尝试的线路）**读写两侧共用**：既是运行时换线表，
        // 也是索引读取重试表（见 buildDashStream）。
        val baseCandidates = buildCdnCandidates(source)
        val videoCandidates = baseCandidates[source.videoUrl].orEmpty()
        val audioCandidates = source.audioUrl?.let { baseCandidates[it] }.orEmpty()
        // ---- C-1：把候选表喂给传输运行时（并行竞速/分片的前提）----
        // 源仓库由 CDN 插件在拿到 playurl 时 `register`；本仓在**同一语义点**注册：
        // 只有服务端原始签名 URL 会被登记，运行时自身还会校验"是否原始 URL + 10 分钟有效期"。
        // `parallel` 来自设置项（源 `CdnRegionPluginCache.parallelDownloadEnabled`）——
        // 关掉即 `parallelEnabled = false`，`CdnPlaybackDataSource.read()` 会当场降级成单连接
        // （抛 `CDN parallel mode disabled` → 退回 `openDelegate`），**不需要中断当前播放**。
        CdnTransferRuntime.configure(active = true, parallel = cdnParallel)
        CdnTransferRuntime.register(video = videoCandidates, audio = audioCandidates)
        val streams = coroutineScope {
            buildList {
                add(
                    async {
                        buildDashStream(
                            url = source.videoUrl,
                            candidates = videoCandidates,
                            sampleMimeType = source.videoSampleMimeType,
                            isAudio = false
                        )
                    }
                )
                source.audioUrl?.let { audioUrl ->
                    add(
                        async {
                            buildDashStream(
                                url = audioUrl,
                                candidates = audioCandidates,
                                sampleMimeType = source.audioSampleMimeType,
                                isAudio = true
                            )
                        }
                    )
                }
            }.awaitAll()
        }
        val dataSourceFactory = buildDataSourceFactory(
            source = source,
            prefetchedRanges = streams.mapNotNull { it.prefetchedRange }.associateBy(PrefetchedRange::url),
            candidatesByUrl = buildMap {
                putAll(baseCandidates)
                // 用另一条线路起播时，"主线路 URL → 候选"这张表查不到（键不对），
                // 所以为**实际读通索引的那条**补一条（它自己在首位）。
                fun register(working: String?, candidates: List<String>) {
                    if (working.isNullOrBlank() || working in baseCandidates) return
                    put(working, (listOf(working) + candidates.filter { it != working }).distinct())
                }
                register(streams.getOrNull(0)?.prefetchedRange?.url, videoCandidates)
                register(streams.getOrNull(1)?.prefetchedRange?.url, audioCandidates)
            }
        )
        val manifestDurationMs = source.durationMs
            ?.coerceAtLeast(1L)
            ?: error("single-file dash 时长无效")
        val adaptationSets = streams.mapIndexed { index, stream ->
            AdaptationSet(
                index.toLong(),
                if (stream.isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO,
                listOf(stream.representation),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            )
        }
        val manifest = DashManifest(
            C.TIME_UNSET,
            manifestDurationMs,
            0,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            null,
            null,
            null,
            null,
            listOf(Period(null, 0, adaptationSets))
        )
        val mediaSource = DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSourceFactory, DASH_MAX_SEGMENTS_PER_LOAD),
            dataSourceFactory
        ).createMediaSource(
            manifest,
            MediaItem.Builder()
                .setUri(source.videoUrl)
                .setMediaMetadata(metadata)
                .build()
        )
        // ---- C-3：DASH 分段预取的静态计划（源仓库同语义：`scheduleCdnDashPrefetch()` 用
        // `cachedDashVideos` 里当前码率的 video track + 它的 `segmentBase.indexRange`）----
        // 只做视频轨（源仓库同样只预取 video track）。计划的"何时跑"由引擎按缓冲决定。
        val prefetchPlan = streams.firstOrNull { !it.isAudio }?.let { video ->
            buildCdnDashPrefetchPlan(
                candidates = videoCandidates,
                indexStart = video.indexStart,
                indexLength = video.indexLength,
                fallbackUrl = source.videoUrl
            )
        }
        return SingleFileDashBuild(
            mediaSource = mediaSource,
            prefetch = prefetchPlan,
            // 至少一条轨道的索引走了网络 ⇒ 起播这段是网络服务（缓存命中不算）。
            indexFromNetwork = streams.any { !it.fromCache }
        )
    }

    private suspend fun buildDashStream(
        url: String,
        candidates: List<String>,
        sampleMimeType: String?,
        isAudio: Boolean
    ): DashStream {
        // **对齐 BiliPai 的 `CdnPlaybackDataSource`：索引读取也要多线路重试。**
        // 源仓库把 DataSource 整个换掉（自己按多线路重试读字节），索引永远拿得到；
        // 本仓只在运行时换线、索引却只试第一条 ⇒ 首条读不到就整支视频打不开
        // （用户实测：部分视频报「单文件 dash 索引缺失」）。
        val attempts = candidates.filter(String::isNotBlank).distinct().ifEmpty { listOf(url) }
        // 现场取证：服务端这次给了哪几条线（含次序）。排障第一问就是它；
        // 也是"健康度是否真的改变了主线路"的唯一可判定依据（配合下面的 `index ok host=`）。
        DiagnosticLog.i("SingleFileDash") {
            "index candidates track=${if (isAudio) "audio" else "video"} " +
                "hosts=${attempts.joinToString(",") { hostFromCdnUrl(it) }}"
        }
        var resolved: SingleFileDashIndexResult? = null
        var workingUrl = url
        for (candidate in attempts) {
            val result = readIndex(candidate)
            // 真实结果回传（索引读不通的线路必须被降权 —— 否则下次还会选它）。
            // ⚠️ **缓存命中的那次不算**：字节来自磁盘时，"通不通"与这条 host 无关，
            // 否则换线/换镜像后会按"选中的那条"白记成功（2026-10-07 缓存审查）。
            if (result == null || !result.fromCache) {
                lineOutcomeListener.onOutcome(candidate, result != null)
            }
            if (result == null) {
                DiagnosticLog.w("SingleFileDash") {
                    "index attempt FAILED host=${runCatching { java.net.URI(candidate).host }.getOrDefault("?")}"
                }
            } else {
                resolved = result
                workingUrl = candidate
                // **成功也要留痕**（与上面的 FAILED 成对）。为什么必须有这条：
                // ① 现场排障：用户说"某支视频慢/打不开"时，先要知道**实际用的是哪条线路**；
                // ② 闭环可证伪：健康度选出的第一条是不是真被用了 —— 没有这条日志，
                //    "坏线路被降权"在真机上无法验证（只能靠单测），而 2026-10-06 的
                //    真机核验正是靠它证明了"被扣分的 host 会被排到后面"。
                DiagnosticLog.i("SingleFileDash") {
                    "index ok host=${hostFromCdnUrl(candidate)} " +
                        "attempt=${attempts.indexOf(candidate) + 1}/${attempts.size} " +
                        "track=${if (isAudio) "audio" else "video"}"
                }
                break
            }
        }
        val indexResult = resolved
            ?: error("单文件 dash 索引缺失（已试 ${attempts.size} 条线路）: $url")
        val index = indexResult.index
        val format = Format.Builder()
            .setContainerMimeType(if (isAudio) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4)
            .setSampleMimeType(sampleMimeType)
            .build()
        return DashStream(
            isAudio = isAudio,
            prefetchedRange = indexResult.prefetchedRange,
            indexStart = index.indexStart,
            indexLength = index.indexLength,
            fromCache = indexResult.fromCache,
            representation = Representation.SingleSegmentRepresentation(
                0L,
                format,
                listOf(BaseUrl(workingUrl)),
                SegmentBase.SingleSegmentBase(
                    RangedUri(null, 0, index.initLength),
                    1,
                    0,
                    index.indexStart,
                    index.indexLength
                ),
                null,
                emptyList(),
                emptyList(),
                null,
                C.LENGTH_UNSET.toLong()
            )
        )
    }

    private suspend fun buildDataSourceFactory(
        source: EngineSource.SingleFileDash,
        prefetchedRanges: Map<String, PrefetchedRange>,
        candidatesByUrl: Map<String, List<String>>
    ): DataSource.Factory {
        val requestSpec = source.toPlaybackRequestSpec()
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(requestSpec.userAgent)
        if (requestSpec.headers.isNotEmpty()) {
            upstreamFactory.setDefaultRequestProperties(requestSpec.headers)
        }
        // PB-03：缓存必须在**最外层**（CdnFailoverDataSource 之外）。换线的 URI 改写发生在
        // 它内部，而缓存键取的是 open() 收到的 DataSpec —— 套在内侧时备用线路会用另一把键
        // 存同一分片（重复下载 + 换线命中率归零）。
        val failover = CdnFailoverDataSourceFactory(
            PrefetchedDataSourceFactory(
                upstreamFactory = DefaultDataSource.Factory(appContext, upstreamFactory),
                prefetchedRanges = prefetchedRanges
            ),
            candidatesByUrl,
            // 闭环修复：换线真实结果回传（这里才能知道"实际通了哪条"）
            lineOutcomeListener,
            // 会话层记账要知道"现在供数的是哪条 host"（换线后不再记到旧线头上）
            servingHostListener
        )
        // SingleFileDash 恒为 VOD DASH（闸门判据见 PlaybackCacheGate），直接包缓存；
        // 位置必须在 failover **之外**（见 PlaybackMediaCache 类注释第 1 条）。
        // ---- C-2：并行传输数据源（源 `CdnPlaybackDataSource`，371 行逐字移植）----
        // 层次与源仓库一致：**缓存在外、并行数据源在内**（源 `PlaybackMediaCache.kt:80`
        // 就是 `CdnPlaybackDataSource(upstreamFactory.createDataSource())` 这样包的）。
        // 它自己负责：多片并行竞速、只认精确 Range（206 + 边界一致）、已交付字节续传、
        // 卡住的分片换线路救援；任何不满足条件的情形都**回落到 upstream**（不影响老的可靠性）。
        val parallelAware = androidx.media3.datasource.DataSource.Factory {
            CdnPlaybackDataSource(
                upstream = failover.createDataSource(),
                appContext = appContext,
                client = okHttpClient,
                lineOutcomeListener = lineOutcomeListener,
                servingHostListener = servingHostListener
            )
        }
        return playbackMediaCache.wrapForPlayback(parallelAware)
    }

    /** 主线路 URL -> 打开失败时依次尝试的候选线路（首元素为主线路本身） */
    private fun buildCdnCandidates(source: EngineSource.SingleFileDash): Map<String, List<String>> {
        return buildMap {
            put(source.videoUrl, (listOf(source.videoUrl) + source.videoBackupUrls).distinct())
            source.audioUrl?.let { audioUrl ->
                put(audioUrl, (listOf(audioUrl) + source.audioBackupUrls).distinct())
            }
        }
    }

    private suspend fun readIndex(url: String): SingleFileDashIndexResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        return when {
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) -> {
                if (!path.endsWith(".m4s", ignoreCase = true)) return null
                readHttpIndex(url)
            }

            else -> null
        }
    }

    /**
     * 起播前拉索引段（`Range: bytes=0-91535`），**经播放字节缓存**读取。
     *
     * P0-3 改造前这里走裸 `okHttpClient.newCall(...)`：绕开 [PlaybackMediaCache]，
     * 于是**跨会话必重发**（同会话靠 [PrefetchedRange] 复用，进程重启/清缓存后不复用）。
     * 改走缓存后：跨会话命中同一片字节（键剥 host/query，见 [PlaybackCacheKeyFactory]）。
     *
     * `Range` 头不再手写——`DataSpec(position=0, length=N)` 会由 media3 的数据源
     * 生成等价的 `Range: bytes=0-(N-1)`。
     */
    private suspend fun readHttpIndex(url: String): SingleFileDashIndexResult? {
        val requestSpec = EngineSource.SingleFileDash(videoUrl = url).toPlaybackRequestSpec()
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(requestSpec.userAgent)
        if (requestSpec.headers.isNotEmpty()) {
            upstreamFactory.setDefaultRequestProperties(requestSpec.headers)
        }
        val prefix = playbackMediaCache.readPrefixThroughCache(
            url = url,
            lengthBytes = DASH_PREFETCH_MAX_BYTES.toLong(),
            upstreamFactory = upstreamFactory
        ) ?: return null
        val data = prefix.bytes
        val index = SingleFileDashIndexParser.parse(data)
        if (index == null) {
            DiagnosticLog.w("SingleFileDash") {
                "index parse FAILED bytes=${data.size} url=${url.take(96)}"
            }
            return null
        }
        return SingleFileDashIndexResult(
            index = index,
            prefetchedRange = PrefetchedRange(
                url = url,
                data = data
            ),
            // 缓存命中的字节与"这条 host 通不通"无关 ⇒ 回传时不能算到线路上（见 buildDashStream）。
            fromCache = prefix.fromCache
        )
    }

    private data class SingleFileDashIndexResult(
        val index: SingleFileDashIndex,
        val prefetchedRange: PrefetchedRange,
        val fromCache: Boolean
    )

    private class PrefetchedRange(
        val url: String,
        val data: ByteArray
    ) {
        val endExclusive: Long
            get() = data.size.toLong()
    }

    private data class DashStream(
        val isAudio: Boolean,
        val prefetchedRange: PrefetchedRange?,
        /** sidx 盒在文件里的**绝对**起点与长度（预取器要按同一区间再读一次索引）。 */
        val indexStart: Long,
        val indexLength: Long,
        val representation: Representation.SingleSegmentRepresentation,
        /** 这条轨道的索引**是否命中缓存**（false = 真的走了网络）。见 [SingleFileDashBuild.indexFromNetwork]。 */
        val fromCache: Boolean = false
    )

    private class PrefetchedDataSourceFactory(
        private val upstreamFactory: DataSource.Factory,
        private val prefetchedRanges: Map<String, PrefetchedRange>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return PrefetchedDataSource(
                upstream = upstreamFactory.createDataSource(),
                prefetchedRanges = prefetchedRanges
            )
        }
    }

    private class PrefetchedDataSource(
        private val upstream: DataSource,
        private val prefetchedRanges: Map<String, PrefetchedRange>
    ) : DataSource {
        private var openedFromPrefetch = false
        private var currentUri: Uri? = null
        private var currentData: ByteArray? = null
        private var readPosition = 0
        private var bytesRemaining = 0

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val prefetched = prefetchedRanges[dataSpec.uri.toString()]
            if (prefetched != null && canServeFromPrefetch(dataSpec, prefetched)) {
                openedFromPrefetch = true
                currentUri = dataSpec.uri
                currentData = prefetched.data
                readPosition = dataSpec.position.toInt()
                bytesRemaining = resolveLength(dataSpec, prefetched).toInt()
                return bytesRemaining.toLong()
            }
            openedFromPrefetch = false
            currentUri = null
            currentData = null
            readPosition = 0
            bytesRemaining = 0
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!openedFromPrefetch) {
                return upstream.read(buffer, offset, length)
            }
            if (length == 0) {
                return 0
            }
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT
            }
            val data = currentData ?: error("PrefetchedDataSource 预取状态异常")
            val bytesToRead = minOf(length, bytesRemaining)
            System.arraycopy(data, readPosition, buffer, offset, bytesToRead)
            readPosition += bytesToRead
            bytesRemaining -= bytesToRead
            return bytesToRead
        }

        override fun getUri(): Uri? {
            return if (openedFromPrefetch) currentUri else upstream.uri
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            return if (openedFromPrefetch) emptyMap() else upstream.responseHeaders
        }

        override fun close() {
            if (openedFromPrefetch) {
                openedFromPrefetch = false
                currentUri = null
                currentData = null
                readPosition = 0
                bytesRemaining = 0
                return
            }
            upstream.close()
        }

        private fun canServeFromPrefetch(
            dataSpec: DataSpec,
            prefetched: PrefetchedRange
        ): Boolean {
            if (dataSpec.position < 0 || dataSpec.position >= prefetched.endExclusive) {
                return false
            }
            val requestedEndExclusive = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                prefetched.endExclusive
            } else {
                dataSpec.position + dataSpec.length
            }
            return requestedEndExclusive <= prefetched.endExclusive
        }

        private fun resolveLength(
            dataSpec: DataSpec,
            prefetched: PrefetchedRange
        ): Long {
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                prefetched.endExclusive - dataSpec.position
            } else {
                dataSpec.length
            }
        }
    }

    internal companion object {
        /**
         * 索引前缀读取长度：起播读索引、以及**预取的索引读**都用它
         * （预取改读"前缀"而非"只读 sidx 段"的原因见 `CdnDashSegmentPrefetcher.loadIndex`）。
         */
        const val DASH_PREFETCH_MAX_BYTES = 91_536

        const val DASH_MAX_SEGMENTS_PER_LOAD = 4
    }
}
