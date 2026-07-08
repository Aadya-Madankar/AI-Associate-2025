package com.example.agent

import com.example.live.LiveFunctionCall
import com.example.permission.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for [GeminiToolMapper] — the bridge between inbound Gemini Live
 * tool calls and the permission engine's [com.example.permission.AgentAction].
 *
 * These assert the SAFETY-critical mapping behavior (ARCHITECTURE.md §4.2), not
 * trivial getters:
 *  - an UNKNOWN tool name maps to a NON-reversible action (forced through Confirm),
 *  - known irreversible action types are marked reversible = false,
 *  - the targetApp scoping identity is read from args only for app-targeted types
 *    (e.g. open_app),
 *  - [GeminiToolMapper.toLiveTool] wraps the declarations into a single LiveTool.
 *
 * The target is pure logic (no android.* on these paths), so no Robolectric runner.
 */
class GeminiToolMapperTest {

    private val mapper = GeminiToolMapper()

    /** Minimal valid object schema for fixtures where the schema's content is irrelevant. */
    private val EMPTY_OBJECT_SCHEMA = """{"type":"object","properties":{}}"""

    // ------------------------------------------------------------------------
    // Unknown tool → UNKNOWN type, NON-reversible (fail-closed)
    // ------------------------------------------------------------------------

    @Test
    fun unknownToolName_mapsToUnknownType() {
        val action = mapper.toAgentAction(
            LiveFunctionCall(id = "1", name = "definitely_not_a_real_tool", args = emptyMap())
        )
        assertEquals(ActionType.UNKNOWN, action.type)
    }

    @Test
    fun unknownToolName_isNotReversible_soItCannotAutoRun() {
        // An unrecognized tool has unknown reversibility, so it MUST be treated as
        // irreversible — this is what forces it through the Confirm gate in every mode.
        val action = mapper.toAgentAction(
            LiveFunctionCall(id = "1", name = "send_money_somehow", args = mapOf("amount" to 9000.0))
        )
        assertEquals(ActionType.UNKNOWN, action.type)
        assertFalse(
            "An UNKNOWN tool must be non-reversible so it can never silently auto-run",
            action.reversible
        )
    }

    @Test
    fun actionTypeFor_unknownName_returnsUnknown() {
        assertEquals(ActionType.UNKNOWN, mapper.actionTypeFor("no_such_tool"))
    }

    // ------------------------------------------------------------------------
    // Known irreversible types are reversible = false
    // ------------------------------------------------------------------------

    @Test
    fun knownIrreversibleType_isNotReversible() {
        // The irreversibility decision lives in toAgentAction's `reversible` computation:
        //   reversible = type != UNKNOWN && type !in IRREVERSIBLE_TYPES   (GeminiToolMapper.kt).
        // We must drive THAT production line, not re-implement it in the test. Today no name in
        // NAME_TO_TYPE maps to an IRREVERSIBLE_TYPES value, so an irreversible type cannot be
        // reached by a plain mapper call. We therefore register a fixture name -> irreversible
        // type into the real NAME_TO_TYPE (reflectively, restored in a finally), then call the
        // actual mapper and assert the action it produces is reversible=false. If line 82's
        // `&& type !in IRREVERSIBLE_TYPES` were deleted, this would (correctly) start failing.
        val irreversibleTypes = readIrreversibleTypes()
        assertTrue(
            "Expected the IRREVERSIBLE_TYPES backstop to be non-empty",
            irreversibleTypes.isNotEmpty()
        )
        // Sanity: the canonical outbound/destructive types must be present.
        assertTrue(irreversibleTypes.contains(ActionType.SEND_MESSAGE))
        assertTrue(irreversibleTypes.contains(ActionType.SEND_EMAIL))
        assertTrue(irreversibleTypes.contains(ActionType.PLACE_CALL))
        assertTrue(irreversibleTypes.contains(ActionType.MAKE_PURCHASE))
        assertTrue(irreversibleTypes.contains(ActionType.DELETE_DATA))

        // Exercise the real predicate for EVERY irreversible type, end-to-end through the mapper.
        val fixtureName = "test_fixture_irreversible_tool"
        val original = readNameToType()
        try {
            for (type in irreversibleTypes) {
                // Point a known tool name at this irreversible type so actionTypeFor resolves it
                // and toAgentAction runs its genuine reversibility computation.
                writeNameToType(original + (fixtureName to type))
                val mapper = GeminiToolMapper()

                // The name is now registered, so this is the known-type path (NOT the UNKNOWN
                // fail-closed branch) — it proves `type !in IRREVERSIBLE_TYPES` actually fires.
                assertEquals(
                    "Fixture name must resolve to the irreversible type under test",
                    type,
                    mapper.actionTypeFor(fixtureName)
                )
                val action = mapper.toAgentAction(
                    LiveFunctionCall(id = "1", name = fixtureName, args = emptyMap())
                )
                assertEquals(type, action.type)
                assertFalse(
                    "Irreversible type $type must yield reversible=false from the mapper",
                    action.reversible
                )
            }
        } finally {
            writeNameToType(original)
        }
    }

