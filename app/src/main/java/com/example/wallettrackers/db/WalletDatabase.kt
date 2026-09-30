package com.example.wallettrackers.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RecordEntity::class, AccountEntity::class], version = 4, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var INSTANCE: WalletDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE records ADD COLUMN balanceBefore TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE records ADD COLUMN transferDestinationAmount TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE accounts_new (id TEXT NOT NULL, name TEXT NOT NULL, accountType TEXT NOT NULL, last4Digits TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, color INTEGER NOT NULL, userId TEXT NOT NULL, creditLimit REAL, billingDay INTEGER, isArchived INTEGER NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(id, userId))")
                database.execSQL("INSERT INTO accounts_new SELECT id, name, accountType, last4Digits, amount, currency, color, userId, creditLimit, billingDay, isArchived, sortOrder FROM accounts")
                database.execSQL("DROP TABLE accounts")
                database.execSQL("ALTER TABLE accounts_new RENAME TO accounts")

                database.execSQL("CREATE TABLE records_new (id TEXT NOT NULL, accountId TEXT NOT NULL, accountName TEXT NOT NULL, category TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL, userId TEXT NOT NULL, balanceAfter TEXT NOT NULL, balanceBefore TEXT NOT NULL, smsId TEXT, comment TEXT NOT NULL, receiptUrl TEXT NOT NULL, transferDestinationAmount TEXT NOT NULL, PRIMARY KEY(id, userId))")
                database.execSQL("INSERT INTO records_new SELECT id, accountId, accountName, category, amount, currency, type, timestamp, userId, balanceAfter, balanceBefore, smsId, comment, receiptUrl, transferDestinationAmount FROM records")
                database.execSQL("DROP TABLE records")
                database.execSQL("ALTER TABLE records_new RENAME TO records")
            }
        }

        fun getInstance(context: Context): WalletDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WalletDatabase::class.java,
                    "wallet_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
