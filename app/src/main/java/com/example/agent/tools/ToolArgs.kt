package com.example.agent.tools

/**
 * Shared coercion for model-supplied tool args (`Map<String, Any?>`): Gemini may send
 * numbers as Double/Long/Int/String and booleans as Boolean/String, so every call site
 * that reads an arg needs the same defensive parsing rather than a raw cast.
 *
 * Reconciled from the five previously byte-identical `intArg` copies (TapTool,
 * InputTextTool, ScrollTool, SwipeTool, LongPressTool) and, for [boolArg], the UNION of
 * ScrollTool's old `boolArg` (case-insensitive "true"/"false" String) and
 * DefaultPhoneControlExecutor's old `readBooleanArg` (Number nonzero=true): every input
 * either legacy site accepted resolves identically here, so no call site regressed — in
 * particular task_complete's `success: 0` still coerces to false rather than falling
 * through to its `?: true` default. Semantics table: .superpowers/sdd/task-8-report.md.
 */
internal object ToolArgs {

    /** Coerce a loosely-typed function-call argument to [Int] (Gemini may send Long/Double/String). */
    fun intArg(args: Map<String, Any?>, key: String): Int? = when (val v = args[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /**
     * Coerce a loosely-typed function-call argument to [Boolean]: case-insensitive
     * "true"/"false" String, or Number (nonzero = true); null otherwise.
     */
    fun boolArg(args: Map<String, Any?>, key: String): Boolean? = when (val v = args[key]) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.trim().lowercase().takeIf { it == "true" || it == "false" }?.toBoolean()
        else -> null
    }
}
