package com.naaammme.bbspace

import android.content.Context
import android.content.Intent
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.auth.CacheManager
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.infra.coldstart.ColdStartClient
import com.naaammme.bbspace.infra.grpc.GaiaReporter
import com.naaammme.bbspace.infra.crypto.BuvidFetcher
import com.naaammme.bbspace.infra.crypto.GuestIdGenerator
import com.naaammme.bbspace.infra.crypto.TicketGenerator
import com.naaammme.bbspace.infra.network.dns.BiliDns
import com.naaammme.bbspace.infra.player.PlayerEngine
import com.naaammme.bbspace.playback.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AppInitializer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coldStartClient: ColdStartClient,
    private val ticketGenerator: TicketGenerator,
    private val buvidFetcher: BuvidFetcher,
    private val guestIdGenerator: GuestIdGenerator,
    private val biliDns: BiliDns,
    @param:Named("image") private val imageOkHttpClient: OkHttpClient,
    private val okHttpClient: OkHttpClient,
    private val gaiaReporter: GaiaReporter,
    private val appSettings: AppSettings,
    private val cacheManager: CacheManager,
    private val playbackSession: StreamPlaybackSession,
    private val playerEngine: PlayerEngine
) {
    companion object {
        private const val TAG = "AppInitializer"
    }

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var coldStartSucceeded = false

    /**
     * 启动期上报 [PlayerViewTargetBinder] 的已释放 player 驻留量。
     *
     * 用途：量化 `released`（`IdentityHashMap<Player, Unit>`，只写不删）是否会在真实使用中
     * 无界增长。每次冷启动打印一次 —— 若多次冷启动之间该值持续变大，说明真的在累积；
     * 若始终为 0 或很小，说明正常浏览/播放流程根本不触发引擎释放（返回时播放转交迷你播放器，
     * 引擎实例被复用），无需收敛。
     *
     * 仅 [BuildConfig.DEBUG] 输出；release 下该分支不成立，无日志开销。
     */
    private fun reportPlayerBinderRetention() {
        if (!BuildConfig.DEBUG) return
        Logger.d(TAG) {
            "PlayerViewTargetBinder.released 驻留 = " +
                com.naaammme.bbspace.infra.player.PlayerViewTargetBinder
                    .releasedCountForDiagnostics()
        }
    }

    fun initialize() {
        observePlaybackService()
        reportPlayerBinderRetention()

        initScope.launch {
            // 一次性设置迁移：默认转场 缩放(SHARED_AXIS_Z) → 左右滑动(SHARED_AXIS_X)。
            // 幂等的一次写；失败不挡启动，只留痕。
            launch {
                runCatching { appSettings.migrateTransitionStyleDefaultIfNeeded() }
                    .onFailure { Logger.w(TAG) { "转场默认值迁移失败：${it.message}" } }
            }
            val useSystemDns = appSettings.useSystemDns.first()
            biliDns.start(useSystemDns) {
                if (!initialized) return@start
                // init 流程内已成功拉取过冷启动数据则不重复请求；
                // DNS 就绪后的补拉仅用于首次请求失败的恢复场景
                if (coldStartSucceeded) return@start
                initScope.launch {
                    cacheManager.clearColdStartCache()
                    coldStartClient.getColdStartData()
                }
            }
            warmupImageConnections()
            warmupApiConnection()
            launch {
                runCatching {
                    playbackSession.prepare()
                }.onSuccess {
                    Logger.d(TAG) { "Player warmup done" }
                }.onFailure { error ->
                    Logger.w(TAG) { "Player warmup failed: ${error.message}" }
                }
            }
            launch {
                try {
                    Logger.d(TAG) { "开始初始化应用..." }

                    appSettings.themeConfig.first()
                    appSettings.updateNeedTrial(false)

                    val ticket = ticketGenerator.getOrRefresh()
                    Logger.d(TAG) { "Ticket 初始化完成: ${if (ticket.isEmpty()) "空" else "已缓存"}" }

                    val remoteBuvid = buvidFetcher.fetchAndUpdate()
                    Logger.d(TAG) { "远程 buvid: ${remoteBuvid ?: "获取失败，使用本地 buvid"}" }

                    val coldStartData = coldStartClient.getColdStartData()
                    coldStartSucceeded = coldStartData.ipInfo != null
                    Logger.d(TAG) { "冷启动数据获取完成: IP=${coldStartData.ipInfo?.addr}" }

                    val guestId = guestIdGenerator.getOrGenerateGuestId(ticket)
                    Logger.d(TAG) { "GuestId 初始化完成: $guestId" }

                    if (cacheManager.sessionId.isEmpty()) {
                        val sessionId = cacheManager.generateSessionId()
                        cacheManager.saveSession(guestId, sessionId, "")
                        Logger.d(TAG) { "SessionId 初始化完成: $sessionId" }
                    }

                    if (!appSettings.blockGaia.first()) {
                        gaiaReporter.reportIfNeeded()
                    } else {
                        Logger.d(TAG) { "Gaia 上报已被用户禁用，跳过" }
                    }
                    initialized = true
                    Logger.d(TAG) { "应用初始化完成" }
                } catch (e: Exception) {
                    Logger.e(TAG, e) { "应用初始化失败" }
                }
            }
        }
    }

    // 预建 TCP+TLS 连接到图片 CDN，避免首批图片等待握手。
    // 必须使用图片 client：它与 Coil 共享同一连接池，预热连接才能被图片请求复用
    private fun warmupImageConnections() {
        val hosts = listOf("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com")
        initScope.launch {
            hosts.map { host ->
                async {
                    val req = Request.Builder()
                        .url("https://$host")
                        .head()
                        .build()
                    runCatching { imageOkHttpClient.newCall(req).execute().close() }
                    Logger.d(TAG) { "连接预热完成: $host" }
                }
            }.forEach { it.await() }
        }
    }

    // 预建 API 宿主（gRPC 同走该 OkHttpClient 连接池）的连接：首次「取流 + 详情」
    // 请求不再独付 DNS+TCP+TLS（移动网络下数百毫秒），详情/起播同步提速
    private fun warmupApiConnection() {
        initScope.launch {
            val req = Request.Builder()
                .url(com.naaammme.bbspace.core.common.BiliConstants.BASE_URL_APP)
                .head()
                .build()
            runCatching { okHttpClient.newCall(req).execute().close() }
            Logger.d(TAG) { "API 连接预热完成: ${com.naaammme.bbspace.core.common.BiliConstants.BASE_URL_APP}" }
        }
    }

    private fun observePlaybackService() {
        initScope.launch {
            combine(
                playerEngine.currentSource.map { it != null },
                appSettings.state.map { it.playback.backgroundPlayback }
            ) { hasSource, backgroundPlayback ->
                hasSource && backgroundPlayback
            }
                .distinctUntilChanged()
                .collect { shouldStart ->
                    val intent = Intent(context, PlaybackService::class.java)
                    // 该回调正常只随用户操作（打开/关闭播放）在前台触发；后台状态下的
                    // 竞态重放会被系统拒绝 startService（Android 12+ 后台服务限制），
                    // 属于无影响边缘——静默跳过，回到前台的下一次状态变化自会纠正
                    runCatching {
                        if (shouldStart) {
                            context.startService(intent)
                        } else {
                            context.stopService(intent)
                        }
                    }
                }
        }
    }
}
