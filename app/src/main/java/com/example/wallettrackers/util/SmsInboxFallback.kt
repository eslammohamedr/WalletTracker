package com.example.wallettrackers.util

import kotlinx.coroutines.delay

data class InboxSmsCandidate(
    val id: String,
    val address: String,
    val body: String,
    val timestampMillis: Long
)

object SmsInboxFallback {
    const val MAX_AGE_MILLIS = 30_000L
    const val INBOX_RETRY_ATTEMPTS = 10
    const val INBOX_RETRY_DELAY_MILLIS = 300L
    private const val MAX_FUTURE_SKEW_MILLIS = 5_000L

    fun shouldReadInboxFallback(pduCount: Int): Boolean = pduCount == 0

    fun selectNewestRecent(
        candidates: List<InboxSmsCandidate>,
        nowMillis: Long,
        maxAgeMillis: Long = MAX_AGE_MILLIS
    ): InboxSmsCandidate? = candidates
        .asSequence()
        .filter { it.id.isNotBlank() && it.address.isNotBlank() && it.body.isNotBlank() }
        .filter {
            it.timestampMillis >= nowMillis - maxAgeMillis &&
                it.timestampMillis <= nowMillis + MAX_FUTURE_SKEW_MILLIS
        }
        .maxByOrNull { it.timestampMillis }

    fun selectRecentUnseen(
        candidates: List<InboxSmsCandidate>,
        nowMillis: Long,
        lastProcessedId: Long,
        maxAgeMillis: Long = MAX_AGE_MILLIS
    ): List<InboxSmsCandidate> = candidates
        .asSequence()
        .filter { it.id.isNotBlank() && it.address.isNotBlank() && it.body.isNotBlank() }
        .filter {
            it.timestampMillis >= nowMillis - maxAgeMillis &&
                it.timestampMillis <= nowMillis + MAX_FUTURE_SKEW_MILLIS
        }
        .filter { isUnseenInboxRow(it.id, lastProcessedId) }
        .sortedWith(compareBy<InboxSmsCandidate> { it.timestampMillis }.thenBy { it.id.toLongOrNull() ?: Long.MIN_VALUE })
        .toList()

    fun isUnseenInboxRow(id: String, lastProcessedId: Long): Boolean =
        id.toLongOrNull()?.let { it > lastProcessedId } == true

    suspend fun readRecentCandidatesWithRetry(
        readCandidates: suspend () -> List<InboxSmsCandidate>,
        maxAttempts: Int = INBOX_RETRY_ATTEMPTS,
        retryDelayMillis: Long = INBOX_RETRY_DELAY_MILLIS
    ): List<InboxSmsCandidate> {
        require(maxAttempts > 0)
        require(retryDelayMillis >= 0)
        repeat(maxAttempts - 1) {
            val candidates = readCandidates()
            if (candidates.isNotEmpty()) return candidates
            delay(retryDelayMillis)
        }
        return readCandidates()
    }
}