    @Test
    fun knownSafeTool_isReversible() {
        // Contrast: a recognized SAFE tool (torch) is reversible and may auto-run.
        val action = mapper.toAgentAction(
            LiveFunctionCall(id = "1", name = AgentToolSchemas.TORCH, args = mapOf("on" to true))
        )
        assertEquals(ActionType.TORCH, action.type)
        assertTrue("A known SAFE, reversible tool must stay reversible", action.reversible)
    }

    // ------------------------------------------------------------------------
    // targetApp extraction
    // ------------------------------------------------------------------------

    @Test
    fun openApp_extractsTargetAppFromPackageArg() {
        val action = mapper.toAgentAction(
            LiveFunctionCall(
                id = "1",
                name = AgentToolSchemas.OPEN_APP,
                args = mapOf("package" to "com.spotify.music")
            )
        )
        assertEquals(ActionType.OPEN_APP, action.type)
        assertEquals("com.spotify.music", action.targetApp)
    }

    @Test
    fun openApp_fallsBackToAppNameWhenPackageMissing() {
        val action = mapper.toAgentAction(
            LiveFunctionCall(
                id = "1",
                name = AgentToolSchemas.OPEN_APP,
                args = mapOf("appName" to "Spotify")
            )
        )
        assertEquals("Spotify", action.targetApp)
    }

    @Test
    fun accessibilityPrimitive_neverReadsTargetAppFromArgs() {
        // SECURITY: a UI primitive (tap) must NOT inherit a model-claimed target app,
        // even if args smuggle one in — the foreground screen is the only authority.
        val action = mapper.toAgentAction(
            LiveFunctionCall(
                id = "1",
                name = AgentToolSchemas.TAP,
                args = mapOf("index" to 3.0, "package" to "com.bank.evil")
            )
        )
        assertEquals(ActionType.TAP, action.type)
        assertNull("UI primitives must not acquire a model-claimed targetApp", action.targetApp)
    }

    @Test
    fun toAgentAction_preservesLiteralArgsAsParams() {
        val args = mapOf("index" to 7.0, "text" to "hello")
        val action = mapper.toAgentAction(
            LiveFunctionCall(id = "1", name = AgentToolSchemas.INPUT_TEXT, args = args)
        )
        assertEquals(args, action.params)
    }

    @Test
    fun toAgentAction_nullArgs_yieldEmptyParams() {
        val action = mapper.toAgentAction(
            LiveFunctionCall(id = "1", name = AgentToolSchemas.GET_SCREEN, args = null)
        )
        assertEquals(emptyMap<String, Any?>(), action.params)
        assertEquals(ActionType.GET_SCREEN, action.type)
    }

    // ------------------------------------------------------------------------
    // toLiveTool wraps declarations
    // ------------------------------------------------------------------------

