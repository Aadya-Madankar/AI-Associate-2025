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
    // boolArg — the UNION of the two legacy coercions: ScrollTool.boolArg's
    // case-insensitive "true"/"false" String, plus DefaultPhoneControlExecutor
    // .readBooleanArg's Number (nonzero=true, zero=false). Every input either old site
    // accepted resolves identically under the union; it only widens on inputs neither
    // handled. Critical live path: task_complete `success: 0` must coerce to false,
    // never fall through to the `?: true` default (failed task reported as success).
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
    fun boolArg_mixedCaseString_parses_legacyScrollToolSemantics() {
        // ScrollTool's old copy lowercased before matching; the union keeps that tolerance.
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to "True"), "k"))
        assertEquals(false, ToolArgs.boolArg(mapOf("k" to "FALSE"), "k"))
    }

    @Test
    fun boolArg_number_nonzeroTrue_zeroFalse_legacyExecutorSemantics() {
        // readBooleanArg's old copy accepted Number (nonzero -> true, zero -> false).
        // Live path: task_complete `success: 0` must be false, never the `?: true` default.
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to 1), "k"))
        assertEquals(false, ToolArgs.boolArg(mapOf("k" to 0), "k"))
        assertEquals(false, ToolArgs.boolArg(mapOf("k" to 0.0), "k"))
        assertEquals(true, ToolArgs.boolArg(mapOf("k" to 1.0), "k"))
    }

    @Test
    fun boolArg_junkStringOrMissing_isNull() {
        assertNull(ToolArgs.boolArg(mapOf("k" to "yes"), "k"))
        assertNull(ToolArgs.boolArg(mapOf("k" to null), "k"))
        assertNull(ToolArgs.boolArg(emptyMap(), "k"))
    }
}
