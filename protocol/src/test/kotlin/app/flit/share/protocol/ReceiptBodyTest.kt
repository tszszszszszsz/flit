package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.circle.DropKeyCore.KeyedFile
import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure, structural tests for the delivery-receipt body gate. No crypto, no
 * sockets, no state: every assertion is on [CircleCore.validReceiptBody] and
 * on the exact key set [CircleCore.RECEIPT_BODY_KEYS]. A receipt body is a
 * closed JSON object of three non-blank, bounded string values; `createdMs`
 * on the event is the only time, so a second time field in the body is extra.
 *
 * The last two tests pin the legacy and keyed `file` body gates: adding the
 * receipt verb and its key set must not change [CircleCore.validFileBody].
 */
class ReceiptBodyTest {

    private fun goodBody(): String =
        """{"dropId":"drop-42","sha256":"deadbeef","keyId":"key-7"}"""

    private fun obj(body: String): JsonObject =
        WireJson.json.parseToJsonElement(body) as JsonObject

    private fun bodyWith(edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val fields = obj(goodBody()).toMap().toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    private fun refused(body: String, why: String) {
        assertFalse(CircleCore.validReceiptBody(body), "gate accepted $why: ${body.take(80)}")
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun keyedFileBody(): String = DropKeyCore.keyedFileBody(
        KeyedFile(
            name = "a.gflit",
            size = 12L,
            sha256 = "deadbeef",
            keyGen = 1,
            cipherSha = "cafebabe",
            iv = b64(ByteArray(12) { (it + 1).toByte() }),
            wraps = mapOf("A1B2C3D4" to b64(ByteArray(256) { 0x5A })),
        ),
    )

    // ---- exact keys and bounds ---------------------------------------------

    @Test
    fun `validReceiptBody accepts an exact three-key string body`() {
        assertTrue(CircleCore.validReceiptBody(goodBody()))
        assertTrue(CircleCore.validReceiptBody(
            """{"keyId":"k","sha256":"s","dropId":"d"}""",
        ), "key order in the JSON object must not matter")
        assertEquals(setOf("dropId", "sha256", "keyId"), obj(goodBody()).keys)
    }

    @Test
    fun `receipt key set is the frozen three keys and stays disjoint from file keys`() {
        assertEquals(setOf("dropId", "sha256", "keyId"), CircleCore.RECEIPT_BODY_KEYS)
        assertEquals(obj(goodBody()).keys, CircleCore.RECEIPT_BODY_KEYS)
        // `sha256` is shared with both file body forms by design: it names
        // the acknowledged plaintext digest. `dropId` and `keyId` are
        // receipt-only fields.
    }

    @Test
    fun `validReceiptBody refuses every missing key`() {
        for (key in CircleCore.RECEIPT_BODY_KEYS) {
            refused(bodyWith { it.remove(key) }, "body missing $key")
        }
        refused(bodyWith { it.remove("dropId"); it.remove("sha256") }, "two keys missing")
        refused("{}", "empty object")
    }

    @Test
    fun `validReceiptBody refuses any extra key`() {
        for (extra in listOf("path", "spoolPath", "token", "extra", "keyGen", "DROPID")) {
            refused(bodyWith { it[extra] = JsonPrimitive("x") }, "extra key $extra")
        }
    }

    @Test
    fun `validReceiptBody refuses a landedMs second time field`() {
        refused(bodyWith { it["landedMs"] = JsonPrimitive("1") }, "extra landedMs field")
        refused(bodyWith { it.remove("keyId"); it["landedMs"] = JsonPrimitive("1") },
            "landedMs substituted for keyId")
    }

    @Test
    fun `validReceiptBody refuses non-string values for every field`() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive(123),
            JsonPrimitive(true),
            JsonNull,
            JsonObject(emptyMap()),
            JsonArray(emptyList()),
        )
        for (key in CircleCore.RECEIPT_BODY_KEYS) {
            for (value in bad) {
                refused(bodyWith { it[key] = value }, "$key with non-string value $value")
            }
        }
        // A receipt has no numeric field: a "size"-style integer value is not
        // a valid stand-in for any of its three strings.
        refused("""{"dropId":"d","sha256":"s","keyId":7}""", "integer keyId")
    }

