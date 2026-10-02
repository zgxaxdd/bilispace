package com.naaammme.bbspace.core.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(item: SearchHistoryEntity): Long

    @Query(
        """
        UPDATE search_history
        SET searchCount = searchCount + 1,
            updatedAt = :updatedAt
        WHERE keyword = :keyword
        """
    )
    abstract suspend fun bump(
        keyword: String,
        updatedAt: Long
    )

    @Transaction
    open suspend fun record(
        keyword: String,
        updatedAt: Long
    ) {
        val rowId = insert(
            SearchHistoryEntity(
                keyword = keyword,
                searchCount = 1,
                updatedAt = updatedAt
            )
        )
        if (rowId == -1L) {
            bump(keyword, updatedAt)
        }
    }

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    abstract suspend fun deleteByKeyword(keyword: String)

    /**
     * AP-23：淘汰超出上限的最旧条目（PlaybackHistoryDao.trimByUid 同款范式）。
     * 排序口径与读取一致（updatedAt DESC, searchCount DESC, keyword ASC）——
     * "留下来的"就是"读出来的"，两条口径漂移会造成"读不到刚删的、删掉要读的"。
     */
    @Query(
        """
        DELETE FROM search_history
        WHERE keyword NOT IN (
            SELECT keyword FROM search_history
            ORDER BY updatedAt DESC, searchCount DESC, keyword ASC
            LIMIT :limit
        )
        """
    )
    protected abstract suspend fun trimToLimit(limit: Int)

    /** AP-23：插入与淘汰同事务 —— 表内条数恒 ≤ [limit]，读取代价不再随历史长度线性增长。 */
    @Transaction
    open suspend fun recordAndTrim(
        keyword: String,
        updatedAt: Long,
        limit: Int
    ) {
        record(keyword, updatedAt)
        trimToLimit(limit)
    }

    @Query(
        """
        SELECT keyword
        FROM search_history
        ORDER BY updatedAt DESC, searchCount DESC, keyword ASC
        LIMIT :limit
        """
    )
    abstract fun observeTopKeywordsByTime(limit: Int): Flow<List<String>>

    @Query(
        """
        SELECT keyword
        FROM search_history
        ORDER BY searchCount DESC, updatedAt DESC, keyword ASC
        LIMIT :limit
        """
    )
    abstract fun observeTopKeywordsByHot(limit: Int): Flow<List<String>>
}
