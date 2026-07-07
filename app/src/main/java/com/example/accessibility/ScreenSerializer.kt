package com.example.accessibility

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a [ScreenState] into a compact JSON string for the model's tool result.
 *
 * The model grounds on element [UiElement.index] (the "set-of-marks" pattern,
 * ARCHITECTURE.md §3.3), so every element emits a small `i` plus only the fields that
 * are present — null/blank fields and false flags are omitted to keep the payload
 * inside the model's tight context budget. Bounds are emitted as `[left, top, right,
 * bottom]` absolute screen pixels.
 *
 * Output shape (top-level object):
 * ```json
 * {"pkg":"com.x","secure":false,"elements":[
 *   {"i":0,"role":"Button","text":"Send","bounds":[40,400,1180,520],"clickable":true}
 * ]}
 * ```
 */
object ScreenSerializer {

    /** Placeholder emitted in place of a redacted value so the model knows one exists. */
    private const val REDACTED = "█REDACTED█"

    /** Serialize [state] to a compact JSON string (no pretty-printing). */
    fun toJson(state: ScreenState): String = toJsonObject(state).toString()

    /** Serialize [state] to a [JSONObject] for callers that compose larger payloads. */
    fun toJsonObject(state: ScreenState): JSONObject {
        val root = JSONObject()
        state.packageName?.let { root.put("pkg", it) }
        state.activity?.let { root.put("activity", it) }
        if (state.secure) root.put("secure", true)
        root.put("elements", elementsArray(state.elements, state.secure))
        return root
    }

    private fun elementsArray(elements: List<UiElement>, secure: Boolean): JSONArray {
        val arr = JSONArray()
        for (el in elements) arr.put(elementObject(el, secure))
        return arr
    }

    private fun elementObject(el: UiElement, secure: Boolean): JSONObject {
        val o = JSONObject()
        o.put("i", el.index)
        o.put("role", el.role)
        // Defense-in-depth redaction gate at the final serialization boundary: never
        // let a password field's value, or any text on a secure screen, leave the
        // device verbatim even if the upstream ScreenRedactor was bypassed or ran
        // under a weaker policy. We still emit bounds + capability flags below so the
        // model can ground and act, and a fixed marker so it knows a value exists.
        if (el.password || secure) {
            o.put("text", REDACTED)
        } else {
            el.text?.takeIf { it.isNotBlank() }?.let { o.put("text", it) }
            el.contentDescription?.takeIf { it.isNotBlank() }?.let { o.put("desc", it) }
            el.hint?.takeIf { it.isNotBlank() }?.let { o.put("hint", it) }
            el.resourceId?.takeIf { it.isNotBlank() }?.let { o.put("id", it) }
        }

        // Bounds as [l, t, r, b].
        val b = JSONArray().apply {
            put(el.bounds.left)
            put(el.bounds.top)
            put(el.bounds.right)
            put(el.bounds.bottom)
        }
        o.put("bounds", b)

        // Only emit flags that are true to minimize tokens.
        if (el.clickable) o.put("clickable", true)
        if (el.editable) o.put("editable", true)
        if (el.scrollable) o.put("scrollable", true)
        if (el.longClickable) o.put("longClickable", true)
        if (el.checkable) o.put("checkable", true)
        if (el.checked) o.put("checked", true)
        if (el.password) o.put("password", true)
        return o
    }
}