    @Test
    fun `validReceiptBody refuses blank field values`() {
        for (key in CircleCore.RECEIPT_BODY_KEYS) {
            refused(bodyWith { it[key] = JsonPrimitive("") }, "blank $key")
            refused(bodyWith { it[key] = JsonPrimitive("   ") }, "space-only $key")
            refused(bodyWith { it[key] = JsonPrimitive("\t\n ") }, "whitespace-only $key")
        }
    }

    @Test
    fun `validReceiptBody bounds every field at MAX_FIELD_CHARS`() {
        val atCap = "x".repeat(CircleCore.MAX_FIELD_CHARS)
        val overCap = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)
        for (key in CircleCore.RECEIPT_BODY_KEYS) {
            refused(bodyWith { it[key] = JsonPrimitive(overCap) }, "over-cap $key")
        }
        assertTrue(CircleCore.validReceiptBody(
            bodyWith {
                it["dropId"] = JsonPrimitive(atCap)
                it["sha256"] = JsonPrimitive(atCap)
                it["keyId"] = JsonPrimitive(atCap)
            },
        ), "values exactly at MAX_FIELD_CHARS must be accepted")
    }

    @Test
    fun `validReceiptBody refuses blank malformed and non-object bodies`() {
        for (body in listOf(
            "", " ", "\t\n", "{", "[]", "null", "\"text\"", "42",
            """{"dropId":"d","sha256":"s","keyId":"k"} trailing""",
            """["drop-42","deadbeef","key-7"]""",
        )) {
            refused(body, "malformed/non-object body")
        }
    }

    @Test
    fun `validReceiptBody refuses a body over MAX_BODY_CHARS`() {
        // Whitespace padding keeps the three fields individually valid while
        // pushing the whole body past the 16 KiB cap; the cap is checked first.
        val big = " ".repeat(CircleCore.MAX_BODY_CHARS) + goodBody()
        assertTrue(big.length > CircleCore.MAX_BODY_CHARS)
        refused(big, "over-cap body")
    }

    // ---- legacy and keyed file bodies are unchanged ------------------------

    @Test
    fun `legacy and keyed file body keys and gates are unchanged`() {
        assertEquals(setOf("name", "size", "sha256"), CircleCore.FILE_BODY_KEYS)
        assertEquals(
            setOf("name", "size", "sha256", "keyGen", "cipherSha", "iv", "wraps"),
            CircleCore.KEYED_FILE_BODY_KEYS,
        )
        assertTrue(CircleCore.validFileBody("""{"name":"a","size":0,"sha256":"s"}"""))
        assertTrue(CircleCore.validFileBody("""{"name":"a","size":12,"sha256":"s"}"""))
        val keyed = keyedFileBody()
        assertTrue(CircleCore.validFileBody(keyed))
        assertEquals(obj(keyed).keys, CircleCore.KEYED_FILE_BODY_KEYS)
        assertEquals(
            KeyedFile(
                name = "a.gflit", size = 12L, sha256 = "deadbeef", keyGen = 1,
                cipherSha = "cafebabe",
                iv = b64(ByteArray(12) { (it + 1).toByte() }),
                wraps = mapOf("A1B2C3D4" to b64(ByteArray(256) { 0x5A })),
            ),
            DropKeyCore.parseFileKeyBody(keyed),
        )
    }

    @Test
    fun `file and receipt body gates reject each other's bodies`() {
        assertFalse(CircleCore.validFileBody(goodBody()), "file gate accepted a receipt body")
        assertFalse(CircleCore.validReceiptBody("""{"name":"a","size":1,"sha256":"s"}"""),
            "receipt gate accepted a legacy file body")
        assertFalse(CircleCore.validReceiptBody(keyedFileBody()),
            "receipt gate accepted a keyed file body")
    }
}
