package com.example.wallettrackers

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.db.toEntity
import com.example.wallettrackers.db.toModel
import com.example.wallettrackers.util.TransferAmountResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferAmountResolverTest {
    @Test
    fun persistedDestinationAmountIsPreferred() {
        val transfer = Record(
            amount = "0.10",
            comment = "Received: 0.09 EUR",
            transferDestinationAmount = "0.08"
        )

        assertEquals(0.08, TransferAmountResolver.destinationAmount(transfer), 0.0001)
    }

    @Test
    fun legacyFxCommentProvidesDestinationAmount() {
        val transfer = Record(amount = "0.10", comment = "Cafe transfer (Received: 1,234.56 EUR)")

        assertEquals(1234.56, TransferAmountResolver.destinationAmount(transfer), 0.0001)
    }

    @Test
    fun sameCurrencyLegacyTransferFallsBackToSourceAmount() {
        val transfer = Record(amount = "42.00", comment = "Internal transfer")

        assertEquals(42.0, TransferAmountResolver.destinationAmount(transfer), 0.0001)
    }

    @Test
    fun destinationAmountSurvivesRoomEntityMapping() {
        val transfer = Record(amount = "0.10", transferDestinationAmount = "0.09")

        assertEquals("0.09", transfer.toEntity().toModel().transferDestinationAmount)
    }
}