    @Test
    fun toLiveTool_wrapsAllDeclarationsIntoSingleTool() {
        val declarations = listOf(
            ToolDeclaration(
                name = AgentToolSchemas.GET_SCREEN,
                description = "Read the screen",
                parametersJsonSchema = EMPTY_OBJECT_SCHEMA
            ),
            ToolDeclaration(
                name = AgentToolSchemas.OPEN_APP,
                description = "Open an app",
                parametersJsonSchema = EMPTY_OBJECT_SCHEMA
            )
        )

        val liveTool = mapper.toLiveTool(declarations)

        val decls = liveTool.functionDeclarations
        assertNotNull("toLiveTool must populate functionDeclarations", decls)
        assertEquals(2, decls!!.size)
        assertEquals(listOf(AgentToolSchemas.GET_SCREEN, AgentToolSchemas.OPEN_APP), decls.map { it.name })
        // Built-in tool kinds must be left unset so the entry carries exactly one kind.
        assertNull(liveTool.googleSearch)
        assertNull(liveTool.codeExecution)
        assertNull(liveTool.urlContext)
    }

    @Test
    fun toLiveFunctionDeclaration_parsesSchemaIntoParametersMap() {
        val decl = ToolDeclaration(
            name = AgentToolSchemas.OPEN_URL,
            description = "Open a URL",
            parametersJsonSchema = EMPTY_OBJECT_SCHEMA
        )

        val live = mapper.toLiveFunctionDeclaration(decl)

        assertEquals(AgentToolSchemas.OPEN_URL, live.name)
        assertEquals("Open a URL", live.description)
        // A non-blank object schema parses into a non-null parameters map.
        assertNotNull(live.parameters)
        assertEquals("object", live.parameters!!["type"])
    }

    @Test
    fun toLiveFunctionDeclaration_blankSchemaBecomesNullParameters() {
        val decl = ToolDeclaration(
            name = AgentToolSchemas.PRESS_BACK,
            description = "Press back",
            parametersJsonSchema = ""
        )

        val live = mapper.toLiveFunctionDeclaration(decl)

        assertNull("A blank schema must map to a parameter-less (null) declaration", live.parameters)
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /** Reflectively read the private static IRREVERSIBLE_TYPES set from the companion. */
    @Suppress("UNCHECKED_CAST")
    private fun readIrreversibleTypes(): Set<ActionType> {
        val field = GeminiToolMapper::class.java.getDeclaredField("IRREVERSIBLE_TYPES")
        field.isAccessible = true
        // Static (companion-backed) field: read with a null receiver.
        return field.get(null) as Set<ActionType>
    }

    /** Reflectively read the private static NAME_TO_TYPE map from the companion. */
    @Suppress("UNCHECKED_CAST")
    private fun readNameToType(): Map<String, ActionType> {
        val field = GeminiToolMapper::class.java.getDeclaredField("NAME_TO_TYPE")
        field.isAccessible = true
        return field.get(null) as Map<String, ActionType>
    }

    /**
     * Replace the private static NAME_TO_TYPE map. Used only to register a test fixture name so a
     * real [GeminiToolMapper.toAgentAction] call can drive an irreversible type through the
     * production reversibility predicate; callers MUST restore the original in a finally.
     *
     * NAME_TO_TYPE is a Kotlin `private val` in the companion, so it compiles to a `static final`
     * field. On JDK 17 `Field.set` refuses to write a static-final (IllegalAccessException), and
     * the legacy "strip the final modifier" trick is filtered, so we write through `sun.misc.Unsafe`
     * (the JVM-supported way to mutate a static-final at runtime). If this mechanism is ever
     * unavailable the test fails loudly rather than silently degrading to a tautology.
     */
    private fun writeNameToType(value: Map<String, ActionType>) {
        val field = GeminiToolMapper::class.java.getDeclaredField("NAME_TO_TYPE")
        val unsafe = sunUnsafe()
        val base = unsafe.javaClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafe.javaClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafe.javaClass
            .getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
    }

    /** The singleton sun.misc.Unsafe, fetched reflectively (no compile-time dependency on it). */
    private fun sunUnsafe(): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafe.isAccessible = true
        return requireNotNull(theUnsafe.get(null)) { "sun.misc.Unsafe is unavailable on this JVM" }
    }
}
