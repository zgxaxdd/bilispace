package com.naaammme.bbspace.core.download

import android.content.Context
import androidx.room.Room
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.community.service.dm.v1.DanmakuElem
import com.bapis.bilibili.community.service.dm.v1.DmSegCacheReq
import com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply
import com.bapis.bilibili.playershared.CodeType
import com.bapis.bilibili.playershared.DashItem
import com.bapis.bilibili.playershared.DashVideo
import com.bapis.bilibili.playershared.DolbyItem
import com.bapis.bilibili.playershared.PlayCtrl
import com.bapis.bilibili.playershared.Stream
import com.bapis.bilibili.playershared.VideoVod
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.UserAgentBuilder
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.model.DanmakuItem
import com.naaammme.bbspace.core.model.DownloadDanmakuCache
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.VideoDownloadEnqueueResult
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.core.model.VideoDownloadTask
import com.naaammme.bbspace.core.model.VideoDownloadTaskStatus
import com.naaammme.bbspace.infra.grpc.BiliGrpcClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class VideoDownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val grpcClient: BiliGrpcClient,
    private val okHttpClient: OkHttpClient,
    private val dao: VideoDownloadDao,
    private val playerSettings: AppSettings
) {

    val tasks: Flow<List<VideoDownloadTask>> = dao.observeAll()
        .map { list -> list.map(VideoDownloadEntity::toModel) }

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()
    @Volatile
    private var drainJob: Job? = null
    @Volatile
    private var runningTaskId: Long? = null
    @Volatile
    private var runningCall: Call? = null

    init {
        repoScope.launch {
            recoverRunningTasks()
            sweepOrphanTaskDirsSafely()
            ensureDrain()
        }
    }

    /**
     * 启动时清一次孤儿目录（ST-11）。**拿不到活的 id 集合就一个都不删** ——
     * "查询失败被当成没有任务"会把用户所有缓存视频抹掉，这个代价不对称，所以 fail closed。
     * 放在 `recoverRunningTasks` 之后、`ensureDrain` 之前：此时还没有任何 runTask 在写目录。
     */
    private suspend fun sweepOrphanTaskDirsSafely() {
        val ids = runCatchingCancellable { dao.allIds() }
            .onFailure { err -> Logger.w(TAG) { "orphan sweep skipped: id query failed ${err.message}" } }
            .getOrNull() ?: return
        val root = File(context.filesDir, ROOT_DIR_NAME)
        val removed = sweepOrphanTaskDirs(root, ids.mapTo(HashSet(ids.size)) { it.toString() })
        if (removed.isNotEmpty()) {
            Logger.w(TAG) { "orphan download dirs removed: count=${removed.size} names=${removed.joinToString()}" }
        }
    }

    suspend fun enqueue(request: VideoDownloadRequest): VideoDownloadEnqueueResult {
        val now = System.currentTimeMillis()
        val entity = request.toEntity(createdAtMs = now)
        val insertedId = dao.insertIgnore(entity)
        if (insertedId > 0L) {
            ensureDrain()
            return VideoDownloadEnqueueResult.Enqueued(insertedId)
        }
        val key = request.key()
        val existingId = dao.findExistingId(
            biz = key.biz,
            aid = key.aid,
            cid = key.cid,
            epId = key.epId,
            seasonId = key.seasonId,
            kind = request.kind.name
        ) ?: error("下载任务已存在但未找到记录")
        return VideoDownloadEnqueueResult.AlreadyExists(existingId)
    }

suspend fun pause(taskId: Long) {
        val task = dao.find(taskId) ?: return
        when (VideoDownloadTaskStatus.valueOf(task.status)) {
            VideoDownloadTaskStatus.WAITING -> {
                // 审查 D11：drain 抢占窗口内 DB 仍是 WAITING，"重读再写"挡不住 runTask
                // 随后的无条件 @Update(RUNNING) 覆写（后写者赢，暂停静默失效）。
                // runningTaskId 命中 = drain 已真实抢占：cancelAndJoin 让 runTask 必死，
                // 之后无论行停在 WAITING（死在写 RUNNING 之前）还是已 RUNNING 都落 PAUSED；
                // 终态（DONE/FAILED）不覆写。未命中才是纯 WAITING，直接写 PAUSED 无竞态
                //（drain 只在 drainMutex 内从 DB 查 WAITING，与本次写串行）。
                if (runningTaskId == taskId) {
                    stopRunningTask(taskId)
                    val latest = dao.find(taskId) ?: return
                    val latestStatus = VideoDownloadTaskStatus.valueOf(latest.status)
                    if (latestStatus == VideoDownloadTaskStatus.WAITING ||
                        latestStatus == VideoDownloadTaskStatus.RUNNING
                    ) {
                        dao.update(latest.pause())
                    }
                    ensureDrain()
                } else {
                    val latest = dao.find(taskId) ?: return
                    if (VideoDownloadTaskStatus.valueOf(latest.status) == VideoDownloadTaskStatus.WAITING) {
                        dao.update(latest.pause())
                    }
                }
            }

            VideoDownloadTaskStatus.RUNNING -> {
                stopRunningTask(taskId)
                val latest = dao.find(taskId) ?: return
                // stopRunningTask 未命中说明任务已自然完成或被 drain 切走；
                // 终态（DONE/FAILED）不能被覆写成 PAUSED（会清空 videoPath 导致重新整段下载）
                if (VideoDownloadTaskStatus.valueOf(latest.status) == VideoDownloadTaskStatus.RUNNING) {
                    dao.update(latest.pause())
                }
                ensureDrain()
            }

            else -> Unit
        }
    }

suspend fun resume(taskId: Long) {
        val task = dao.find(taskId) ?: return
        if (VideoDownloadTaskStatus.valueOf(task.status) != VideoDownloadTaskStatus.PAUSED) return
        dao.update(task.waiting())
        ensureDrain()
    }

suspend fun delete(taskId: Long) {
        val task = dao.find(taskId) ?: return
        // 审查 D1：不能只看 DB 状态——drain 在 `runningTaskId = task.id`（drain() :220）与
        // runTask 把状态写成 RUNNING（:234）之间存在 DB 仍为 WAITING 的窗口；此时跳过
        // stopRunningTask 直接删行删文件，runTask 会带着已删实体继续：mkdirs 重建目录、
        // 写完整段下载，而终态 dao.update 对已删行是静默 0 行更新——产出无 DB 记录的
        // 孤儿目录（数百 MB）且无任何清理机制。stopRunningTask 内部以 runningTaskId
        // 仲裁并 cancelAndJoin drain 协程，join 返回后 runTask 必已死亡，随后才删行删文件。
        if (VideoDownloadTaskStatus.valueOf(task.status) == VideoDownloadTaskStatus.RUNNING ||
            runningTaskId == taskId
        ) {
            stopRunningTask(taskId)
        }
        dao.delete(taskId)
        // 目录里可能有数百 MB 的视频/音频分片，递归删除必须切 IO，主线程删除会 ANR
        withContext(Dispatchers.IO) {
            deleteTaskFiles(taskId)
        }
        ensureDrain()
    }

    suspend fun getTask(taskId: Long): VideoDownloadTask? {
        return dao.find(taskId)?.toModel()
    }

suspend fun loadDanmaku(taskId: Long): DownloadDanmakuCache? {
        val task = dao.find(taskId) ?: return null
        val aid = task.aid.takeIf { it > 0L } ?: return null
        val cid = task.cid.takeIf { it > 0L } ?: return null
        val file = File(taskDir(taskId), DANMAKU_FILE_NAME)
        if (!file.exists()) {
            return DownloadDanmakuCache(
                aid = aid,
                cid = cid,
                items = emptyList()
            )
        }

        val reply = withContext(Dispatchers.IO) {
            DmSegMobileReply.parseFrom(file.readBytes())
        }
        val weightFilterLevel = playerSettings.state.first().danmaku.weightFilterLevel
        val items = withContext(Dispatchers.Default) {
            reply.elemsList.asSequence()
                .filter { elem -> elem.weight >= weightFilterLevel }
                .map(::mapDanmakuItem)
                .toList()
        }
        return DownloadDanmakuCache(
            aid = aid,
            cid = cid,
            items = items
        )
    }

    private suspend fun recoverRunningTasks() {
        dao.pauseRunningTasks(
            runningStatus = VideoDownloadTaskStatus.RUNNING.name,
            pausedStatus = VideoDownloadTaskStatus.PAUSED.name,
            message = "下载中断，已暂停"
        )
    }

    private fun ensureDrain() {
        if (drainJob?.isActive == true) return
        val job = repoScope.launch {
            drain()
        }
        drainJob = job
        job.invokeOnCompletion {
            if (drainJob === job) {
                drainJob = null
            }
        }
    }

    private suspend fun stopRunningTask(taskId: Long) {
        if (runningTaskId != taskId) return
        runningCall?.cancel()
        val job = drainJob ?: return
        if (job.isActive) {
            job.cancelAndJoin()
        }
    }

    private suspend fun drain() {
        drainMutex.withLock {
            while (true) {
                val task = dao.findFirstByStatus(VideoDownloadTaskStatus.WAITING.name) ?: break
                runningTaskId = task.id
                try {
                    runTask(task)
                } finally {
                    runningTaskId = null
                }
            }
        }
    }

    private suspend fun runTask(task: VideoDownloadEntity) {
        val pending = dao.find(task.id) ?: return
        if (pending.status != VideoDownloadTaskStatus.WAITING.name) return
        var cur = pending.prepareForRun()
        dao.update(cur)
        val dir = taskDir(task.id)
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                error("创建下载目录失败")
            }

            val source = fetchSource(cur.toRequest())
            val resolved = cur.withResolvedVideoId(
                aid = source.aid,
                cid = source.cid
            )
            if (resolved != cur) {
                cur = resolved
                dao.update(cur)
            }
            cacheDanmaku(
                task = cur,
                source = source,
                dir = dir
            )
            val stream = source.selectStream(cur.videoQuality)
            val audio = source.selectAudio(stream, cur.audioQuality)

            when (VideoDownloadKind.from(cur.kind)) {
                VideoDownloadKind.VIDEO -> {
                    val picked = stream ?: error("没有可下载视频流")
                    val cdn = buildCdns(picked, audio).firstOrNull() ?: error("下载地址无效")
                    val videoFile = File(dir, VIDEO_FILE_NAME)
                    downloadFile(cdn.videoUrl, videoFile) { done, total ->
                        cur = cur.downloading("视频", done, total)
                        dao.update(cur)
                    }
                    val audioFile = cdn.audioUrl?.let { audioUrl ->
                        File(dir, AUDIO_FILE_NAME).also { file ->
                            downloadFile(audioUrl, file) { done, total ->
                                cur = cur.downloading("音频", done, total)
                                dao.update(cur)
                            }
                        }
                    }
                    cur = cur.finish(
                        videoPath = videoFile.absolutePath,
                        audioPath = audioFile?.absolutePath,
                        durationMs = source.durationMs
                    )
                    dao.update(cur)
                }

                VideoDownloadKind.AUDIO -> {
                    val picked = audio ?: error("没有可下载音频流")
                    val audioUrl = picked.urls().firstOrNull() ?: error("没有可下载音频流")
                    val audioFile = File(dir, AUDIO_ONLY_FILE_NAME)
                    downloadFile(audioUrl, audioFile) { done, total ->
                        cur = cur.downloading("音频", done, total)
                        dao.update(cur)
                    }
                    cur = cur.finish(
                        videoPath = null,
                        audioPath = audioFile.absolutePath,
                        durationMs = source.durationMs
                    )
                    dao.update(cur)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            val latest = dao.find(task.id) ?: return
            dao.update(latest.pause("下载中断，已暂停"))
        } catch (e: Exception) {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            val latest = dao.find(task.id) ?: return
            dao.update(latest.fail(e.message ?: "下载失败"))
        }
    }

    private suspend fun fetchSource(request: VideoDownloadRequest): DownloadSource {
        val req = buildRequest(request)
        val reply = grpcClient.call(
            endpoint = ENDPOINT,
            requestBytes = req.toByteArray(),
            parser = PlayViewUniteReply.parser()
        )
        return mapReply(request, reply)
    }

    private fun buildRequest(request: VideoDownloadRequest): PlayViewUniteReq {
        val vod = VideoVod.newBuilder()
            .setAid(request.aid)
            .setCid(request.cid)
            .setQn(request.videoQuality.toLong())
            .setFnval(DOWNLOAD_FNVAL)
            .setFnver(0)
            .setDownload(0)
            .setPreferCodecType(CodeType.CODE265)
            .setIsNeedTrial(true)
            .build()
        val builder = PlayViewUniteReq.newBuilder()
            .setVod(vod)
            .setFromScene(FROM_SCENE)
            .setPlayCtrl(PlayCtrl.PLAY_CTRL_DEFAULT)
        request.extraContent().forEach { (key, value) ->
            builder.putExtraContent(key, value)
        }
        request.bvid
            ?.takeIf(String::isNotBlank)
            ?.let(builder::setBvid)
        return builder.build()
    }

    private fun mapReply(
        request: VideoDownloadRequest,
        reply: PlayViewUniteReply
    ): DownloadSource {
        val audios = buildList {
            addAll(reply.vodInfo.dashAudioList.map(::mapAudio))
            if (reply.vodInfo.hasDolby() && reply.vodInfo.dolby.type != DolbyItem.Type.NONE) {
                addAll(reply.vodInfo.dolby.audioList.map(::mapAudio))
            }
            if (reply.vodInfo.hasLossLessItem() && reply.vodInfo.lossLessItem.isLosslessAudio) {
                add(mapAudio(reply.vodInfo.lossLessItem.audio))
            }
        }
        return DownloadSource(
            aid = request.aid,
            cid = request.cid,
            durationMs = if (reply.hasPlayArc() && reply.playArc.durationMs > 0L) {
                reply.playArc.durationMs
            } else {
                reply.vodInfo.timelength
            },
            streams = reply.vodInfo.streamListList.mapNotNull { mapStream(it, audios) },
            audios = audios
        )
    }

    private fun mapStream(
        stream: Stream,
        audios: List<DownloadAudio>
    ): DownloadStream? {
        return when {
            stream.hasDashVideo() -> mapDash(stream.streamInfo.quality, stream.dashVideo, audios)
            stream.hasMultiDashVideo() -> (
                stream.multiDashVideo.dashVideosList.firstOrNull { it.codecid == CODECID_HEVC }
                    ?: stream.multiDashVideo.dashVideosList.firstOrNull()
                )?.let { mapDash(stream.streamInfo.quality, it, audios) }
            else -> null
        }
    }

    private fun mapDash(
        quality: Int,
        dash: DashVideo,
        audios: List<DownloadAudio>
    ): DownloadStream? {
        if (dash.baseUrl.isBlank()) return null
        return DownloadStream(
            quality = quality,
            videoUrl = dash.baseUrl,
            videoBackupUrls = dash.backupUrlList,
            audioId = dash.audioId.takeIf { it > 0 && audios.any { audio -> audio.id == it } }
        )
    }

    private fun mapAudio(item: DashItem): DownloadAudio {
        return DownloadAudio(
            id = item.id,
            url = item.baseUrl,
            backupUrls = item.backupUrlList
        )
    }

    private suspend fun cacheDanmaku(
        task: VideoDownloadEntity,
        source: DownloadSource,
        dir: File
    ) {
        val aid = source.aid.takeIf { it > 0L } ?: task.aid.takeIf { it > 0L } ?: return
        val cid = source.cid.takeIf { it > 0L } ?: task.cid.takeIf { it > 0L } ?: return

        runCatchingCancellable {
            val reply = grpcClient.call(
                endpoint = DM_SEG_CACHE_ENDPOINT,
                requestBytes = buildDmSegCacheReq(aid = aid, cid = cid).toByteArray(),
                parser = DmSegMobileReply.parser()
            )
            persistDanmaku(
                file = File(dir, DANMAKU_FILE_NAME),
                payload = reply.toByteArray()
            )
            reply.elemsCount
        }.onSuccess { count ->
            Logger.d(TAG) {
                "离线弹幕缓存完成 aid=$aid cid=$cid elems=$count"
            }
        }.onFailure { error ->
            Logger.w(TAG) {
                "离线弹幕缓存失败 aid=$aid cid=$cid, ${error.message}"
            }
        }
    }

    private fun buildDmSegCacheReq(
        aid: Long,
        cid: Long
    ): DmSegCacheReq {
        return DmSegCacheReq.newBuilder()
            .setType(DM_TYPE_VIDEO)
            .setOid(cid)
            .setPid(aid)
            .build()
    }

    private fun persistDanmaku(
        file: File,
        payload: ByteArray
    ) {
        val parent = file.parentFile ?: error("弹幕缓存目录无效")
        if (!parent.exists() && !parent.mkdirs()) {
            error("创建弹幕缓存目录失败")
        }
        val part = File("${file.absolutePath}.part")
        if (part.exists() && !part.delete()) {
            error("清理临时弹幕缓存失败")
        }
        FileOutputStream(part, false).use { output ->
            output.write(payload)
            output.flush()
        }
        if (file.exists() && !file.delete()) {
            error("清理旧弹幕缓存失败")
        }
        if (!part.renameTo(file)) {
            error("保存弹幕缓存失败")
        }
    }

    private fun mapDanmakuItem(elem: DanmakuElem): DanmakuItem {
        return DanmakuItem(
            id = elem.id,
            idStr = elem.idStr,
            progressMs = elem.progress,
            mode = elem.mode,
            fontSize = elem.fontsize,
            color = elem.color,
            midHash = elem.midHash,
            content = elem.content,
            createdAtEpochSecond = elem.ctime,
            weight = elem.weight,
            action = elem.action,
            pool = elem.pool,
            attr = elem.attr,
            likeCount = elem.likeCount,
            animation = elem.animation,
            extra = elem.extra,
            colorfulType = elem.colorfulValue,
            type = elem.type,
            oid = elem.oid,
            dmFromType = elem.dmFromValue
        )
    }

    private suspend fun downloadFile(
        url: String,
        out: File,
        onProgress: suspend (Long, Long) -> Unit
    ) {
        if (out.exists()) {
            val size = out.length()
            if (size > 0L) {
                onProgress(size, size)
                return
            }
            if (!out.delete()) {
                error("清理空下载文件失败")
            }
        }
        val part = File("${out.absolutePath}.part")
        val start = part.length()
        currentCoroutineContext().ensureActive()
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("user-agent", UserAgentBuilder.buildPlayerUserAgent())
        if (start > 0L) {
            requestBuilder.addHeader("Range", "bytes=$start-")
        }
        val request = requestBuilder.build()
        val call = okHttpClient.newCall(request)
        runningCall = call
        try {
            call.execute().use { response ->
                if (response.code == HTTP_RANGE_NOT_SATISFIABLE && start > 0L) {
                    val total = contentRangeTotal(response.header("Content-Range"))
                    if (total == start && part.renameTo(out)) {
                        onProgress(start, start)
                        return
                    }
                    error("断点续传位置无效")
                }
                if (start > 0L && response.code != HTTP_PARTIAL_CONTENT) {
                    error("服务器不支持断点续传")
                }
                // 审查 D8：206 只表示"返回了某个 range"，不保证从本地续传偏移开始
                //（RFC 9110 §15.3.7 要求客户端校验 range 匹配后才能合并）。CDN 前置
                // 代理吞掉 Range 头、或 URL 漂移到不同转码变体时，返回的起点可能是 0
                // —— 此处若照常从 start 追加，前后两段不同源字节拼进同一 .part 并转正，
                // 得到长度正确但内容错位的媒体文件（播放花屏/解封装失败，无检测点）。
                // 起点不匹配 → 丢弃 .part 报错，用户重试时从 0 重新整段下载。
                if (start > 0L) {
                    val begin = contentRangeBegin(response.header("Content-Range"))
                    if (begin != null && begin != start) {
                        if (!part.delete()) {
                            part.deleteOnExit()
                        }
                        error("断点续传位置无效（服务器返回起点 $begin，本地 $start），已重置下载进度")
                    }
                }
                if (!response.isSuccessful) {
                    error("下载失败 HTTP ${response.code}")
                }
                val body = response.body // OkHttp 5 非空，原来的 `?: error(...)` 是恒假分支
                val total = contentRangeTotal(response.header("Content-Range"))
                    ?: body.contentLength().takeIf { it >= 0L }?.plus(start)
                    ?: 0L
                var done = start
                body.byteStream().use { input ->
                    FileOutputStream(part, start > 0L).use { output ->
                        val buf = ByteArray(BUF_SIZE)
                        var lastProgressAt = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val len = input.read(buf)
                            if (len < 0) break
                            output.write(buf, 0, len)
                            done += len
                            val now = System.currentTimeMillis()
                            if (done == total || now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                                lastProgressAt = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (part.length() <= 0L) {
                    error("下载文件为空")
                }
                if (!part.renameTo(out)) {
                    error("保存下载文件失败")
                }
            }
        } finally {
            if (runningCall === call) {
                runningCall = null
            }
        }
    }

    private fun contentRangeTotal(value: String?): Long? {
        return value?.substringAfter('/', missingDelimiterValue = "")?.toLongOrNull()
    }

    /** 解析 Content-Range 的起始字节（"bytes 100-199/200" → 100）；格式异常返回 null（不误杀）。 */
    private fun contentRangeBegin(value: String?): Long? {
        val spec = value?.trim()?.removePrefix("bytes")?.trim() ?: return null
        return spec.substringBefore('-').trim().toLongOrNull()
    }

    private fun taskDir(taskId: Long): File {
        return File(context.filesDir, "$ROOT_DIR_NAME/$taskId")
    }

    private fun deleteTaskFiles(taskId: Long) {
        val dir = taskDir(taskId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun DownloadSource.selectStream(quality: Int): DownloadStream? {
        return streams.firstOrNull { it.quality == quality } ?: streams.firstOrNull()
    }

    private fun DownloadSource.selectAudio(
        stream: DownloadStream?,
        preferredId: Int
    ): DownloadAudio? {
        if (audios.isEmpty()) return null
        return audios.firstOrNull { it.id == preferredId && preferredId > 0 }
            ?: audios.firstOrNull { it.id == stream?.audioId }
            ?: audios.firstOrNull()
    }

    private fun buildCdns(
        stream: DownloadStream,
        audio: DownloadAudio?
    ): List<DownloadCdn> {
        val videoUrls = mergePlaybackUrls(stream.videoUrl, stream.videoBackupUrls)
        if (videoUrls.isEmpty()) return emptyList()
        val audioUrls = mergePlaybackUrls(audio?.url, audio?.backupUrls ?: emptyList())
        return List(maxOf(videoUrls.size, audioUrls.size)) { index ->
            DownloadCdn(
                videoUrl = videoUrls[index.coerceAtMost(videoUrls.lastIndex)],
                audioUrl = audioUrls.getOrNull(index.coerceAtMost(audioUrls.lastIndex))
            )
        }
    }

    private fun DownloadAudio.urls(): List<String> = mergePlaybackUrls(url, backupUrls)

    private fun VideoDownloadEntity.toRequest(): VideoDownloadRequest {
        return VideoDownloadRequest(
            biz = PlayBiz.from(biz),
            aid = aid,
            cid = cid,
            bvid = bvid,
            epId = epId,
            seasonId = seasonId,
            kind = VideoDownloadKind.from(kind),
            videoQuality = videoQuality,
            audioQuality = audioQuality
        )
    }

    private fun VideoDownloadRequest.key(): TaskKey {
        return TaskKey(
            biz = biz.name,
            aid = aid,
            cid = cid,
            epId = epId,
            seasonId = seasonId
        )
    }

    private fun VideoDownloadRequest.extraContent(): Map<String, String> {
        return buildMap {
            epId.takeIf { it > 0L }?.let { put("ep_id", it.toString()) }
            seasonId.takeIf { it > 0L }?.let { put("season_id", it.toString()) }
            put("short_edge", SHORT_EDGE)
            put("long_edge", LONG_EDGE)
            if (biz == PlayBiz.PUGV) {
                put("biz_type", PUGV_BIZ_TYPE)
            }
        }
    }

    private fun VideoDownloadEntity.withResolvedVideoId(
        aid: Long,
        cid: Long
    ): VideoDownloadEntity {
        val resolvedAid = aid.takeIf { it > 0L } ?: this.aid
        val resolvedCid = cid.takeIf { it > 0L } ?: this.cid
        if (resolvedAid == this.aid && resolvedCid == this.cid) {
            return this
        }
        return copy(
            aid = resolvedAid,
            cid = resolvedCid
        )
    }

    private fun VideoDownloadEntity.prepareForRun(): VideoDownloadEntity {
        return copy(
            status = VideoDownloadTaskStatus.RUNNING.name,
            progressType = ProgressType.PREPARING,
            progressLabel = null,
            doneBytes = 0L,
            totalBytes = 0L,
            error = null,
            videoPath = null,
            audioPath = null,
            durationMs = 0L
        )
    }

    private fun VideoDownloadEntity.waiting(): VideoDownloadEntity {
        return copy(
            status = VideoDownloadTaskStatus.WAITING.name,
            progressType = null,
            progressLabel = null,
            doneBytes = 0L,
            totalBytes = 0L,
            error = null,
            videoPath = null,
            audioPath = null,
            durationMs = 0L
        )
    }

    private fun VideoDownloadEntity.pause(): VideoDownloadEntity {
        return pause(message = null)
    }

    private fun VideoDownloadEntity.pause(message: String?): VideoDownloadEntity {
        return copy(
            status = VideoDownloadTaskStatus.PAUSED.name,
            error = message,
            videoPath = null,
            audioPath = null,
            durationMs = 0L
        )
    }

    private fun VideoDownloadEntity.downloading(
        label: String,
        doneBytes: Long,
        totalBytes: Long
    ): VideoDownloadEntity {
        return copy(
            progressType = ProgressType.DOWNLOADING,
            progressLabel = label,
            doneBytes = doneBytes,
            totalBytes = totalBytes
        )
    }

    private fun VideoDownloadEntity.finish(
        videoPath: String?,
        audioPath: String?,
        durationMs: Long
    ): VideoDownloadEntity {
        return copy(
            status = VideoDownloadTaskStatus.DONE.name,
            progressType = ProgressType.DONE,
            progressLabel = null,
            doneBytes = 0L,
            totalBytes = 0L,
            error = null,
            videoPath = videoPath,
            audioPath = audioPath,
            durationMs = durationMs
        )
    }

    private fun VideoDownloadEntity.fail(message: String): VideoDownloadEntity {
        return copy(
            status = VideoDownloadTaskStatus.FAILED.name,
            progressType = null,
            progressLabel = null,
            doneBytes = 0L,
            totalBytes = 0L,
            error = message,
            videoPath = null,
            audioPath = null,
            durationMs = 0L
        )
    }

    private data class DownloadSource(
        val aid: Long,
        val cid: Long,
        val durationMs: Long,
        val streams: List<DownloadStream>,
        val audios: List<DownloadAudio>
    )

    private data class DownloadStream(
        val quality: Int,
        val videoUrl: String,
        val videoBackupUrls: List<String>,
        val audioId: Int?
    )

    private data class DownloadAudio(
        val id: Int,
        val url: String,
        val backupUrls: List<String>
    )

    private data class DownloadCdn(
        val videoUrl: String,
        val audioUrl: String?
    )

    private data class TaskKey(
        val biz: String,
        val aid: Long,
        val cid: Long,
        val epId: Long,
        val seasonId: Long
    )

    private companion object {
        const val TAG = "VideoDownloadRepo"
        const val ENDPOINT = "bilibili.app.playerunite.v1.Player/PlayViewUnite"
        const val DM_SEG_CACHE_ENDPOINT = "bilibili.community.service.dm.v1.DM/DmSegCache"
        const val FROM_SCENE = "normal"
        const val SHORT_EDGE = "1080"
        const val LONG_EDGE = "1920"
        const val PUGV_BIZ_TYPE = "3"
        const val DOWNLOAD_FNVAL = 4048
        const val DM_TYPE_VIDEO = 1
        const val CODECID_HEVC = 12
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val BUF_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL_MS = 400L
        const val ROOT_DIR_NAME = "video_download"
        const val DANMAKU_FILE_NAME = "danmaku.pb"
        const val VIDEO_FILE_NAME = "video.m4s"
        const val AUDIO_FILE_NAME = "audio.m4s"
        const val AUDIO_ONLY_FILE_NAME = "audio.m4a"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object VideoDownloadModule {
    @Provides
    @Singleton
    fun provideVideoDownloadDb(
        @ApplicationContext context: Context
    ): VideoDownloadDb {
        return Room.databaseBuilder(
            context,
            VideoDownloadDb::class.java,
            "video_download.db"
        ).build()
    }

    @Provides
    fun provideVideoDownloadDao(db: VideoDownloadDb): VideoDownloadDao {
        return db.dao()
    }
}

private fun mergePlaybackUrls(primaryUrl: String?, backupUrls: List<String>): List<String> {
    return (listOfNotNull(primaryUrl) + backupUrls)
        .filter(String::isNotBlank)
        .distinct()
}
