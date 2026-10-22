package app.flit.share.protocol.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Canonical wire JSON config shared by client, server and discovery. */
@OptIn(ExperimentalSerializationApi::class)
object WireJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // v2 RegisterDtoV2 (rust) requires `port` and `protocol` with no serde
        // defaults; keeping default-valued fields on the wire is the safe side.
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)
}
