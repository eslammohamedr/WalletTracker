package com.example.wallettrackers

import com.example.wallettrackers.repository.RecordSnapshotSyncPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSnapshotSyncPolicyTest {
    @Test
    fun `cached query snapshot is not authoritative for local records`() {
        assertFalse(RecordSnapshotSyncPolicy.shouldApply(isFromCache = true))
    }

    @Test
    fun `server-confirmed query snapshot can replace local records`() {
        assertTrue(RecordSnapshotSyncPolicy.shouldApply(isFromCache = false))
    }
}
