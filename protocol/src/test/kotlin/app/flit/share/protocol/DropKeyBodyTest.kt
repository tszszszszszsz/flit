package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.circle.DropKeyCore.KeyedFile
import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure body-codec tests for the per-drop key announcement. No crypto, no
 * sockets, no state: every assertion here is on the exact key sets and bounds
 * of [CircleCore.validFileBody] and on [DropKeyCore.keyedFileBody] /
 * [DropKeyCore.parseFileKeyBody] agreeing with that gate.
 */
class DropKeyBodyTest {

    // Small hand-built values are enough: the gate checks Base64 shape and
    // length, not RSA content. 256 bytes encode to a realistic 344-char wrap.
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private val iv12: String = b64(ByteArray(12) { (it + 1).toByte() })
    private val wrap344: String = b64(ByteArray(256) { 0x5A })

    private fun sample(): KeyedFile = KeyedFile(
        name = "a.gflit",
        size = 12L,
        sha256 = "deadbeef",
        keyGen = 1,
        cipherSha = "cafebabe",
        iv = iv12,
        wraps = mapOf("A1B2C3D4" to wrap344),
    )

    private fun obj(body: String): JsonObject =
        WireJson.json.parseToJsonElement(body) as JsonObject

    private fun wraps(map: Map<String, String>): JsonObject =
        JsonObject(map.mapValues { JsonPrimitive(it.value) })

    private fun keyedBody(
        base: KeyedFile = sample(),
        edit: (MutableMap<String, JsonElement>) -> Unit = {},
    ): String {
        val fields = obj(DropKeyCore.keyedFileBody(base)).toMap().toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    private fun legacyBody(edit: (MutableMap<String, JsonElement>) -> Unit = {}): String {
        val fields = obj("""{"name":"a.gflit","size":12,"sha256":"deadbeef"}""")
            .toMap().toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    private fun refused(body: String, why: String) {
        assertFalse(CircleCore.validFileBody(body), "gate accepted $why: ${body.take(80)}")
        assertNull(DropKeyCore.parseFileKeyBody(body), "parse accepted $why: ${body.take(80)}")
    }

    // ---- codec round-trip and exact key sets -------------------------------

    @Test
    fun `keyedFileBody round-trips through parse and passes the file gate`() {
        val value = sample()
        val body = DropKeyCore.keyedFileBody(value)
        assertTrue(CircleCore.validFileBody(body))
        assertEquals(value, DropKeyCore.parseFileKeyBody(body))
    }

    @Test
    fun `keyedFileBody has exactly the seven keyed keys`() {
        val keys = obj(DropKeyCore.keyedFileBody(sample())).keys
        assertEquals(
            setOf("name", "size", "sha256", "keyGen", "cipherSha", "iv", "wraps"),
            keys,
        )
        assertEquals(keys, CircleCore.KEYED_FILE_BODY_KEYS)
    }

    @Test
    fun `legacy body has exactly the three legacy keys and still passes`() {
        assertEquals(setOf("name", "size", "sha256"), CircleCore.FILE_BODY_KEYS)
        assertEquals(setOf("name", "size", "sha256"), obj(legacyBody()).keys)
        assertTrue(CircleCore.validFileBody(legacyBody()))
        assertTrue(CircleCore.validFileBody("""{"name":"a","size":0,"sha256":"s"}"""))
    }

    @Test
    fun `validFileBody refuses every partial keyed and legacy key set`() {
        for (key in CircleCore.KEYED_FILE_BODY_KEYS) {
            refused(keyedBody { it.remove(key) }, "keyed body missing $key")
        }
        // A body missing two keys is no better than one missing a single key.
        refused(keyedBody { it.remove("cipherSha"); it.remove("wraps") }, "two missing keys")
        refused("""{"name":"a","size":1,"sha256":"s","keyGen":1}""", "five-key hybrid")

        for (key in CircleCore.FILE_BODY_KEYS) {
            refused(legacyBody { it.remove(key) }, "legacy body missing $key")
        }
    }

    @Test
    fun `validFileBody refuses any extra key on either body form`() {
        for (extra in listOf("path", "spoolPath", "token", "extra", "KEYGEN")) {
            refused(legacyBody { it[extra] = JsonPrimitive("x") }, "legacy body with $extra")
            refused(keyedBody { it[extra] = JsonPrimitive("x") }, "keyed body with $extra")
        }
    }

    // ---- value types and numeric bounds ------------------------------------

    @Test
    fun `validFileBody requires a non-negative integer size in both forms`() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive("12"), JsonPrimitive(-1L), JsonPrimitive(1.5), JsonNull,
        )
        for (value in bad) {
            refused(keyedBody { it["size"] = value }, "keyed non-integer/negative size")
            refused(legacyBody { it["size"] = value }, "legacy non-integer/negative size")
        }
        assertTrue(CircleCore.validFileBody(keyedBody { it["size"] = JsonPrimitive(0) }))
        assertTrue(CircleCore.validFileBody(legacyBody { it["size"] = JsonPrimitive(0) }))
        assertTrue(CircleCore.validFileBody(keyedBody { it["size"] = JsonPrimitive(Long.MAX_VALUE) }))
    }

