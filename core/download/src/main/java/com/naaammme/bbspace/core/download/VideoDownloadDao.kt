package com.naaammme.bbspace.core.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDownloadDao {
    @Query("SELECT * FROM video_download_task ORDER BY created_at_ms DESC, id DESC")
    fun observeAll(): Flow<List<VideoDownloadEntity>>

    @Query("SELECT * FROM video_download_task WHERE id = :taskId")
    fun observe(taskId: Long): Flow<VideoDownloadEntity?>

    @Query("SELECT * FROM video_download_task WHERE id = :taskId")
    suspend fun find(taskId: Long): VideoDownloadEntity?

    @Query(
        "SELECT * FROM video_download_task " +
            "WHERE status = :status " +
            "ORDER BY created_at_ms ASC, id ASC " +
            "LIMIT 1"
    )
    suspend fun findFirstByStatus(status: String): VideoDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: VideoDownloadEntity): Long

    @Query(
        "SELECT id FROM video_download_task " +
            "WHERE biz = :biz " +
            "AND aid = :aid " +
            "AND cid = :cid " +
            "AND ep_id = :epId " +
            "AND season_id = :seasonId " +
            "AND kind = :kind " +
            "LIMIT 1"
    )
    suspend fun findExistingId(
        biz: String,
        aid: Long,
        cid: Long,
        epId: Long,
        seasonId: Long,
        kind: String
    ): Long?

    @Update
    suspend fun update(task: VideoDownloadEntity)

    /**
     * 入队 + 冲突回读放在**同一个写事务**里（ST-13）。
     *
     * 原先是仓库里两次独立调用：`insertIgnore` 返回 -1（唯一约束冲突）之后再 `findExistingId`。
     * 两条语句各自一个隐式事务，中间那行可能被并发 `delete` 掉，于是"冲突了却查不到行"，
     * 老代码在那里 `error(...)` 抛**裸 IllegalStateException** ——
     * 而 `DownloadViewModel.enqueue` 的调用点在 `viewModelScope.launch` 里**没有 try/catch**，
     * 所以一次并发删除就是一次崩溃，不是"看起来不太好的报错"。
     *
     * 现在把两步包进一个事务（SQLite 串行化写事务，中间插不进别人的 delete），
     * 并把"仍不一致"降级成返回值（`DownloadEnqueueOutcome(taskId = -1, created = false)`）：
     * 抛不抛、怎么抛，由调用方决定。
     */
    @Transaction
    suspend fun insertAndFind(
        task: VideoDownloadEntity,
        biz: String,
        aid: Long,
        cid: Long,
        epId: Long,
        seasonId: Long,
        kind: String
    ): DownloadEnqueueOutcome {
        val inserted = insertIgnore(task)
        if (inserted > 0L) return DownloadEnqueueOutcome(taskId = inserted, created = true)
        val existing = findExistingId(
            biz = biz,
            aid = aid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
            kind = kind
        )
        return if (existing != null) {
            DownloadEnqueueOutcome(taskId = existing, created = false)
        } else {
            DownloadEnqueueOutcome(taskId = -1L, created = false)
        }
    }

    @Query("DELETE FROM video_download_task WHERE id = :taskId")
    suspend fun delete(taskId: Long)

    /** 孤儿目录清理用：只问"哪些 id 还有行"，不读整表（见 sweepOrphanTaskDirs）。 */
    @Query("SELECT id FROM video_download_task")
    suspend fun allIds(): List<Long>

    @Query(
        "UPDATE video_download_task " +
            "SET status = :pausedStatus, error = :message, video_path = NULL, audio_path = NULL, duration_ms = 0 " +
            "WHERE status = :runningStatus"
    )
    suspend fun pauseRunningTasks(
        runningStatus: String,
        pausedStatus: String,
        message: String
    )
}

/**
 * [VideoDownloadDao.insertAndFind] 的结果。
 *
 * - `created = true` → 本次真的建了这一行
 * - `created = false, taskId > 0` → 命中已存在的行（正常去重）
 * - `created = false, taskId = -1` → 冲突却查不到行（并发删除的窄窗口），由调用方决定重试与提示
 */
data class DownloadEnqueueOutcome(
    val taskId: Long,
    val created: Boolean
)
