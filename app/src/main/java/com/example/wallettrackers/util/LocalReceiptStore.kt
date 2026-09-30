package com.example.wallettrackers.util

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest

class LocalReceiptStore(context: Context) {
    private val appContext = context.applicationContext
    private val receiptsDirectory = File(appContext.filesDir, "receipts")

    fun copyReceipt(userId: String, recordId: String, source: Uri): Uri? {
        val extension = appContext.contentResolver.getType(source)
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.takeIf { it.isNotBlank() }
            ?: "img"
        val userDirectory = File(receiptsDirectory, hash(userId))
        if (!userDirectory.exists() && !userDirectory.mkdirs()) return null
        val destination = File(userDirectory, "${hash(recordId)}.$extension")
        val temporary = File(userDirectory, "${destination.name}.tmp")
        return try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (temporary.length() == 0L) return null
            if (destination.exists() && !destination.delete()) return null
            if (!temporary.renameTo(destination)) return null
            Uri.fromFile(destination)
        } catch (_: Exception) {
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deleteReceipt(receiptUri: String): Boolean {
        return try {
            val file = File(Uri.parse(receiptUri).path ?: return false).canonicalFile
            val directory = receiptsDirectory.canonicalFile
            file.path.startsWith(directory.path + File.separator) && file.delete()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun isLocalReceipt(receiptUri: String): Boolean =
            Uri.parse(receiptUri).scheme == "file" && "/receipts/" in (Uri.parse(receiptUri).path ?: "")

        fun cloudSafeReceiptUrl(receiptUri: String): String =
            if (isLocalReceipt(receiptUri)) "" else receiptUri

        fun receiptUrlAfterSync(remoteReceiptUri: String, localReceiptUri: String): String =
            if (isLocalReceipt(localReceiptUri)) localReceiptUri else remoteReceiptUri

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
