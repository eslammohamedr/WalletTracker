package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record

object TransferAmountResolver {
    private val legacyReceivedAmount = Regex(
        """(?:^|\()Received:\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s+[A-Z]{3}\b""",
        RegexOption.IGNORE_CASE
    )

    fun destinationAmount(record: Record): Double {
        record.transferDestinationAmount.toDoubleOrNull()?.let { return it }
        legacyReceivedAmount.find(record.comment)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()?.let { return it }
        return record.amount.toDoubleOrNull() ?: 0.0
    }
}
