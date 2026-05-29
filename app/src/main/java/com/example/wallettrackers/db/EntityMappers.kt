package com.example.wallettrackers.db

import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import java.util.Date

// ── Record ↔ RecordEntity ────────────────────────────────────────────────

fun Record.toEntity() = RecordEntity(
    id = id,
    accountId = accountId,
    accountName = accountName,
    category = category,
    amount = amount,
    currency = currency,
    type = type,
    timestamp = timestamp.time,
    userId = userId,
    balanceAfter = balanceAfter,
    smsId = smsId,
    comment = comment,
    receiptUrl = receiptUrl
)

fun RecordEntity.toModel() = Record(
    id = id,
    accountId = accountId,
    accountName = accountName,
    category = category,
    amount = amount,
    currency = currency,
    type = type,
    timestamp = Date(timestamp),
    userId = userId,
    balanceAfter = balanceAfter,
    smsId = smsId,
    comment = comment,
    receiptUrl = receiptUrl
)

// ── Account ↔ AccountEntity ─────────────────────────────────────────────

fun Account.toEntity() = AccountEntity(
    id = id,
    name = name,
    accountType = accountType,
    last4Digits = last4Digits,
    amount = amount,
    currency = currency,
    color = color,
    userId = userId,
    creditLimit = creditLimit,
    billingDay = billingDay,
    isArchived = isArchived,
    sortOrder = sortOrder
)

fun AccountEntity.toModel() = Account(
    id = id,
    name = name,
    accountType = accountType,
    last4Digits = last4Digits,
    amount = amount,
    currency = currency,
    color = color,
    userId = userId,
    creditLimit = creditLimit,
    billingDay = billingDay,
    isArchived = isArchived,
    sortOrder = sortOrder
)
