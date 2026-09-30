package com.example.wallettrackers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.LocalReceiptStore
import com.example.wallettrackers.util.ReceiptAttachmentHandler
import com.example.wallettrackers.util.ReceiptAttachmentOutcome
import com.example.wallettrackers.model.Record
import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class LocalReceiptStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun copiesReceiptIntoUserScopedPrivateStorageAndDeletesItSafely() {
        val source = File(context.cacheDir, "receipt-source-${System.nanoTime()}.jpg")
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val store = LocalReceiptStore(context)

        val firstReceipt = store.copyReceipt("user-a", "record-1", Uri.fromFile(source))
        val secondUserReceipt = store.copyReceipt("user-b", "record-1", Uri.fromFile(source))

        assertNotNull(firstReceipt)
        assertNotNull(secondUserReceipt)
        assertTrue(LocalReceiptStore.isLocalReceipt(firstReceipt.toString()))
        assertNotEquals(firstReceipt, secondUserReceipt)
        assertTrue(File(firstReceipt!!.path!!).exists())
        assertTrue(store.deleteReceipt(firstReceipt.toString()))
        assertFalse(File(firstReceipt.path!!).exists())
        assertFalse(store.deleteReceipt(source.toURI().toString()))
        store.deleteReceipt(secondUserReceipt.toString())
        source.delete()
    }

    @Test
    fun localReceiptUriNeverSyncsAndSurvivesRemoteRecordRefresh() {
        val localUri = Uri.fromFile(File(context.filesDir, "receipts/user/receipt.img")).toString()

        assertTrue(LocalReceiptStore.cloudSafeReceiptUrl(localUri).isEmpty())
        assertTrue(LocalReceiptStore.receiptUrlAfterSync("", localUri) == localUri)
        assertTrue(LocalReceiptStore.receiptUrlAfterSync("https://receipt.example/photo.jpg", "") == "https://receipt.example/photo.jpg")
    }

    @Test
    fun failedCloudUploadFallsBackToLocalRecordAttachment() = runBlocking {
        val source = File(context.cacheDir, "receipt-fallback-${System.nanoTime()}.jpg")
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val record = Record(id = "record-fallback", category = "Groceries", amount = "10.00")
        var remoteRecord: Record? = null
        var localRecord: Record? = null

        val outcome = ReceiptAttachmentHandler(context).attach(
            userId = "receipt-test-user",
            record = record,
            source = Uri.fromFile(source),
            upload = { _, _, _ -> throw IOException("Storage returned HTTP 404") },
            saveRemoteRecord = { remoteRecord = it },
            saveLocalRecord = { localRecord = it }
        )

        assertTrue(outcome is ReceiptAttachmentOutcome.SavedLocally)
        assertNull(remoteRecord)
        assertNotNull(localRecord)
        assertTrue(LocalReceiptStore.isLocalReceipt(localRecord!!.receiptUrl))
        assertTrue(File(Uri.parse(localRecord!!.receiptUrl).path!!).exists())
        LocalReceiptStore(context).deleteReceipt(localRecord!!.receiptUrl)
        source.delete()
        Unit
    }

    @Test
    fun localCopyIsRemovedWhenRecordPersistenceFails() = runBlocking {
        val source = File(context.cacheDir, "receipt-save-failure-${System.nanoTime()}.jpg")
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val receiptDirectory = File(context.filesDir, "receipts")
        val existingFiles = receiptDirectory.takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.map { it.canonicalPath }
            ?.toSet()
            .orEmpty()

        val outcome = ReceiptAttachmentHandler(context).attach(
            userId = "receipt-save-failure-user",
            record = Record(id = "record-save-failure-${System.nanoTime()}", amount = "10.00"),
            source = Uri.fromFile(source),
            upload = { _, _, _ -> null },
            saveRemoteRecord = {},
            saveLocalRecord = { throw IOException("Room write failed") }
        )

        val remainingFiles = receiptDirectory.takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.map { it.canonicalPath }
            ?.toSet()
            .orEmpty()
        assertTrue(outcome is ReceiptAttachmentOutcome.Failed)
        assertTrue(remainingFiles.subtract(existingFiles).isEmpty())
        source.delete()
        Unit
    }

    @Test
    fun successfulCloudUploadUpdatesTheRemoteReceiptWithoutLocalFallback() = runBlocking {
        val source = File(context.cacheDir, "receipt-cloud-${System.nanoTime()}.jpg")
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val record = Record(id = "record-cloud", category = "Groceries", amount = "10.00")
        var remoteRecord: Record? = null
        var localSaveCalled = false

        val outcome = ReceiptAttachmentHandler(context).attach(
            userId = "receipt-test-user",
            record = record,
            source = Uri.fromFile(source),
            upload = { _, _, _ -> "https://receipt.example/record-cloud.jpg" },
            saveRemoteRecord = { remoteRecord = it },
            saveLocalRecord = { localSaveCalled = true }
        )

        assertTrue(outcome is ReceiptAttachmentOutcome.Uploaded)
        assertEquals("https://receipt.example/record-cloud.jpg", remoteRecord?.receiptUrl)
        assertFalse(localSaveCalled)
        source.delete()
        Unit
    }
}
