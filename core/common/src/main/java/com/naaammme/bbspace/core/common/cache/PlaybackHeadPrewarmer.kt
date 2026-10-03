package com.naaammme.bbspace.core.common.cache

/**
 * 起播头部字节预热（PB-04）：把某支视频的**头部若干字节**先写进播放字节缓存，
 * 让"真正开播"少等一段网络。
 *
 * 契约：
 * - **尽力而为**：失败一律吞掉，绝不冒泡成播放/预取的错误（与 `preloadPlayUrl` 同口径）；
 * - **可取消**：用户快速连续翻页时随协程取消；
 * - **自带门控**：是否预热（如"仅 Wi-Fi"）由实现内部的判据决定，调用方只管把
 *   网络类型传进来 —— 门控只有一处，不会被某个调用点漏掉。
 */
interface PlaybackHeadPrewarmer {

    suspend fun prewarm(
        videoUrl: String?,
        audioUrl: String?,
        isUnmetered: Boolean
    )
}