    @Test
    fun `validFileBody requires a positive integer keyGen`() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("1"),
            JsonPrimitive(1.5), JsonNull,
        )
        for (value in bad) {
            refused(keyedBody { it["keyGen"] = value }, "non-positive/non-integer keyGen")
        }
        assertTrue(CircleCore.validFileBody(keyedBody { it["keyGen"] = JsonPrimitive(1) }))
        assertTrue(CircleCore.validFileBody(keyedBody { it["keyGen"] = JsonPrimitive(Int.MAX_VALUE) }))
    }

    @Test
    fun `validFileBody requires an iv that decodes to exactly twelve bytes`() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive(b64(ByteArray(11))),
            JsonPrimitive(b64(ByteArray(13))),
            JsonPrimitive(""),
            JsonPrimitive("!!!"),
            JsonPrimitive(12345),
            JsonNull,
        )
        for (value in bad) {
            refused(keyedBody { it["iv"] = value }, "bad iv")
        }
        assertTrue(CircleCore.validFileBody(keyedBody { it["iv"] = JsonPrimitive(b64(ByteArray(12))) }))
    }

    @Test
    fun `validFileBody refuses blank and oversized metadata strings`() {
        val atCap = "x".repeat(CircleCore.MAX_FIELD_CHARS)
        val overCap = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)
        for (key in listOf("name", "sha256", "cipherSha")) {
            refused(keyedBody { it[key] = JsonPrimitive("") }, "blank $key")
            refused(keyedBody { it[key] = JsonPrimitive("   ") }, "blank $key")
            refused(keyedBody { it[key] = JsonPrimitive(overCap) }, "over-cap $key")
        }
        for (key in listOf("name", "sha256")) {
            refused(legacyBody { it[key] = JsonPrimitive("") }, "blank legacy $key")
            refused(legacyBody { it[key] = JsonPrimitive(overCap) }, "over-cap legacy $key")
        }
        assertTrue(CircleCore.validFileBody(keyedBody {
            it["name"] = JsonPrimitive(atCap)
            it["sha256"] = JsonPrimitive(atCap)
            it["cipherSha"] = JsonPrimitive(atCap)
        }))
    }

    // ---- wraps object -------------------------------------------------------

    @Test
    fun `validFileBody refuses malformed wraps entries`() {
        refused(keyedBody { it["wraps"] = JsonObject(emptyMap()) }, "empty wraps")
        refused(keyedBody { it["wraps"] = JsonPrimitive("not an object") }, "non-object wraps")
        refused("""{"name":"a","size":1,"sha256":"s","keyGen":1,"cipherSha":"c","iv":"$iv12","wraps":[]}""",
            "array wraps")
        refused(keyedBody { it["wraps"] = wraps(mapOf("A1B2C3D4" to "")) }, "blank wrap value")
        refused(keyedBody { it["wraps"] = wraps(mapOf("A1B2C3D4" to "!!!")) }, "junk wrap value")
        refused(keyedBody {
            it["wraps"] = JsonObject(mapOf("A1B2C3D4" to JsonPrimitive(7)))
        }, "non-string wrap value")
        for (fp in listOf("", " ", " A1B2C3D4", "a1b2c3d4", "A1b2C3D4", "x".repeat(257))) {
            refused(keyedBody { it["wraps"] = wraps(mapOf(fp to wrap344)) }, "bad fingerprint")
        }
    }

    @Test
    fun `validFileBody caps wrap values at five hundred twelve chars`() {
        val atCap = b64(ByteArray(384))  // 512 Base64 chars
        val overCap = b64(ByteArray(385)) // 516 Base64 chars
        assertEquals(512, atCap.length)
        assertEquals(344, wrap344.length)
        assertTrue(CircleCore.validFileBody(keyedBody {
            it["wraps"] = wraps(mapOf("A1B2C3D4" to atCap))
        }))
        assertTrue(CircleCore.validFileBody(keyedBody {
            it["wraps"] = wraps(mapOf("A1B2C3D4" to wrap344))
        }))
        refused(keyedBody { it["wraps"] = wraps(mapOf("A1B2C3D4" to overCap)) },
            "wrap value over MAX_WRAPPED_CHARS")
    }

    @Test
    fun `validFileBody caps wrap count at thirty two`() {
        fun roster(n: Int): Map<String, String> = (0 until n).associate { i ->
            ("FP" + i.toString(16).uppercase().padStart(6, '0')) to wrap344
        }
        assertTrue(CircleCore.validFileBody(keyedBody { it["wraps"] = wraps(roster(1)) }))
        assertTrue(CircleCore.validFileBody(keyedBody { it["wraps"] = wraps(roster(32)) }))
        refused(keyedBody { it["wraps"] = wraps(roster(33)) }, "33 wraps")
        refused(keyedBody { it["wraps"] = wraps(roster(40)) }, "40 wraps")
    }

    @Test
    fun `validFileBody refuses a body over sixteen kib even with valid fields`() {
        // 32 individually valid 512-char wrap values push the whole body past
        // MAX_BODY_CHARS; the body cap is checked before any field is read.
        val big = keyedBody {
            it["wraps"] = wraps((0 until 32).associate { i ->
                ("FP" + i.toString(16).uppercase().padStart(6, '0')) to b64(ByteArray(384))
            })
        }
        assertTrue(big.length > CircleCore.MAX_BODY_CHARS)
        refused(big, "over-cap body")
    }

    // ---- parse mirrors the gate --------------------------------------------

    @Test
    fun `parseFileKeyBody returns null for every structurally refused body`() {
        val good = DropKeyCore.keyedFileBody(sample())
        val refusedBodies = listOf(
            "", " ", "{", "[]", "null", "x".repeat(CircleCore.MAX_BODY_CHARS + 1),
            """{"name":"a","size":1}""",
            """{"name":"a","size":1,"sha256":"s","extra":1}""",
            keyedBody { it.remove("keyGen") },
            keyedBody { it["token"] = JsonPrimitive("t") },
            keyedBody { it["size"] = JsonPrimitive("12") },
            keyedBody { it["keyGen"] = JsonPrimitive(0) },
            keyedBody { it["cipherSha"] = JsonPrimitive("") },
            keyedBody { it["iv"] = JsonPrimitive(b64(ByteArray(8))) },
            keyedBody { it["wraps"] = JsonObject(emptyMap()) },
            keyedBody { it["wraps"] = wraps(mapOf("lower" to wrap344)) },
            keyedBody { it["wraps"] = wraps(mapOf("A1B2C3D4" to "junk!")) },
            keyedBody { it["wraps"] = wraps((0 until 33).associate { i ->
                "FP" + i.toString(16).uppercase().padStart(6, '0') to wrap344
            }) },
        )
        assertTrue(CircleCore.validFileBody(good))
        for (body in refusedBodies) {
            refused(body, "structurally refused body")
        }
    }
}
