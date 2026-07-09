package com.example.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MemoryScoring] — the recall math that decides which memories come back.
 * No Room/Android, so no Robolectric. Guards the money paths: embedding round-trip, cosine,
 * keyword overlap, recency decay, and that the blended scores rank the way recall should.
 */
class MemoryScoringTest {

    @Test
    fun `embedding round-trips through the BLOB packing`() {
        val v = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f, 100f)
        val back = MemoryScoring.bytesToFloats(MemoryScoring.floatsToBytes(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 1e-6f)
    }

    @Test
    fun `cosine is 1 for identical, ~0 for orthogonal, and 0 when a vector is missing`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0, MemoryScoring.cosine(a, a), 1e-9)
        assertEquals(0.0, MemoryScoring.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(0.0, MemoryScoring.cosine(null, a), 0.0)
        assertEquals(0.0, MemoryScoring.cosine(a, floatArrayOf(1f, 2f)), 0.0) // length mismatch
    }

    @Test
    fun `keyword overlap is fraction of query tokens present`() {
        // "wife" and "name" both present -> 2/2; short tokens (<=2 chars) are dropped.
        assertEquals(1.0, MemoryScoring.keyword("wife name", "the user's wife name is Priya"), 1e-9)
        assertEquals(0.5, MemoryScoring.keyword("wife husband", "the user's wife is Priya"), 1e-9)
        assertEquals(0.0, MemoryScoring.keyword("", "anything"), 0.0)
    }

    @Test
    fun `recency decays monotonically and halves at the half-life`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(1.0, MemoryScoring.recency(0), 1e-9)
        assertEquals(0.5, MemoryScoring.recency(30L * day), 1e-3) // 30-day half-life
        assertTrue(MemoryScoring.recency(1L * day) > MemoryScoring.recency(60L * day))
    }

    @Test
    fun `an orthogonal embedding contributes no semantic baseline`() {
        // Regression guard: previously sem used (cosine+1)/2, so an unrelated (orthogonal) embedded
        // fact got a ~0.5 semantic baseline that a no-embedding fact lacked. With max(cosine,0)
        // both must score identically when nothing else differs (kw=0 here: "zzz" not in the text).
        val now = 1_000_000_000_000L
        val qEmb = floatArrayOf(1f, 0f)
        val orthogonal = MemoryScoring.floatsToBytes(floatArrayOf(0f, 1f))
        fun score(emb: ByteArray?) = MemoryScoring.scoreFact(
            query = "zzz", qEmb = qEmb, text = "unrelated text here", emb = emb,
            lastConfirmedAt = now, salience = 1.0, useCount = 0, now = now
        )
        assertEquals("orthogonal embedding must not outrank no-embedding", score(null), score(orthogonal), 1e-9)
    }

    @Test
    fun `a keyword-matching recent fact outranks an unrelated stale one without embeddings`() {
        val now = 1_000_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val relevant = MemoryScoring.scoreFact(
            query = "wife name", qEmb = null, text = "the user's wife is named Priya", emb = null,
            lastConfirmedAt = now - day, salience = 1.0, useCount = 3, now = now
        )
        val stale = MemoryScoring.scoreFact(
            query = "wife name", qEmb = null, text = "the user likes cricket", emb = null,
            lastConfirmedAt = now - 200 * day, salience = 1.0, useCount = 0, now = now
        )
        assertTrue("relevant ($relevant) must outrank stale ($stale)", relevant > stale)
    }
}
