package com.example.wallettrackers.util

import java.security.MessageDigest

object SmsBroadcastId {
    fun create(timestampMillis: Long, sender: String, body: String): String {
        val content = "$sender\u0000$body".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "${timestampMillis}_$digest"
    }

    fun timestampMillis(smsId: String?): Long? =
        smsId?.substringBefore('_')?.toLongOrNull()
}
