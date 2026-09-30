package com.example.wallettrackers.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Query("SELECT * FROM records WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAll(userId: String): Flow<List<RecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<RecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecordEntity)

    @Update
    suspend fun update(record: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id AND userId = :userId")
    suspend fun deleteById(id: String, userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE smsId = :smsId AND userId = :userId)")
    suspend fun existsBySmsId(smsId: String, userId: String): Boolean

    @Query("SELECT * FROM records WHERE smsId = :smsId AND userId = :userId LIMIT 1")
    suspend fun findBySmsId(smsId: String, userId: String): RecordEntity?

    @Query("DELETE FROM records WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Transaction
    suspend fun replaceAllForUser(userId: String, records: List<RecordEntity>) {
        deleteAllForUser(userId)
        if (records.isNotEmpty()) insertAll(records)
    }
}
