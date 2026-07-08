package com.example.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [ToolArgs] — the shared coercion for model-supplied tool args (Gemini may
 * send numbers as Double/Long/Int/String, booleans as Boolean/String).
 *
 * Reconciled from five byte-identical `intArg` copies (TapTool, InputTextTool, ScrollTool,
 * SwipeTool, LongPressTool) and two diverging boolean coercions (ScrollTool.boolArg,
 * DefaultPhoneControlExecutor.readBooleanArg) — see the semantics table in
 * .superpowers/sdd/task-8-report.md.
 */
class ToolArgsTest {

    // ------------------------------------------------------------------------
    // intArg — matches the original intArg copies: Number -> toInt(), numeric
    // String -> toIntOrNull() (strict; "3.5" is NOT accepted, unlike a toDouble-based
    // coercion), anything else -> null.
    // ------------------------------------------------------------------------

    @Test
    fun intArg_number_coercesViaToInt() {
        assertEquals(42, ToolArgs.intArg(mapOf("k" to 42), "k"))
        assertEquals(42, ToolArgs.intArg(mapOf("k" to 42.0), "k"))
        assertEquals(42, ToolArgs.intArg(mapOf("k" to 42L), "k"))
    }

    @Test
    fun intArg_numericString_parses() {
        assertEquals(42, ToolArgs.intArg(mapOf("k" to "42"), "k"))
        assertEquals(42, ToolArgs.intArg(mapOf("k" to "  42  "), "k"))
    }

    @Test
    fun intArg_decimalString_isRejected_strictIntOnly() {
        // toIntOrNull, not toDoubleOrNull -> "3.5" does not parse (matches every
        // existing copy's real behavior, not the brief's more lenient sketch).
        assertNull(ToolArgs.intArg(mapOf("k" to "3.5"), "k"))
    }

    @Test
    fun intArg_junkString_isNull() {
        assertNull(ToolArgs.intArg(mapOf("k" to "abc"), "k"))
    }

    @Test
    fun intArg_missingOrWrongType_isNull() {
        assertNull(ToolArgs.intArg(mapOf("k" to null), "k"))
        assertNull(ToolArgs.intArg(mapOf("k" to true), "k"))
        assertNull(ToolArgs.intArg(emptyMap(), "k"))
    }

    // ------------------------------------------------------------------------
    // boolArg — reconciled to the STRICTEST common behavior between ScrollTool.boolArg
    // (case-insensitive String, no Number) and DefaultPhoneControlExecutor.readBooleanArg
    // (case-sensitive String via toBooleanStrictOrNull, Number nonzero=true). Intersection:
    // Boolean passthrough, case-sensitive "true"/"false" String only, no Number branch.
    // ------------------------------------------------------------------------

    @Test
    fun boolArg_booleanPassthrough() {
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to true), "k"))
        assertEquals(false, ToolArgs.boolArg(mapOf("k" to false), "k"))
    }

    @Test
    fun boolArg_exactCaseStrings_parse() {
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to "true"), "k"))
        assertEquals(false, ToolArgs.boolArg(mapOf("k" to "false"), "k"))
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to "  true  "), "k"))
    }

    @Test
    fun boolArg_mixedCaseString_isNull_strictestCommon() {
        // ScrollTool's old copy lowercased first ("True" would have parsed there); the
        // strictest common (readBooleanArg's toBooleanStrictOrNull) does not.
        assertNull(ToolArgs.boolArg(mapOf("k" to "True"), "k"))
        assertNull(ToolArgs.boolArg(mapOf("k" to "FALSE"), "k"))
    }

    @Test
    fun boolArg_number_isNull_strictestCommon() {
        // readBooleanArg's old copy accepted Number (nonzero -> true); ScrollTool's copy
        // never did. Strictest common drops Number support.
        assertNull(ToolArgs.boolArg(mapOf("k" to 1), "k"))
        assertNull(ToolArgs.boolArg(mapOf("k" to 0), "k"))
    }

    @Test
    fun boolArg_junkStringOrMissing_isNull() {
        assertNull(ToolArgs.boolArg(mapOf("k" to "yes"), "k"))
        assertNull(ToolArgs.boolArg(mapOf("k" to null), "k"))
        assertNull(ToolArgs.boolArg(emptyMap(), "k"))
    }
}
