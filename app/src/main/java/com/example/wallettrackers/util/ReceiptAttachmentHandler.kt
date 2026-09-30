package com.example.wallettrackers.util

import android.content.Context
import android.net.Uri
import com.example.wallettrackers.model.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ReceiptAttachmentOutcome {
    data class Uploaded(val url: String) : ReceiptAttachmentOutcome
    data class SavedLocally(val uri: Uri) : ReceiptAttachmentOutcome
    data object Failed : ReceiptAttachmentOutcome
}

class ReceiptAttachmentHandler(
    context: Context,
    private val receiptStore: LocalReceiptStore = LocalReceiptStore(context)
) {
    suspend fun attach(
        userId: String,
        record: Record,
        source: Uri,
        upload: suspend (String, String, Uri) -> String?,
        saveRemoteRecord: suspend (Record) -> Unit,
        saveLocalRecord: suspend (Record) -> Unit
    ): ReceiptAttachmentOutcome {
        val remoteUrl = runCatching { upload(userId, record.id, source) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (remoteUrl != null && runCatching { saveRemoteRecord(record.copy(receiptUrl = remoteUrl)) }.isSuccess) {
            return ReceiptAttachmentOutcome.Uploaded(remoteUrl)
        }

        val localUri = withContext(Dispatchers.IO) {
            receiptStore.copyReceipt(userId, record.id, source)
        } ?: return ReceiptAttachmentOutcome.Failed
        return if (runCatching { saveLocalRecord(record.copy(receiptUrl = localUri.toString())) }.isSuccess) {
            ReceiptAttachmentOutcome.SavedLocally(localUri)
        } else {
            withContext(Dispatchers.IO) { receiptStore.deleteReceipt(localUri.toString()) }
            ReceiptAttachmentOutcome.Failed
        }
    }
}
