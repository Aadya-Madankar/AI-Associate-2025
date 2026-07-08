package com.example.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolSchemaContractTest {

    private val forbiddenKeys = setOf("additionalProperties", "\$ref", "\$schema")

    @Test
    fun `no tool schema contains keys that reject the Gemini Live setup`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        val mapper = GeminiToolMapper()
        val violations = mutableListOf<String>()
        for (decl in registry.declarations()) {
            val live = mapper.toLiveFunctionDeclaration(decl)
            val schema = live.parameters
            if (schema == null) {
                // Parameter-less tool is fine — but a NON-blank schema string that failed to
                // parse silently degrades to null; that hides a broken schema. Catch it:
                if (decl.parametersJsonSchema.isNotBlank()) violations += "${decl.name}: schema failed to parse"
                continue
            }
            findForbidden(decl.name, schema, violations)
        }
        assertTrue("Schema violations (would close Live socket with 1007): $violations", violations.isEmpty())
    }

    private fun findForbidden(tool: String, node: Any?, out: MutableList<String>) {
        when (node) {
            is Map<*, *> -> node.forEach { (k, v) ->
                if (k in forbiddenKeys) out += "$tool: contains $k"
                findForbidden(tool, v, out)
            }
            is List<*> -> node.forEach { findForbidden(tool, it, out) }
        }
    }
}
