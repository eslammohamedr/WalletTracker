package com.example.wallettrackers

import com.example.wallettrackers.util.SmsMultipartAssembler
import com.example.wallettrackers.util.SmsPduPart
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsMultipartAssemblerTest {
    @Test
    fun assemblesPartsFromOneSenderBeforeParsing() {
        val result = SmsMultipartAssembler.assemble(
            listOf(
                SmsPduPart("1861", 1000L, "Debited EGP 0.15 at B.T"),
                SmsPduPart("1861", 1000L, "ECH on 25/09/2026")
            )
        )

        assertEquals(1, result.size)
        assertEquals("Debited EGP 0.15 at B.TECH on 25/09/2026", result.single().body)
    }

    @Test
    fun identicalBodiesAreNotDuplicatedWhenPlatformAlreadyProvidesTheJoinedText() {
        val joinedBody = "Debited EGP 0.15 at B.TECH"
        val result = SmsMultipartAssembler.assemble(
            listOf(
                SmsPduPart("1861", 1000L, joinedBody),
                SmsPduPart("1861", 1000L, joinedBody)
            )
        )

        assertEquals(joinedBody, result.single().body)
    }

    @Test
    fun keepsDifferentSendersAsSeparateMessages() {
        val result = SmsMultipartAssembler.assemble(
            listOf(
                SmsPduPart("1861", 1000L, "first"),
                SmsPduPart("BANK", 1000L, "second")
            )
        )

        assertEquals(listOf("first", "second"), result.map { it.body })
    }
}
