package com.example.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.permission.AutonomyMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip check for the merged [AuditRecord]: write via [RoomAuditLog.record], read back via
 * [RoomAuditLog.recent], and confirm ordering (newest first) plus every field — enums included —
 * survives the Room [AuditConverters] round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAuditLogTest {

    @Test
    fun `recent returns entries newest-first with fields intact`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AuditDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val log = RoomAuditLog(db.auditDao())

        val first = AuditRecord(
            id = "entry-1",
            timestampMs = 1_000L,
            mode = AutonomyMode.ASK,
            actionType = "TAP",
            targetApp = "com.example.target",
            paramsHash = "hash-1",
            decision = "Confirm",
            outcome = AuditOutcome.ALLOWED_CONFIRMED
        )
        val second = AuditRecord(
            id = "entry-2",
            timestampMs = 2_000L,
            mode = AutonomyMode.AUTO,
            actionType = "OPEN_APP",
            targetApp = null,
            paramsHash = "hash-2",
            decision = "Allow",
            outcome = AuditOutcome.BLOCKED
        )

        log.record(first)
        log.record(second)

        val recent = log.recent(2)

        assertEquals(listOf(second, first), recent)

        db.close()
    }
}
