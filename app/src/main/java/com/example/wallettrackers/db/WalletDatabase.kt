package com.example.wallettrackers.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordEntity::class, AccountEntity::class], version = 1, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var INSTANCE: WalletDatabase? = null

        fun getInstance(context: Context): WalletDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WalletDatabase::class.java,
                    "wallet_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
