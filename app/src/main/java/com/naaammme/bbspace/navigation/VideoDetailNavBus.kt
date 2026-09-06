package com.naaammme.bbspace.navigation

import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频详情 Activity → MainActivity 的跨 Activity 导航请求总线。
 *
 * 详情 Activity 自己没有 NavController：详情页内的「回首页 / 打开 UP 主空间 /
 * 查看缓存 / 开始下载」通过 [post] 投递请求并 finish 自己，MainActivity 的
 * AppNavHost 在可见时收集并复用既有的导航逻辑执行。
 *
 * 实现说明：用 Channel（receiveAsFlow）而非 StateFlow 保存待处理请求——
 * MainActivity 在后台时收不到事件，请求必须排队直到返回前台被处理；
 * 且「开始下载」可能连续投递多条相同请求，StateFlow 的去重/覆盖语义会丢事件，
 * Channel 的逐条投递天然满足 post+consume（收到即消费、无残留）。
 */
@Singleton
class VideoDetailNavBus @Inject constructor() {

    sealed interface NavRequest {
        data object GoHome : NavRequest
        data class OpenSpace(val route: SpaceRoute) : NavRequest
        data object OpenDownloadCache : NavRequest
        data class StartDownload(val request: VideoDownloadRequest) : NavRequest
    }

    private val channel = Channel<NavRequest>(capacity = Channel.UNLIMITED)

    val requests: Flow<NavRequest> = channel.receiveAsFlow()

    /** 详情 Activity 在 finish 前投递；无论 MainActivity 是否在前台都不丢失。 */
    fun post(request: NavRequest) {
        channel.trySend(request)
    }
}
