package com.naaammme.bbspace.core.live

import android.os.SystemClock
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.auth.AuthRepository
import com.naaammme.bbspace.core.published.PublishedRecordRepository
import com.naaammme.bbspace.core.model.LiveRoomMedal
import com.naaammme.bbspace.core.model.LiveRoomMessage
import com.naaammme.bbspace.core.model.LiveRoomPanelState
import com.naaammme.bbspace.core.model.LiveRoomSessionState
import com.naaammme.bbspace.core.model.LiveRoomUser
import com.naaammme.bbspace.core.model.PUBLISHED_RECORD_KIND_LIVE_DANMAKU
import com.naaammme.bbspace.core.model.PublishedRecord
import com.naaammme.bbspace.core.model.PublishedRecordKeyTool
import com.naaammme.bbspace.core.model.merge
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import com.naaammme.bbspace.infra.crypto.HwIdGenerator
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class LiveRoomMessageRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authRepository: AuthRepository,
    private val authProvider: AuthProvider,
    private val publishedRecordRepo: PublishedRecordRepository,
    private val deviceIdentity: DeviceIdentity,
    private val hwIdGenerator: HwIdGenerator
) {

    fun observeRoomSession(roomId: Long): Flow<LiveRoomSessionState> = flow {
        if (roomId <= 0L) {
            emit(
                LiveRoomSessionState(
                    roomId = roomId,
                    lastError = "直播间 roomId 无效"
                )
            )
            return@flow
        }

        coroutineScope {
            val localIdGen = AtomicLong(0L)
            val ackDeduper = AckDeduper()
            val stateLock = Any()
            val stateFlow = MutableStateFlow(LiveRoomSessionState(roomId = roomId))
            var retryCount = 0

            fun pushState(transform: (LiveRoomSessionState) -> LiveRoomSessionState) {
                synchronized(stateLock) {
                    stateFlow.value = transform(stateFlow.value)
                }
            }

            suspend fun runLoop(scope: CoroutineScope) {
                while (scope.isActive) {
                    val danmuInfo = try {
                        pushState { it.copy(lastError = null) }
                        fetchDanmuInfo(roomId)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        val msg = t.message ?: "获取直播弹幕配置失败"
                        Logger.e(TAG, t) { "fetch danmu info failed roomId=$roomId msg=$msg" }
                        pushState { it.copy(lastError = msg) }
                        retryCount += 1
                        delay(nextRetryDelayMs(retryCount))
                        continue
                    }

                    try {
                        connectAndConsume(
                            roomId = roomId,
                            info = danmuInfo,
                            localIdGen = localIdGen,
                            ackDeduper = ackDeduper,
                            emitState = ::pushState
                        )
                        if (!scope.isActive) return
                        retryCount = 0
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        val msg = t.message ?: "直播消息连接断开"
                        Logger.w(TAG) { "live room session break roomId=$roomId msg=$msg retry=$retryCount" }
                        pushState { it.copy(lastError = msg) }
                        retryCount += 1
                        delay(nextRetryDelayMs(retryCount))
                    }
                }
            }

            val sessionJob = launch(Dispatchers.IO) { runLoop(this) }
            try {
                emitAll(stateFlow)
            } finally {
                sessionJob.cancel()
            }
        }
    }

    suspend fun sendDanmaku(
        roomId: Long,
        content: String,
        jumpFrom: Int
    ) {
        require(roomId > 0L) { "直播间 roomId 无效" }
        val msg = content.trim()
        require(msg.isNotEmpty()) { "弹幕内容不能为空" }
        val accessToken = authProvider.accessToken.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("请先登录后再发送弹幕")
        val mid = authProvider.mid.takeIf { it > 0L }
            ?: throw IllegalStateException("当前账号信息无效")
        val sentAtMs = System.currentTimeMillis()
        val ts = System.currentTimeMillis() / 1000L
        val dataExtend = buildBasicDataExtend()
        val liveStatistics = buildBasicLiveStatistics(
            roomId = roomId,
            jumpFrom = jumpFrom,
            dataExtend = dataExtend
        )
        restClient.postSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$SEND_DANMAKU_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + buildMap {
                put("actionKey", "appkey")
                put("av_id", UNKNOWN_EXT_VALUE)
                put("bubble", "0")
                put("bussiness_extend", DEFAULT_BUSSINESS_EXTEND)
                put("cid", roomId.toString())
                put("color", DEFAULT_DANMAKU_COLOR.toString())
                put("data_extend", dataExtend)
                put("device", BiliConstants.PLATFORM)
                put("dm_type", "0")
                put("flow_extend", DEFAULT_FLOW_EXTEND)
                put("fontsize", DEFAULT_DANMAKU_FONT_SIZE.toString())
                put("jumpfrom", jumpFrom.toString())
                put("jumpfrom_extend", UNKNOWN_EXT_VALUE)
                put("launch_id", DEFAULT_LAUNCH_ID)
                put("live_statistics", liveStatistics)
                put("live_status", DEFAULT_LIVE_STATUS)
                put("mid", mid.toString())
                put("mode", DEFAULT_DANMAKU_MODE.toString())
                put("msg", msg)
                put("msg_type", "0")
                put("playTime", DEFAULT_PLAY_TIME)
                put("pool", "0")
                put("reply_attr", "0")
                put("reply_mid", "0")
                put("reply_type", "0")
                put("reply_uname", "")
                put("rnd", randomDanmakuSeed())
                put("room_type", "0")
                put("screen_status", DEFAULT_SCREEN_STATUS)
                put("session_id", UNKNOWN_EXT_VALUE)
                put("type", "json")
            },
            profile = BiliRestProfile.APP
        )
        // ST-06b：弹幕此刻已发送成功，收尾只剩本地记账，所以这一处**不能**传播取消
        // （换成 runCatchingCancellable 会把一次成功的发送改判成失败）。
        // 与 CommentRepository 同款正解：记账跑在 NonCancellable 上。
        withContext(NonCancellable) {
            runCatching {
                publishedRecordRepo.save(
                    buildLiveDanmakuRecord(
                        roomId = roomId,
                        senderMid = mid,
                        content = msg,
                        sentAtMs = sentAtMs
                    )
                )
            }.onFailure { err ->
                Logger.e(TAG, err) { "save live danmaku record failed roomId=$roomId" }
            }
        }
    }

    private fun buildLiveDanmakuRecord(
        roomId: Long,
        senderMid: Long,
        content: String,
        sentAtMs: Long
    ): PublishedRecord {
        val user = authRepository.getUserInfo()
        return PublishedRecord(
            key = PublishedRecordKeyTool.liveDanmaku(
                roomId = roomId,
                senderMid = senderMid,
                itemId = sentAtMs
            ),
            kind = PUBLISHED_RECORD_KIND_LIVE_DANMAKU,
            itemId = sentAtMs,
            targetId = roomId,
            targetType = 0L,
            senderMid = senderMid,
            senderName = user?.name?.ifBlank { null } ?: "用户$senderMid",
            senderAvatar = user?.avatar.orEmpty(),
            content = content,
            ctime = sentAtMs / 1000L
        )
    }

    private suspend fun fetchDanmuInfo(roomId: Long): DanmuInfo {
        val ts = System.currentTimeMillis() / 1000L
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$DANMU_INFO_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, authProvider.accessToken) + buildMap {
                put("actionKey", "appkey")
                put("device", BiliConstants.PLATFORM)
                put("free_type", "0")
                put("is_anchor", "0")
                put("room_id", roomId.toString())
                put("version", BiliConstants.VERSION)
            },
            profile = BiliRestProfile.APP
        )
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("直播弹幕配置缺少 data")
        val token = data.optString("token")
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("直播弹幕配置缺少 token")
        val hosts = parseHosts(data.optJSONArray("ip_list"))
        if (hosts.isEmpty()) throw IllegalStateException("直播弹幕配置缺少 ip_list")
        return DanmuInfo(token = token, hosts = hosts)
    }

    private suspend fun connectAndConsume(
        roomId: Long,
        info: DanmuInfo,
        localIdGen: AtomicLong,
        ackDeduper: AckDeduper,
        emitState: ((LiveRoomSessionState) -> LiveRoomSessionState) -> Unit
    ) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (index in info.hosts.indices) {
            val host = info.hosts[index]
            val queueId = UUID.randomUUID().toString()
            val socket = Socket()
            val writeLock = Any()
            try {
                ackDeduper.clear()
                emitState { it.copy(lastError = null) }
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host.host, host.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = AUTH_TIMEOUT_MS
                socket.getOutputStream().use { output ->
                    DataInputStream(socket.getInputStream()).use { input ->
                        sendPacket(
                            output = output,
                            writeLock = writeLock,
                            op = OP_AUTH,
                            payload = buildAuthPayload(roomId, info.token, queueId).toByteArray()
                        )
                        consumeSocket(
                            socket = socket,
                            input = input,
                            output = output,
                            writeLock = writeLock,
                            localIdGen = localIdGen,
                            ackDeduper = ackDeduper,
                            emitState = emitState
                        )
                    }
                }
                return@withContext
            } catch (t: Throwable) {
                lastError = t
                socket.closeQuietly()
                Logger.w(TAG) {
                    "live socket host failed roomId=$roomId host=${host.host}:${host.port} msg=${t.message}"
                }
            } finally {
                socket.closeQuietly()
            }
        }
        throw lastError ?: IllegalStateException("直播消息连接失败")
    }

    private suspend fun consumeSocket(
        socket: Socket,
        input: DataInputStream,
        output: OutputStream,
        writeLock: Any,
        localIdGen: AtomicLong,
        ackDeduper: AckDeduper,
        emitState: ((LiveRoomSessionState) -> LiveRoomSessionState) -> Unit
    ) = withContext(Dispatchers.IO) {
        var authOk = false
        var heartbeatJob: Job? = null

        try {
            while (isActive) {
                val packet = readPacket(input)
                handlePacket(
                    packet = packet,
                    output = output,
                    writeLock = writeLock,
                    localIdGen = localIdGen,
                    ackDeduper = ackDeduper,
                    emitState = emitState,
                    onAuthSuccess = {
                        authOk = true
                        socket.soTimeout = READ_TIMEOUT_MS
                        if (heartbeatJob == null) {
                            heartbeatJob = launch {
                                while (isActive) {
                                    delay(HEARTBEAT_INTERVAL_MS)
                                    sendPacket(output, writeLock, OP_HEARTBEAT, EMPTY_BYTES)
                                }
                            }
                        }
                        emitState { it.copy(lastError = null) }
                    },
                    onHeartbeat = { popular ->
                        emitState { it.copy(popularCount = popular) }
                    },
                    onMessage = { msg ->
                        emitState {
                            val next = appendMessage(it.messages, msg)
                            it.copy(messages = next)
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!authOk && t !is EOFException) {
                throw IllegalStateException("直播消息认证失败: ${t.message}", t)
            }
            throw t
        } finally {
            heartbeatJob?.cancel()
        }
    }

    private fun handlePacket(
        packet: LivePacket,
        output: OutputStream,
        writeLock: Any,
        localIdGen: AtomicLong,
        ackDeduper: AckDeduper,
        emitState: ((LiveRoomSessionState) -> LiveRoomSessionState) -> Unit,
        onAuthSuccess: () -> Unit,
        onHeartbeat: (Long) -> Unit,
        onMessage: (LiveRoomMessage) -> Unit
    ) = when (packet.op) {
        OP_AUTH_REPLY -> {
            val json = parseJson(packet.payload)
            if (json.optInt("code", -1) != 0) {
                throw IllegalStateException("直播消息认证返回 code=${json.optInt("code", -1)}")
            }
            onAuthSuccess()
        }

        OP_HEARTBEAT_REPLY -> {
            onHeartbeat(parsePopularCount(packet.payload))
        }

        OP_MESSAGE -> {
            // B7：解包逻辑已抽到 [LiveDanmakuProtocol]（纯逻辑、可单测）。
            // 原先它是本类的 private 方法，于是"恶意包会不会打崩解析器"这件事
            // 没有任何真字节级的测试能覆盖（只能靠扫源码）。见该 object 的 KDoc。
            LiveDanmakuProtocol.unpack(packet.version, packet.payload).forEach { json ->
                parseAckPayload(json)?.let { ack ->
                    sendAck(
                        output = output,
                        writeLock = writeLock,
                        msgId = ack.msgId,
                        cmd = ack.cmd,
                        msgType = ack.msgType
                    )
                }
                if (ackDeduper.shouldDrop(json)) return@forEach
                parseDanmuMessage(json, localIdGen.incrementAndGet())?.let { msg ->
                    onMessage(msg)
                    return@forEach
                }
                parsePanelPatch(json)?.let { patch ->
                    emitState {
                        it.copy(panel = it.panel.merge(patch))
                    }
                }
            }
        }

        else -> Unit
    }

    private fun parseDanmuMessage(
        json: JSONObject,
        localId: Long
    ): LiveRoomMessage? {
        val rawCmd = json.optString("cmd")
        val cmd = rawCmd.substringBefore(':')
        if (cmd != CMD_DANMU && cmd != CMD_DANMU_MIRROR) return null
        val info = json.optJSONArray("info") ?: return null
        val meta = info.optJSONArray(0)
        val content = info.optString(1).takeIf(String::isNotBlank) ?: return null
        val extObj = meta?.optJSONObject(15)
        val extra = extObj?.optString("extra")
        val userObj = extObj?.optJSONObject("user")
        val baseObj = userObj?.optJSONObject("base")
        val medalObj = userObj?.optJSONObject("medal")
        val user = userObj?.let {
            LiveRoomUser(
                uid = it.optLong("uid"),
                name = baseObj?.optString("name").orEmpty(),
                avatar = baseObj?.optString("face")?.takeIf(String::isNotBlank),
                nameColor = baseObj?.optString("name_color_str")?.takeIf(String::isNotBlank)
            )
        }?.takeIf { it.uid > 0L || it.name.isNotBlank() }
        val medal = medalObj?.let {
            LiveRoomMedal(
                name = it.optString("name"),
                level = it.optInt("level"),
                colorStart = it.optString("v2_medal_color_start").takeIf(String::isNotBlank),
                colorEnd = it.optString("v2_medal_color_end").takeIf(String::isNotBlank),
                colorBorder = it.optString("v2_medal_color_border").takeIf(String::isNotBlank)
            )
        }?.takeIf { it.name.isNotBlank() || it.level > 0 }
        return LiveRoomMessage(
            localId = localId,
            msgId = json.optString("msg_id").takeIf(String::isNotBlank),
            cmd = cmd,
            title = if (cmd == CMD_DANMU_MIRROR) "镜像弹幕" else null,
            content = content,
            user = user,
            medal = medal,
            mode = meta?.optInt(1) ?: 1,
            fontSize = meta?.optInt(2) ?: 25,
            color = meta?.optInt(3) ?: 0xFFFFFF,
            sendTimeMs = json.optLong("send_time").takeIf { it > 0L }
                ?: meta?.optLong(4)?.takeIf { it > 0L }
                ?: 0L,
            isMirror = cmd == CMD_DANMU_MIRROR,
            isAck = json.optBoolean("p_is_ack"),
            msgType = json.optInt("p_msg_type").takeIf { it > 0 || json.has("p_msg_type") },
            extra = extra?.takeIf(String::isNotBlank)
        )
    }

    private fun parsePanelPatch(
        json: JSONObject
    ): LiveRoomPanelState? {
        val rawCmd = json.optString("cmd").takeIf(String::isNotBlank) ?: return null
        val cmd = rawCmd.substringBefore(':')
        return when (cmd) {
            CMD_WATCHED_CHANGE -> {
                val data = json.optJSONObject("data") ?: return null
                val content = data.optString("text_large")
                    .ifBlank { data.optString("text_small") }
                    .ifBlank { data.optString("num") }
                    .ifBlank { return null }
                LiveRoomPanelState(
                    watchedText = content
                )
            }

            CMD_ONLINE_RANK_COUNT -> {
                val data = json.optJSONObject("data") ?: return null
                val countText = data.optString("count_text")
                val content = countText
                    .ifBlank { data.optString("count") }
                    .takeIf(String::isNotBlank)
                    ?.let { "在线榜 $it" }
                    ?: return null
                LiveRoomPanelState(
                    onlineRankText = content
                )
            }

            CMD_RANK_CHANGED -> {
                val data = json.optJSONObject("data") ?: return null
                val rankName = data.optString("rank_name_by_type")
                    .ifBlank { data.optString("on_rank_name_by_type") }
                    .ifBlank { "排名变化" }
                val rank = data.optInt("rank")
                val content = if (rank > 0) {
                    "$rankName 第 $rank 名"
                } else {
                    rankName
                }
                LiveRoomPanelState(
                    rankChangedText = content
                )
            }

            else -> null
        }
    }

    private fun parseAckPayload(json: JSONObject): AckPayload? {
        if (!json.optBoolean("p_is_ack")) return null
        val msgId = json.optString("msg_id").takeIf(String::isNotBlank) ?: return null
        val cmd = json.optString("cmd").takeIf(String::isNotBlank) ?: return null
        val msgType = json.optInt("p_msg_type").takeIf { it > 0 || json.has("p_msg_type") }
            ?: return null
        return AckPayload(
            msgId = msgId,
            cmd = cmd,
            msgType = msgType
        )
    }

    private fun buildAuthPayload(
        roomId: Long,
        token: String,
        queueId: String
    ): String {
        return JSONObject().apply {
            put("app_id", BiliConstants.APP_ID)
            put("brand", deviceIdentity.brand)
            put("build", BiliConstants.BUILD)
            put("buvid", deviceIdentity.buvid)
            put("channel", BiliConstants.CHANNEL)
            put("clientver", "${BiliConstants.VERSION}.${BiliConstants.BUILD}")
            put("device", BiliConstants.PLATFORM)
            put("fp_local", deviceIdentity.fp)
            put("fp_remote", deviceIdentity.fp)
            put("group", "")
            hwIdGenerator.build()?.takeIf(String::isNotBlank)?.let { put("hwid", it) }
            put("key", token)
            put("mobi_app", BiliConstants.MOBI_APP)
            put("model", deviceIdentity.model)
            put("osver", deviceIdentity.osVer)
            put("platform", BiliConstants.PLATFORM)
            put("protover", 3)
            put("queue_uuid", queueId)
            put("roomid", roomId)
            put("scene", "room")
            put("support_ack", true)
            put("uid", authProvider.mid)
            put("version_name", BiliConstants.VERSION)
        }.toString()
    }

    private fun appendMessage(
        messages: List<LiveRoomMessage>,
        msg: LiveRoomMessage
    ): List<LiveRoomMessage> {
        // AP-11：重连（见本类重连回放路径）会把同 msg_id 的消息再投一遍，而
        // LiveDetailPane 的 LazyColumn 以 msgId 作 key —— 重复 key 直接崩溃。
        // 同 id 的回放帧在入口处丢弃（消息 ≤ MAX_MESSAGE_COUNT 条，线性查重成本可忽略）。
        val incomingId = msg.msgId
        if (incomingId != null && messages.any { it.msgId == incomingId }) return messages
        if (messages.isEmpty()) return listOf(msg)
        val next = if (messages.size >= MAX_MESSAGE_COUNT) {
            ArrayList<LiveRoomMessage>(MAX_MESSAGE_COUNT).apply {
                addAll(messages.subList(1, messages.size))
                add(msg)
            }
        } else {
            ArrayList<LiveRoomMessage>(messages.size + 1).apply {
                addAll(messages)
                add(msg)
            }
        }
        return next
    }

    private fun sendAck(
        output: OutputStream,
        writeLock: Any,
        msgId: String,
        cmd: String,
        msgType: Int
    ) {
        val payload = JSONObject().apply {
            put("msg_id", msgId)
            put("cmd", cmd)
            put("p_msg_type", msgType)
        }.toString().toByteArray()
        sendPacket(output, writeLock, OP_ACK, payload)
    }

    private fun sendPacket(
        output: OutputStream,
        writeLock: Any,
        op: Int,
        payload: ByteArray
    ) {
        // B7：拼包走 [LiveDanmakuProtocol.encode] —— 与测试用的是**同一份**字节布局，
        // 不会出现"测试里手写一份、生产里另一份"的漂移。
        val frame = LiveDanmakuProtocol.encode(version = VERSION_PLAIN, op = op, payload = payload)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    private fun readPacket(input: DataInputStream): LivePacket {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val packetLen = readInt32(header, 0)
        val headerLen = readInt16(header, 4)
        val version = readInt16(header, 6)
        val op = readInt32(header, 8)
        if (packetLen !in HEADER_SIZE..MAX_PACKET_SIZE) {
            throw IllegalStateException("直播消息包长度非法 packetLen=$packetLen")
        }
        if (headerLen !in HEADER_SIZE..packetLen) {
            throw IllegalStateException("直播消息包头长度非法 headerLen=$headerLen packetLen=$packetLen")
        }
        val extraHeaderLen = headerLen - HEADER_SIZE
        if (extraHeaderLen > 0) {
            skipFully(input, extraHeaderLen)
        }
        val bodyLen = packetLen - headerLen
        val payload = ByteArray(bodyLen)
        input.readFully(payload)
        return LivePacket(
            version = version,
            op = op,
            payload = payload
        )
    }

    private fun parsePopularCount(payload: ByteArray): Long {
        if (payload.size < 4) return 0L
        return LiveDanmakuProtocol.readUInt32(payload)
    }

    // B7：`inflate` / `decodeBrotli` / `readAllBytes` 三个方法已随解包逻辑一起
    // 移入 [LiveDanmakuProtocol]（它们只被那条链路使用，留在这里就是死代码）。
    // `parseJsonOrNull` / `readInt32` / `readInt16` 仍在本类有真实调用者
    // （认证包解析、包长校验），所以保留，但**实现委托给同一个 object**，
    // 避免"两份字节序实现漂移"。

    private fun parseJson(payload: ByteArray): JSONObject {
        return parseJsonOrNull(payload)
            ?: throw IllegalStateException("直播消息 JSON 非法")
    }

    private fun parseJsonOrNull(payload: ByteArray): JSONObject? =
        LiveDanmakuProtocol.parseJsonOrNull(payload)

    private fun parseHosts(arr: JSONArray?): List<HostPort> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val host = item.optString("host")
                val port = item.optInt("port")
                if (host.isBlank() || port <= 0) continue
                add(HostPort(host, port))
            }
        }
    }

    private fun nextRetryDelayMs(retryCount: Int): Long {
        val step = min(retryCount, MAX_RETRY_STEP)
        return RETRY_BASE_DELAY_MS * step
    }

    private fun buildBasicDataExtend(): String {
        return JSONObject().apply {
            put("from_launch_id", DEFAULT_LAUNCH_ID)
            put("from_session_id", UNKNOWN_EXT_VALUE)
            put("live_key", UNKNOWN_EXT_VALUE)
            put("sub_session_key", UNKNOWN_EXT_VALUE)
        }.toString()
    }

    private fun buildBasicLiveStatistics(
        roomId: Long,
        jumpFrom: Int,
        dataExtend: String
    ): String {
        return JSONObject().apply {
            put("buvid", deviceIdentity.buvid.ifBlank { UNKNOWN_EXT_VALUE })
            put("session_id", UNKNOWN_EXT_VALUE)
            put("launch_id", DEFAULT_LAUNCH_ID)
            put("jumpfrom", jumpFrom.toString())
            put("jumpfrom_extend", UNKNOWN_EXT_VALUE)
            put("screen_status", DEFAULT_SCREEN_STATUS)
            put("live_status", DEFAULT_LIVE_STATUS)
            put("av_id", UNKNOWN_EXT_VALUE)
            put("flow_extend", DEFAULT_FLOW_EXTEND)
            put("bussiness_extend", DEFAULT_BUSSINESS_EXTEND)
            put("data_extend", dataExtend)
            put("spm_id", UNKNOWN_EXT_VALUE)
            put("up_id", UNKNOWN_EXT_VALUE)
            put("room_id", roomId.toString())
            put("parent_area_id", UNKNOWN_EXT_VALUE)
            put("area_id", UNKNOWN_EXT_VALUE)
            put("simple_id", UNKNOWN_EXT_VALUE)
            put("room_category", "0")
            put("official_channel", UNKNOWN_EXT_VALUE)
            put("if_dual_screen", "0")
            put("subscreen_scale", "0")
            put("dual_screen_model", "0")
            put("gift_method", UNKNOWN_EXT_VALUE)
            put("gift_position", UNKNOWN_EXT_VALUE)
            put("gift_subname", UNKNOWN_EXT_VALUE)
        }.toString()
    }

    private fun randomDanmakuSeed(): String {
        return (UUID.randomUUID().mostSignificantBits.toInt()).toString()
    }

    private fun AckDeduper.shouldDrop(json: JSONObject): Boolean {
        val msgType = json.optInt("p_msg_type")
        if (msgType != ACK_DEDUP_MSG_TYPE) return false
        val msgId = json.optString("msg_id").takeIf(String::isNotBlank) ?: return false
        val now = SystemClock.uptimeMillis()
        trimExpired(now)
        if (!msgIds.add(msgId)) return true
        entries.addLast(AckCacheEntry(msgId, now))
        while (entries.size > ACK_CACHE_MAX_COUNT) {
            msgIds.remove(entries.removeFirst().msgId)
        }
        return false
    }

    private fun AckDeduper.trimExpired(nowMs: Long) {
        while (entries.isNotEmpty() && nowMs - entries.first().atMs >= ACK_CACHE_WINDOW_MS) {
            msgIds.remove(entries.removeFirst().msgId)
        }
    }

    private fun AckDeduper.clear() {
        entries.clear()
        msgIds.clear()
    }

    // B7：字节序读取统一委托给 [LiveDanmakuProtocol]，不再本类各留一份实现
    // （两份实现漂移会让"包长校验"与"解包"对同一段字节给出不同结论）。
    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        LiveDanmakuProtocol.readInt32(bytes, offset)

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        LiveDanmakuProtocol.readInt16(bytes, offset)

    private fun skipFully(input: DataInputStream, count: Int) {
        var remain = count
        while (remain > 0) {
            val skipped = input.skipBytes(remain)
            if (skipped <= 0) throw EOFException("直播消息头部不完整")
            remain -= skipped
        }
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private data class DanmuInfo(
        val token: String,
        val hosts: List<HostPort>
    )

    private data class HostPort(
        val host: String,
        val port: Int
    )

    private class LivePacket(
        val version: Int,
        val op: Int,
        val payload: ByteArray
    )

    private data class AckPayload(
        val msgId: String,
        val cmd: String,
        val msgType: Int
    )

    private data class AckCacheEntry(
        val msgId: String,
        val atMs: Long
    )

    private class AckDeduper {
        val entries = ArrayDeque<AckCacheEntry>()
        val msgIds = HashSet<String>()
    }

    private companion object {
        const val TAG = "LiveRoomMsg"
        const val DANMU_INFO_ENDPOINT = "/xlive/app-room/v1/index/getDanmuInfo"
        const val SEND_DANMAKU_ENDPOINT = "/xlive/app-room/v1/dM/sendmsg"
        const val DEFAULT_DANMAKU_MODE = 1
        const val DEFAULT_DANMAKU_FONT_SIZE = 25
        const val DEFAULT_DANMAKU_COLOR = 0xFFFFFF
        const val DEFAULT_LAUNCH_ID = "-99998"
        const val DEFAULT_LIVE_STATUS = "live"
        const val DEFAULT_PLAY_TIME = "0.0"
        const val DEFAULT_SCREEN_STATUS = "2"
        const val UNKNOWN_EXT_VALUE = "-99998"
        const val DEFAULT_FLOW_EXTEND = """{"position":"1","s_position":"1","slide_direction":"-99998"}"""
        const val DEFAULT_BUSSINESS_EXTEND = """{"broadcast_type":"1","stream_scale":"1","watch_ui_type":"3"}"""
        // ── 线协议常量：**单一真值源在 [LiveDanmakuProtocol]** ──────────────────
        // B7（2026-10-13）：解包逻辑抽出去之后，这些值如果在这里再留一份字面量，
        // 就会与协议层漂移（改了协议上限却忘了改校验）。一律转发。
        const val HEADER_SIZE = LiveDanmakuProtocol.HEADER_SIZE
        const val MAX_PACKET_SIZE = LiveDanmakuProtocol.MAX_PACKET_SIZE
        const val VERSION_PLAIN = LiveDanmakuProtocol.VERSION_PLAIN
        const val VERSION_ZLIB = LiveDanmakuProtocol.VERSION_ZLIB
        const val VERSION_BROTLI = LiveDanmakuProtocol.VERSION_BROTLI
        const val OP_HEARTBEAT = LiveDanmakuProtocol.OP_HEARTBEAT
        const val OP_HEARTBEAT_REPLY = LiveDanmakuProtocol.OP_HEARTBEAT_REPLY
        const val OP_MESSAGE = LiveDanmakuProtocol.OP_MESSAGE
        const val OP_AUTH = LiveDanmakuProtocol.OP_AUTH
        const val OP_AUTH_REPLY = LiveDanmakuProtocol.OP_AUTH_REPLY

        // 以下两个只被本类的 socket 循环用（ACK / 心跳 / 退避），协议层不关心：
        const val OP_ACK = 24
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 3_000
        const val AUTH_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 65_000
        const val RETRY_BASE_DELAY_MS = 2_000L
        const val MAX_RETRY_STEP = 5
        const val MAX_MESSAGE_COUNT = 200
        const val ACK_DEDUP_MSG_TYPE = 1
        const val ACK_CACHE_MAX_COUNT = 2_000
        const val ACK_CACHE_WINDOW_MS = 30_000L
        const val CMD_DANMU = "DANMU_MSG"
        const val CMD_DANMU_MIRROR = "DANMU_MSG_MIRROR"
        const val CMD_WATCHED_CHANGE = "WATCHED_CHANGE"
        const val CMD_ONLINE_RANK_COUNT = "ONLINE_RANK_COUNT"
        const val CMD_RANK_CHANGED = "RANK_CHANGED_V2"
        val EMPTY_BYTES = ByteArray(0)
    }
}
