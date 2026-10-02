package com.naaammme.bbspace.core.history

import android.content.Context
import androidx.room.Room
import com.naaammme.bbspace.core.model.PlaybackHistory
import com.naaammme.bbspace.core.model.PlaybackHistoryKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class PlaybackHistoryRepository @Inject constructor(
    private val dao: PlaybackHistoryDao
) {

    suspend fun upsertVideo(item: PlaybackHistory) {
        dao.upsertAndTrim(item.toEntity(), MAX_VIDEOS_PER_UID)
    }

    suspend fun getVideo(
        uid: Long,
        key: String
    ): PlaybackHistory? {
        return dao.getById(PlaybackHistoryKey.videoId(uid, key))?.toModel()
    }

    fun observeVideos(): Flow<List<PlaybackHistory>> {
        // AP-24：同值重复写入会驱动下游重组 —— 结构相等去重拦住"内容没变的发射"
        return dao.observeVideos().map { list -> list.map(PlaybackHistoryEntity::toModel) }
            .distinctUntilChanged()
    }

    suspend fun deleteVideo(id: String) {
        dao.deleteById(id)
    }

    suspend fun clearVideos() {
        dao.clear()
    }

    private companion object {
        const val MAX_VIDEOS_PER_UID = 1000
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackHistoryModule {
    @Provides
    @Singleton
    fun providePlaybackHistoryDb(
        @ApplicationContext context: Context
    ): PlaybackHistoryDb {
        return Room.databaseBuilder(
            context,
            PlaybackHistoryDb::class.java,
            "playback_history.db"
        )
            .addMigrations(*PlaybackHistoryMigrations.ALL)
            .build()
    }

    @Provides
    fun providePlaybackHistoryDao(db: PlaybackHistoryDb): PlaybackHistoryDao {
        return db.dao()
    }
}
