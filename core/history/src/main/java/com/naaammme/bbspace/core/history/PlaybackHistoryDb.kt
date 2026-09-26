package com.naaammme.bbspace.core.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PlaybackHistoryEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PlaybackHistoryDb : RoomDatabase() {
    abstract fun dao(): PlaybackHistoryDao
}
