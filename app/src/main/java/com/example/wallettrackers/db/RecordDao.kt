package com.example.wallettrackers.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Query("SELECT * FROM records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<RecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<RecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecordEntity)

    @Update
    suspend fun update(record: RecordEntity)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE smsId = :smsId)")
    suspend fun existsBySmsId(smsId: String): Boolean

    @Query("SELECT * FROM records WHERE smsId = :smsId LIMIT 1")
    suspend fun findBySmsId(smsId: String): RecordEntity?

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}
