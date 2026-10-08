package com.naaammme.bbspace.core.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadProgress
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.core.model.VideoDownloadTask
import com.naaammme.bbspace.core.model.VideoDownloadTaskStatus
import com.naaammme.bbspace.core.model.fallbackTitle

/**
 * 下载队列表。
 *
 * ## Q-6（2026-10-04 审查报告）：`status` / `created_at_ms` 为什么**不建索引**
 *
 * `VideoDownloadDao.findFirstByStatus`（`WHERE status = ? ORDER BY created_at_ms ASC, id ASC LIMIT 1`）
 * 与 `observeAll`（`ORDER BY created_at_ms DESC, id DESC`）都会全表扫描 + 排序。
 * 但本表存的是**用户自己的下载队列**：量级是几条到几十条（下载管理页一屏就装完），
 * 全表扫描的代价可忽略；而加索引会引入写放大，并且 **Room schema 变更要连带 bump 版本 +
 * 写迁移 + 更新 schema 快照**（`RoomSchemaGuardTest` / `RoomMigrationCopyListTest` 会红）。
 * 所以这里刻意只留唯一业务键索引，并把理由写在实体上 —— 真要支持"几千条任务"时再补
 * `Index(value = ["status", "created_at_ms"])` 与迁移，别默默加上。
 */
@Entity(
    tableName = "video_download_task",
    indices = [
        Index(
            value = ["biz", "aid", "cid", "ep_id", "season_id", "kind"],
            unique = true
        )
    ]
)
data class VideoDownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val cover: String?,
    @ColumnInfo(name = "owner_uid")
    val ownerUid: Long?,
    @ColumnInfo(name = "owner_name")
    val ownerName: String?,
    val biz: String,
    val aid: Long,
    val cid: Long,
    val bvid: String?,
    @ColumnInfo(name = "ep_id")
    val epId: Long,
    @ColumnInfo(name = "season_id")
    val seasonId: Long,
    val kind: String,
    @ColumnInfo(name = "video_quality")
    val videoQuality: Int,
    @ColumnInfo(name = "audio_quality")
    val audioQuality: Int,
    val status: String,
    @ColumnInfo(name = "progress_type")
    val progressType: String?,
    @ColumnInfo(name = "progress_label")
    val progressLabel: String?,
    @ColumnInfo(name = "done_bytes")
    val doneBytes: Long,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    val error: String?,
    @ColumnInfo(name = "video_path")
    val videoPath: String?,
    @ColumnInfo(name = "audio_path")
    val audioPath: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long
)

internal fun VideoDownloadEntity.toModel(): VideoDownloadTask {
    val resolvedKind = VideoDownloadKind.from(kind)
    return VideoDownloadTask(
        id = id,
        biz = PlayBiz.from(biz),
        aid = aid,
        cid = cid,
        bvid = bvid,
        epId = epId,
        seasonId = seasonId,
        kind = resolvedKind,
        videoQuality = videoQuality,
        audioQuality = audioQuality,
        title = title,
        cover = cover,
        ownerUid = ownerUid,
        ownerName = ownerName,
        status = VideoDownloadTaskStatus.valueOf(status),
        progress = toProgress(),
        error = error,
        videoPath = videoPath,
        audioPath = audioPath,
        durationMs = durationMs,
        createdAtMs = createdAtMs
    )
}

internal fun VideoDownloadRequest.toEntity(
    createdAtMs: Long
): VideoDownloadEntity {
    return VideoDownloadEntity(
        title = resolveTitle(),
        cover = meta.cover?.takeIf(String::isNotBlank),
        ownerUid = meta.ownerUid?.takeIf { it > 0L },
        ownerName = meta.ownerName?.takeIf(String::isNotBlank),
        biz = biz.name,
        aid = aid,
        cid = cid,
        bvid = bvid?.takeIf(String::isNotBlank),
        epId = epId,
        seasonId = seasonId,
        kind = kind.name,
        videoQuality = videoQuality,
        audioQuality = audioQuality,
        status = VideoDownloadTaskStatus.WAITING.name,
        progressType = null,
        progressLabel = null,
        doneBytes = 0L,
        totalBytes = 0L,
        error = null,
        videoPath = null,
        audioPath = null,
        durationMs = 0L,
        createdAtMs = createdAtMs
    )
}

private fun VideoDownloadEntity.toProgress(): VideoDownloadProgress? {
    return when (progressType) {
        null -> null
        ProgressType.PREPARING -> VideoDownloadProgress.Preparing
        ProgressType.DOWNLOADING -> VideoDownloadProgress.Downloading(
            label = progressLabel ?: "",
            doneBytes = doneBytes,
            totalBytes = totalBytes
        )
        ProgressType.DONE -> VideoDownloadProgress.Done
        else -> error("未知下载进度类型: $progressType")
    }
}

private fun VideoDownloadRequest.resolveTitle(): String {
    val raw = meta.title?.trim()
    return if (!raw.isNullOrBlank()) raw else fallbackTitle()
}

object ProgressType {
    const val PREPARING = "PREPARING"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
}
