package com.example.wallettrackers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.db.AccountEntity
import com.example.wallettrackers.db.RecordEntity
import com.example.wallettrackers.db.WalletDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserScopedRoomCacheTest {
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            WalletDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun accountAndRecordQueriesAndSyncReplacementStayWithinUser() = runBlocking {
        val accounts = database.accountDao()
        val records = database.recordDao()
        accounts.insertAll(
            listOf(
                AccountEntity(id = "shared-account-id", userId = "user-a", name = "A"),
                AccountEntity(id = "shared-account-id", userId = "user-b", name = "B")
            )
        )
        records.insertAll(
            listOf(
                RecordEntity(id = "shared-record-id", userId = "user-a", amount = "10"),
                RecordEntity(id = "shared-record-id", userId = "user-b", amount = "20")
            )
        )

        records.replaceAllForUser("user-a", listOf(RecordEntity(id = "shared-record-id", userId = "user-a", amount = "30")))
        accounts.replaceAllForUser("user-a", listOf(AccountEntity(id = "shared-account-id", userId = "user-a", name = "A2")))

        assertEquals(listOf("A2"), accounts.getAll("user-a").first().map { it.name })
        assertEquals(listOf("B"), accounts.getAll("user-b").first().map { it.name })
        assertEquals(listOf("30"), records.getAll("user-a").first().map { it.amount })
        assertEquals(listOf("20"), records.getAll("user-b").first().map { it.amount })

        records.deleteAllForUser("user-a")
        accounts.deleteAllForUser("user-a")
        assertEquals(listOf("B"), accounts.getAll("user-b").first().map { it.name })
        assertEquals(listOf("20"), records.getAll("user-b").first().map { it.amount })
    }

    @Test
    fun versionThreeMigrationPreservesBothUsersRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "wallet-migration-test.db"
        context.deleteDatabase(databaseName)
        val legacyDatabase = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacyDatabase.version = 3
        legacyDatabase.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, accountType TEXT NOT NULL, last4Digits TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, color INTEGER NOT NULL, userId TEXT NOT NULL, creditLimit REAL, billingDay INTEGER, isArchived INTEGER NOT NULL, sortOrder INTEGER NOT NULL)")
        legacyDatabase.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, accountName TEXT NOT NULL, category TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL, userId TEXT NOT NULL, balanceAfter TEXT NOT NULL, balanceBefore TEXT NOT NULL, smsId TEXT, comment TEXT NOT NULL, receiptUrl TEXT NOT NULL, transferDestinationAmount TEXT NOT NULL)")
        legacyDatabase.execSQL("INSERT INTO accounts VALUES ('account-a', 'A', 'Debit', '1111', '100', 'EGP', 0, 'user-a', NULL, NULL, 0, 0)")
        legacyDatabase.execSQL("INSERT INTO accounts VALUES ('account-b', 'B', 'Debit', '2222', '200', 'EGP', 0, 'user-b', NULL, NULL, 0, 0)")
        legacyDatabase.execSQL("INSERT INTO records VALUES ('record-a', 'account-a', 'A', 'Groceries', '10', 'EGP', 'Expense', 1, 'user-a', '', '', NULL, '', '', '')")
        legacyDatabase.execSQL("INSERT INTO records VALUES ('record-b', 'account-b', 'B', 'Groceries', '20', 'EGP', 'Expense', 2, 'user-b', '', '', NULL, '', '', '')")
        legacyDatabase.close()

        val migratedDatabase = Room.databaseBuilder(context, WalletDatabase::class.java, databaseName)
            .addMigrations(WalletDatabase.MIGRATION_3_4)
            .build()
        try {
            assertEquals("A", migratedDatabase.accountDao().getAll("user-a").first().single().name)
            assertEquals("B", migratedDatabase.accountDao().getAll("user-b").first().single().name)
            assertEquals("10", migratedDatabase.recordDao().getAll("user-a").first().single().amount)
            assertEquals("20", migratedDatabase.recordDao().getAll("user-b").first().single().amount)
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}
