package com.example.wallettrackers.util

data class SmsPduPart(
    val sender: String,
    val timestampMillis: Long,
    val body: String
)

data class AssembledSms(
    val sender: String,
    val timestampMillis: Long,
    val body: String
)

object SmsMultipartAssembler {
    fun assemble(parts: List<SmsPduPart>): List<AssembledSms> {
        if (parts.isEmpty()) return emptyList()
        val groups = linkedMapOf<String, MutableList<SmsPduPart>>()
        parts.forEach { part ->
            groups.getOrPut(part.sender) { mutableListOf() }.add(part)
        }
        return groups.values.map { senderParts ->
            val first = senderParts.first()
            val bodies = senderParts.map { it.body }
            AssembledSms(
                sender = first.sender,
                timestampMillis = senderParts.minOf { it.timestampMillis },
                body = if (bodies.distinct().size == 1) bodies.first() else bodies.joinToString(separator = "")
            )
        }
    }
}
