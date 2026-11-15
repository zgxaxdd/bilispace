package com.naaammme.bbspace.core.playback.repository

import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.playershared.CodeType
import com.bapis.bilibili.playershared.DashItem
import com.bapis.bilibili.playershared.DolbyItem
import com.bapis.bilibili.playershared.DashVideo
import com.bapis.bilibili.playershared.PlayCtrl
import com.bapis.bilibili.playershared.ResponseUrl
import com.bapis.bilibili.playershared.Stream
import com.bapis.bilibili.playershared.StreamInfo
import com.bapis.bilibili.playershared.VideoVod
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.cache.OnDemandRequestCache
import com.naaammme.bbspace.core.common.cache.PlaybackHeadPrewarmer
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.network.NetworkTypeProvider
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.VideoPlayerRepository
import com.naaammme.bbspace.core.model.PlaybackAudio
import com.naaammme.bbspace.core.model.PlaybackControlMode
import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackStream
import com.naaammme.bbspace.core.model.ProgressiveSegment
import com.naaammme.bbspace.core.model.QualityOption
import com.naaammme.bbspace.core.model.StreamLimitInfo
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.infra.grpc.BiliGrpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoPlayerRepositoryImpl @Inject constructor(
    private val grpcClient: BiliGrpcClient,
    private val appSettings: AppSettings,
    private val authProvider: AuthProvider,
    private val webPlaybackResolver: VideoWebPlaybackResolver,
    /** PB-04：头部字节预热（接口在 core:common，实现在 infra:player）。 */
    private val headPrewarmer: PlaybackHeadPrewarmer,
    /** PB-04 的门控输入：只有非计量网络才预热。 */
    private val networkTypeProvider: NetworkTypeProvider
) : VideoPlayerRepository {

    /** 播放地址进程内 LRU：正常开播与相邻页预取共用（见 [PlayUrlCache] 的 KDoc）。 */
    private val playUrlCache = PlayUrlCache({ android.os.SystemClock.elapsedRealtime() })

    /**
     * B12（2026-10-13，上游 v0.3.6 同步）：**在途取流合并**。
     *
     * ## 为什么需要它（本仓真实存在的重复请求）
     *
     * 竖屏连播有两条**独立发起**的取流路径：预取（`VideoViewModel.prefetchEntry()` →
     * `preloadPlayUrl`）与开播（用户滑到该页 → `fetchPlaybackSource`）。两者共用
     * [playUrlCache]，但缓存只在**请求成功之后**才写入 ⇒ "预取在途、用户已经滑到"
     * 这个窗口里两条路径都 cache miss，**同一个 key 发两次完整 gRPC 往返**
     * （而 `t1 建源` 在真机读数里占首帧的 ~72%，重复一次就是白等一个往返）。
     *
     * ## 为什么只包住**网络段**、不改动 [playUrlCache]
     *
     * `PlayUrlCache` 有自己的 TTL / LRU / 前缀失效语义，并且已被
     * `PlayUrlCacheTest`、`PlayUrlCacheInvalidationTest`、`RetryInvalidationGuardTest`
     * 钉住。这里把合并层放在它**下面**（只覆盖"真的要发请求"这一段），
     * 于是：缓存读写顺序、命中计数、失效行为全部不变，只有"并发同 key 会重复发请求"
     * 这一件事被修掉。TTL 取 0 ⇒ 这一层**不做结果缓存**（缓存仍归 `PlayUrlCache` 管），
     * 只做在途合并 —— 单一真值源。
     *
     * ## 等待者计数取消的语义（对本仓尤其重要）
     *
     * 预取协程随用户滑走而被取消时，若它是唯一等待者，这次在途请求会被**取消** ——
     * 正是我们想要的（用户已经不在等它了）。而当开播请求已经加入（等待者 ≥ 2）时，
     * 预取离开**不会**取消它，开播拿到的仍是同一次加载。
     *
     * ⚠️ `scope` 用自建 scope 而不是任何 UI 生命周期：它只承载"合并中的一次取流"，
     * 随进程存活；调用方（预取/开播）自己的取消通过等待者计数传导。
     */
    private val inFlightPlayUrl = OnDemandRequestCache<String, Result<PlaybackSource>>(
        maxEntries = 1,
        maxAgeMs = 0L, // 不做结果缓存（那是 PlayUrlCache 的职责）
        nowMs = { android.os.SystemClock.elapsedRealtime() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        // 失败永不进缓存（虽然 TTL=0 已使缓存失效，这里显式表达意图）
        canCache = { it.isSuccess },
    )

    override suspend fun fetchPlaybackSource(request: PlaybackRequest): PlaybackSource {
        val flavor = flavorOf()
        val cacheKey = PlayUrlCache.keyOf(request, flavor)
        playUrlCache.get(cacheKey)?.let { cached ->
            // 预取命中：省掉一整个 gRPC 往返（竖屏连播的起播速度主要来自这里）
            PlayUrlCacheMetrics.record(hit = true)
            Logger.d(TAG) { "playurl cache hit aid=${request.ids.aid} cid=${request.ids.cid}" }
            return cached
        }
        PlayUrlCacheMetrics.record(hit = false)
        Logger.d(TAG) { "playurl cache miss aid=${request.ids.aid} cid=${request.ids.cid}" }
        // B12：合并"同 key 的并发取流"。`Result` 包一层是因为 OnDemandRequestCache
        // 需要"成功/失败"作为值来判定 canCache；展开在下面。
        val source = inFlightPlayUrl
            .getOrLoad(cacheKey) { runCatchingCancellable { loadPlaybackSource(request) } }
            .getOrThrow()
        playUrlCache.put(cacheKey, source)
        return source
    }

    /** 真正的取流（不含缓存与合并）—— B12 抽出来是为了让合并层只包住这一段。 */
    private suspend fun loadPlaybackSource(request: PlaybackRequest): PlaybackSource {
        return if (shouldUseWebPlayback(request)) {
            webPlaybackResolver.fetchPlaybackSource(request)
        } else {
            val reply = withContext(Dispatchers.IO) {
                val req = buildRequest(request)
                grpcClient.call(
                    endpoint = ENDPOINT,
                    requestBytes = req.toByteArray(),
                    parser = PlayViewUniteReply.parser()
                )
            }
            withContext(Dispatchers.Default) { mapReply(request, reply) }
        }
    }

    /**
     * 相邻页预取：与正常开播**共用同一份 LRU**（同键同 TTL），命中即直接播。
     * 失败一律吞掉（预取是尽力而为，绝不冒泡成播放错误）。
     *
     * PB-04：取流成功后就地**预热头部字节**（PB-03 的字节缓存）——接口返回类型不变，
     * 预热失败也只吞掉。门控（仅 Wi-Fi）在 [PlaybackHeadPrewarmer] 实现内部，
     * 这里只把网络类型传进去。
     */
    override suspend fun preloadPlayUrl(request: PlaybackRequest): Boolean {
        if (request.ids.aid <= 0L || request.ids.cid <= 0L) return false
        return runCatching { fetchPlaybackSource(request) }
            .onSuccess { source ->
                runCatchingCancellable {
                    headPrewarmer.prewarm(
                        videoUrl = source.prewarmVideoUrl(),
                        audioUrl = source.prewarmAudioUrl(),
                        isUnmetered = networkTypeProvider.isUnmetered()
                    )
                }
            }
            .onFailure { Logger.d(TAG) { "preload failed aid=${request.ids.aid} msg=${it.message}" } }
            .isSuccess
    }

    /** 预热用视频 URL：DASH 取第一条流的 videoUrl；progressive 取首段。 */
    private fun PlaybackSource.prewarmVideoUrl(): String? = when (val s = streams.firstOrNull()) {
        is PlaybackStream.Dash -> s.videoUrl
        is PlaybackStream.Progressive -> s.segments.firstOrNull()?.url
        null -> null
    }

    /** 预热用音频 URL：第一条音频轨。 */
    private fun PlaybackSource.prewarmAudioUrl(): String? = audios.firstOrNull()?.url

    /** 与 [buildRequest] 读的设置一一对应：它们变了，响应内容就变了，缓存键必须跟着变。 */
    private suspend fun flavorOf(): String = PlayUrlCache.flavorOf(
        enableHdrAnd8k = appSettings.enableHdrAnd8k.first(),
        preferredCodec = appSettings.preferredCodec.first(),
        needTrial = appSettings.needTrial.first(),
        forceHost = appSettings.forceHost.first()
    )

    private suspend fun shouldUseWebPlayback(request: PlaybackRequest): Boolean {
        return authProvider.accessToken.isBlank() &&
            appSettings.enableWebPlayback.first() &&
            request.ids.aid > 0L &&
            request.ids.cid > 0L
    }

    override fun invalidatePlayUrl(request: PlaybackRequest): Int {
        val removed = playUrlCache.invalidateByPrefix(PlayUrlCache.prefixOf(request))
        Logger.d(TAG) {
            "playurl invalidate aid=${request.ids.aid} removed=$removed（重试要拿到新签名 URL）"
        }
        return removed
    }

    override fun clearPlayUrlCache() {
        playUrlCache.clear()
    }

    private suspend fun buildRequest(request: PlaybackRequest): PlayViewUniteReq {
        val playable = request.playable
        val ids = playable.ids
        val enableHdrAnd8k = appSettings.enableHdrAnd8k.first()
        val needTrial = appSettings.needTrial.first()
        val preferredCodec = appSettings.preferredCodec.first()
        val forceHost = appSettings.forceHost.first()
        val fnval = if (enableHdrAnd8k) 4048 else 272

        val codecType = when (preferredCodec) {
            2 -> CodeType.CODE265
            3 -> CodeType.CODEAV1
            else -> CodeType.CODE264
        }

        val vod = VideoVod.newBuilder()
            .setAid(ids.aid)
            .setCid(ids.cid)
            .setQn(80)
            .setFnval(fnval)
            .setFnver(0)
            .setDownload(0)
            .setPreferCodecType(codecType)
            .setIsNeedTrial(needTrial)
            .setForceHost(forceHost)
            .build()

        val playCtrl = when (request.controlMode) {
            PlaybackControlMode.Simple -> PlayCtrl.PLAY_CTRL_SIMPLE
            PlaybackControlMode.Default -> PlayCtrl.PLAY_CTRL_DEFAULT
        }

        val builder = PlayViewUniteReq.newBuilder()
            .setVod(vod)
            .setSpmid(VideoTargetTool.SPMID)
            .setFromSpmid(playable.src.fromSpmid)
            .setFromScene(playable.fromScene)
            .setPlayCtrl(playCtrl)
            .putAllExtraContent(
                playable.getResolveExtraContent() + mapOf(
                    "short_edge" to SHORT_EDGE,
                    "long_edge" to LONG_EDGE
                )
            )

        ids.epId.takeIf { it > 0L }?.let { builder.putExtraContent("ep_id", it.toString()) }
        ids.seasonId.takeIf { it > 0L }?.let { builder.putExtraContent("season_id", it.toString()) }

        playable.adExtra
            ?.takeIf(String::isNotBlank)
            ?.let(builder::setAdExtra)

        ids.bvid
            ?.takeIf(String::isNotBlank)
            ?.let(builder::setBvid)

        return builder.build()
    }

    private fun mapReply(request: PlaybackRequest, reply: PlayViewUniteReply): PlaybackSource {
        val vodInfo = reply.vodInfo
        val biz = request.playable.biz.biz
        val audios = buildList {
            addAll(vodInfo.dashAudioList.map(::mapAudio))
            if (vodInfo.hasDolby() && vodInfo.dolby.type != DolbyItem.Type.NONE) {
                addAll(vodInfo.dolby.audioList.map(::mapAudio))
            }
            if (vodInfo.hasLossLessItem() && vodInfo.lossLessItem.isLosslessAudio) {
                add(mapAudio(vodInfo.lossLessItem.audio))
            }
        }
        val streams = vodInfo.streamListList.mapNotNull { mapStream(it, audios) }
        val options = vodInfo.qnPanel.qnItemsList.mapNotNull { item ->
            when {
                item.hasStreamInfo() -> mapQuality(item.streamInfo)
                item.hasQnGroup() -> item.qnGroup.streamInfosList.firstOrNull()?.let(::mapQuality)
                else -> null
            }
        }.ifEmpty {
            vodInfo.streamListList.map { mapQuality(it.streamInfo) }
        }

        // 打印完整响应体用于调试
        Logger.d(TAG) { "Response biz=$biz videos=${streams.size} audios=${audios.size} supplement=${reply.supplement.typeUrl}" }
        streams.forEach { stream ->
            Logger.d(TAG) { "Stream - Q: ${stream.quality}, Format: ${stream.format}, Desc: ${stream.description}, W: ${stream.width}, H: ${stream.height}" }
        }
        audios.forEach { audio ->
            Logger.d(TAG) { "Audio - ID: ${audio.id}, Bandwidth: ${audio.bandwidth}, MimeType: ${audio.mimeType}" }
        }

        return PlaybackSource(
            biz = biz,
            durationMs = if (reply.hasPlayArc() && reply.playArc.durationMs > 0) {
                reply.playArc.durationMs
            } else {
                vodInfo.timelength
            },
            streams = streams,
            audios = audios,
            qualityOptions = options.distinctBy(QualityOption::quality),
            resumePositionMs = if (reply.hasHistory() && reply.history.hasCurrentVideo()) {
                reply.history.currentVideo.progress
            } else {
                request.seekToMs
            },
            isPreview = reply.hasPlayArc() && reply.playArc.isPreview,
            supportProject = vodInfo.supportProject,
            supplementType = reply.supplement.typeUrl.takeIf { reply.hasSupplement() && it.isNotBlank() }
        )
    }

    private fun mapStream(stream: Stream, audios: List<PlaybackAudio>): PlaybackStream? {
        val info = stream.streamInfo
        return when {
            stream.hasDashVideo() -> mapDashStream(info, stream.dashVideo, audios)
            stream.hasSegmentVideo() -> mapProgressiveStream(
                info,
                stream.segmentVideo.segmentList.mapNotNull(::mapResponseUrl)
            )
            stream.hasMultiDashVideo() -> stream.multiDashVideo.dashVideosList.firstOrNull()?.let {
                mapDashStream(info, it, audios)
            }
            else -> null
        }
    }

    private fun mapDashStream(
        info: StreamInfo,
        dash: DashVideo,
        audios: List<PlaybackAudio>
    ): PlaybackStream? {
        if (dash.baseUrl.isBlank()) return null
        return PlaybackStream.Dash(
            quality = info.quality,
            format = info.format,
            description = info.description.ifBlank { info.displayDesc },
            width = dash.width,
            height = dash.height,
            mimeType = "video/mp4",
            needVip = info.needVip,
            needLogin = info.needLogin,
            supportDrm = info.supportDrm,
            videoUrl = dash.baseUrl,
            videoBackupUrls = dash.backupUrlList,
            audioId = dash.audioId.takeIf { it > 0 && audios.any { audio -> audio.id == it } },
            bandwidth = dash.bandwidth,
            codecId = dash.codecid,
            frameRate = dash.frameRate.takeIf(String::isNotBlank)
        )
    }

    private fun mapProgressiveStream(
        info: StreamInfo,
        segments: List<ProgressiveSegment>
    ): PlaybackStream? {
        if (segments.isEmpty()) return null
        return PlaybackStream.Progressive(
            quality = info.quality,
            format = info.format,
            description = info.description.ifBlank { info.displayDesc },
            width = null,
            height = null,
            mimeType = "video/mp4",
            needVip = info.needVip,
            needLogin = info.needLogin,
            supportDrm = info.supportDrm,
            segments = segments
        )
    }

    private fun mapAudio(item: DashItem): PlaybackAudio {
        return PlaybackAudio(
            id = item.id,
            url = item.baseUrl,
            backupUrls = item.backupUrlList,
            bandwidth = item.bandwidth,
            codecId = item.codecid,
            mimeType = "audio/mp4"
        )
    }

    private fun mapResponseUrl(item: ResponseUrl): ProgressiveSegment? {
        val url = item.url.takeIf(String::isNotBlank) ?: return null
        return ProgressiveSegment(
            url = url,
            durationMs = item.length.takeIf { it > 0 }
        )
    }

    private fun mapQuality(info: StreamInfo): QualityOption {
        return QualityOption(
            quality = info.quality,
            format = info.format,
            description = info.description.ifBlank { info.displayDesc },
            displayDescription = info.displayDesc,
            needVip = info.needVip,
            needLogin = info.needLogin,
            vipFree = info.vipFree,
            supportDrm = info.supportDrm,
            limit = if (info.hasLimit()) {
                StreamLimitInfo(
                    title = info.limit.title,
                    message = info.limit.msg,
                    uri = info.limit.uri
                )
            } else {
                null
            }
        )
    }

    private companion object {
        const val TAG = "PlayViewUnite"
        const val ENDPOINT = "bilibili.app.playerunite.v1.Player/PlayViewUnite"
        const val SHORT_EDGE = "1080"
        const val LONG_EDGE = "1920"
    }
}
