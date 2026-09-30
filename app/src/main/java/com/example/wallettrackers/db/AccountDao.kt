package com.example.wallettrackers.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE userId = :userId ORDER BY sortOrder ASC")
    fun getAll(userId: String): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id AND userId = :userId")
    suspend fun deleteById(id: String, userId: String)

    @Query("DELETE FROM accounts WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @androidx.room.Transaction
    suspend fun replaceAllForUser(userId: String, accounts: List<AccountEntity>) {
        deleteAllForUser(userId)
        if (accounts.isNotEmpty()) insertAll(accounts)
    }
}
