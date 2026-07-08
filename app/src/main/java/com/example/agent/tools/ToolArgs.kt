package com.example.agent.tools

/**
 * Shared coercion for model-supplied tool args (`Map<String, Any?>`): Gemini may send
 * numbers as Double/Long/Int/String and booleans as Boolean/String, so every call site
 * that reads an arg needs the same defensive parsing rather than a raw cast.
 *
 * Reconciled from the five previously byte-identical `intArg` copies (TapTool,
 * InputTextTool, ScrollTool, SwipeTool, LongPressTool) and, for [boolArg], the strictest
 * common ground between ScrollTool's old `boolArg` (case-insensitive String, no Number)
 * and DefaultPhoneControlExecutor's old `readBooleanArg` (case-sensitive String via
 * `toBooleanStrictOrNull`, Number nonzero=true) — see the semantics table in
 * .superpowers/sdd/task-8-report.md for the two narrow behavior deltas this introduces.
 */
internal object ToolArgs {

    /** Coerce a loosely-typed function-call argument to [Int] (Gemini may send Long/Double/String). */
    fun intArg(args: Map<String, Any?>, key: String): Int? = when (val v = args[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /** Coerce a loosely-typed function-call argument to [Boolean] (exact "true"/"false" String only). */
    fun boolArg(args: Map<String, Any?>, key: String): Boolean? = when (val v = args[key]) {
        is Boolean -> v
        is String -> v.trim().toBooleanStrictOrNull()
        else -> null
    }
}
