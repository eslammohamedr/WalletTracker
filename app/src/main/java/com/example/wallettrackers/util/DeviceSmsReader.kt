package com.example.wallettrackers.util

import android.content.Context
import android.provider.Telephony
import java.util.Date

data class DeviceSms(
    val id: String,
    val sender: String,
    val body: String,
    val date: Date
)

object DeviceSmsReader {

    /** Reads all SMS from the device inbox, sorted oldest-first for chronological processing. */
    fun readAll(context: Context): List<DeviceSms> {
        val result = mutableListOf<DeviceSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.Inbox._ID,
                Telephony.Sms.Inbox.ADDRESS,
                Telephony.Sms.Inbox.BODY,
                Telephony.Sms.Inbox.DATE
            ),
            null, null,
            "${Telephony.Sms.Inbox.DATE} ASC"
        )?.use { cursor ->
            val idIdx   = cursor.getColumnIndex(Telephony.Sms.Inbox._ID)
            val addrIdx = cursor.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.Inbox.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.Inbox.DATE)
            while (cursor.moveToNext()) {
                result.add(DeviceSms(
                    id     = cursor.getString(idIdx)   ?: continue,
                    sender = cursor.getString(addrIdx) ?: "",
                    body   = cursor.getString(bodyIdx) ?: "",
                    date   = Date(cursor.getLong(dateIdx))
                ))
            }
        }
        return result
    }
}
