package com.naaammme.bbspace.infra.player

sealed interface EngineSource {

    data class LiveFlv(
        val url: String,
        val backupUrls: List<String> = emptyList()
    ) : EngineSource

    data class LocalMerged(
        val videoUrl: String,
        val audioUrl: String? = null
    ) : EngineSource

    data class SingleFileDash(
        val videoUrl: String,
        val audioUrl: String? = null,
        val videoSampleMimeType: String? = null,
        val audioSampleMimeType: String? = null,
        val durationMs: Long? = null,
        // 打开失败时按顺序尝试的备用线路（首元素为主线路本身）
        val videoBackupUrls: List<String> = emptyList(),
        val audioBackupUrls: List<String> = emptyList()
    ) : EngineSource

    data class ProgressiveSegment(
        val url: String,
        val durationMs: Long?
    )

    data class Progressive(val segments: List<ProgressiveSegment>) : EngineSource
}
