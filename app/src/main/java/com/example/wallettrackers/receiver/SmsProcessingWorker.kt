package com.example.wallettrackers.receiver

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth

class SmsProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString("user_id") ?: run {
            Log.e(TAG, "Skipping malformed queued SMS without a user ID")
            return Result.success()
        }
        val workKey = inputData.getString("sms_id") ?: if (inputData.getBoolean("inbox_fallback", false)) {
            "inbox-fallback-${id}"
        } else {
            "malformed-${id}"
        }
        val signedInUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (signedInUserId != userId) {
            Log.w(TAG, "SMS_WORK_SKIPPED key=$workKey because the signed-in user changed")
            return Result.success()
        }

        return try {
            val receiver = SmsReceiver()
            if (inputData.getBoolean("inbox_fallback", false)) {
                receiver.processRecentSmsInbox(applicationContext, userId)
            } else {
                val body = inputData.getString("body") ?: return malformedInput()
                val smsId = inputData.getString("sms_id") ?: return malformedInput()
                val sender = inputData.getString("sender").orEmpty()
                val timestamp = inputData.getLong("timestamp", 0L)
                if (timestamp <= 0L) return malformedInput()
                receiver.processQueuedSms(applicationContext, userId, body, smsId, timestamp, sender)
            }
            Log.i(TAG, "SMS_WORK_COMPLETED key=$workKey")
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Queued SMS processing failed on attempt ${runAttemptCount + 1}", error)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Log.e(TAG, "SMS_WORK_DROPPED key=$workKey after ${runAttemptCount + 1} attempts")
                Result.success()
            }
        }
    }

    private fun malformedInput(): Result {
        Log.e(TAG, "Skipping malformed queued SMS work input")
        return Result.success()
    }

    private companion object {
        const val TAG = "SmsProcessingWorker"
        const val MAX_RETRIES = 5
    }
}
