package iondrive.nop.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Total accessors over untrusted JSON.
 *
 * kotlinx's own `jsonPrimitive` and `jsonObject` *throw* when the element is the wrong shape, which
 * is the wrong default here: every document these read is written by somebody else's program —
 * transcripts whose format changes between CLI releases, credential files, API responses. A field
 * that turns out to be a list where a string was expected should cost that one field, not the
 * record, and certainly not the thread reading it.
 */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.let {
    // JsonNull is a JsonPrimitive, and its content is the literal "null".
    if (it is kotlinx.serialization.json.JsonNull) null else it.content
}

internal fun JsonElement?.long(): Long? = str()?.toLongOrNull()

internal fun JsonElement?.int(): Int? = str()?.toIntOrNull()

internal fun JsonElement?.double(): Double? = str()?.toDoubleOrNull()

internal fun JsonElement?.bool(): Boolean = str() == "true"

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
