package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.circle.DropKeyCore.KeyedFile
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.LinkedHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parity seams of the per-drop keyed `file` announcement: the full-cap
 * body stays under the wire bounds, the signed canonical bytes bind the key
 * material (wraps, iv, cipherSha), and the legacy and keyed body gates accept
 * exactly their own key sets. No socket, carrier, file, Android or app state —
 * only `CircleCore`, `DropKeyCore`, in-memory identities and byte arrays.
 */
class DropParityTest {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun bodyObject(body: String): JsonObject =
        WireJson.json.parseToJsonElement(body) as JsonObject

    /** Edits one JSON body without touching the signature or any other field. */
    private fun JsonObject.edited(edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val fields = toMap().toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    private fun wrapsObject(map: Map<String, String>): JsonObject =
        JsonObject(map.mapValues { JsonPrimitive(it.value) })

    /** A deterministic gate-valid sample; wrap values need only Base64 shape. */
    private fun sample(): KeyedFile = KeyedFile(
        name = "report.gflit",
        size = 512L,
        sha256 = hex(sha256(ByteArray(512) { (it * 17).toByte() })),
        keyGen = 1,
        cipherSha = hex(sha256(ByteArray(528))),
        iv = b64(ByteArray(DropKeyCore.IV_BYTES) { (it + 3).toByte() }),
        wraps = mapOf("A1B2C3D4" to b64(ByteArray(256) { 0x5A })),
    )

    private fun <T> subsetsOfSize(items: List<T>, size: Int): List<List<T>> =
        (0 until (1 shl items.size)).mapNotNull { mask ->
            val picked = items.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            picked.takeIf { it.size == size }
        }

    // ---- caps --------------------------------------------------------------

    @Test
    fun `a full thirty-two-wrap keyed body stays under every cap and round-trips`() {
        val identity = TlsIdentity.generate()
        val key = DropKeyCore.mint()
        val plain = ByteArray(512) { (it * 17).toByte() }
        val sealed = DropKeyCore.seal(key, plain)

        // One real certificate wrapped under 32 distinct uppercase fingerprints
        // gives genuine ~344-char wrap values at the declared roster cap.
        val roster = LinkedHashMap<String, X509Certificate>()
        repeat(DropKeyCore.MAX_WRAPS) {
            roster["FP" + it.toString(16).uppercase().padStart(4, '0')] = identity.certificate
        }
        val wraps = DropKeyCore.wrap(key.bytes, roster)
        assertEquals(DropKeyCore.MAX_WRAPS, wraps.size)

        val value = KeyedFile(
            name = "report-2026.gflit",
            size = plain.size.toLong(),
            sha256 = hex(sha256(plain)),
            keyGen = 1,
            cipherSha = hex(sha256(sealed)),
            iv = b64(key.iv),
            wraps = wraps,
        )
        val body = DropKeyCore.keyedFileBody(value)
        assertTrue(body.length <= CircleCore.MAX_BODY_CHARS, "full-cap body exceeded MAX_BODY_CHARS")

        val obj = bodyObject(body)
        for (field in listOf("name", "sha256", "cipherSha", "iv")) {
            val text = (obj[field] as JsonPrimitive).content
            assertTrue(text.isNotBlank())
            assertTrue(text.length <= CircleCore.MAX_FIELD_CHARS, "$field exceeded MAX_FIELD_CHARS")
        }
        assertEquals(DropKeyCore.IV_BYTES, Base64.getDecoder().decode((obj["iv"] as JsonPrimitive).content).size)
        assertEquals(CircleCore.KEYED_FILE_BODY_KEYS, obj.keys)
        val wrapsJson = obj["wraps"] as JsonObject
        assertEquals(DropKeyCore.MAX_WRAPS, wrapsJson.size)
        for ((fp, wrapped) in wrapsJson) {
            val b64 = (wrapped as JsonPrimitive).content
            assertEquals(fp.uppercase(), fp, "wrap key must be an uppercase fingerprint")
            assertTrue(b64.length <= DropKeyCore.MAX_WRAPPED_CHARS)
            assertTrue(b64.length > CircleCore.MAX_FIELD_CHARS, "a real wrap needs its own, larger cap")
        }

        assertTrue(CircleCore.validFileBody(body))
        assertEquals(value, DropKeyCore.parseFileKeyBody(body))
        assertEquals(hex(sha256(plain)), DropKeyCore.parseFileKeyBody(body)!!.sha256)
        assertEquals(hex(sha256(sealed)), DropKeyCore.parseFileKeyBody(body)!!.cipherSha)
    }

    @Test
    fun `the gate wrap cap matches the wrap cap so a thirty-third wrap is refused`() {
        val identity = TlsIdentity.generate()
        val key = DropKeyCore.mint().bytes
        val roster = LinkedHashMap<String, X509Certificate>()
        repeat(DropKeyCore.MAX_WRAPS) {
            roster["FP" + it.toString(16).uppercase().padStart(4, '0')] = identity.certificate
        }
        val atCap = DropKeyCore.wrap(key, roster)
        val base = sample().copy(wraps = atCap)
        val atCapBody = DropKeyCore.keyedFileBody(base)
        assertTrue(CircleCore.validFileBody(atCapBody))

        // The extra entry is still a well-formed uppercase/Base64 wrap, so the
        // only reason for refusal is the count, not a body-size or field flaw.
        val overCap = LinkedHashMap(atCap)
        overCap["FP" + DropKeyCore.MAX_WRAPS.toString(16).uppercase().padStart(4, '0')] =
            atCap.values.first()
        assertEquals(DropKeyCore.MAX_WRAPS + 1, overCap.size)
        val overCapBody = DropKeyCore.keyedFileBody(base.copy(wraps = overCap))
        assertTrue(overCapBody.length <= CircleCore.MAX_BODY_CHARS)
        assertFalse(CircleCore.validFileBody(overCapBody), "gate accepted ${overCap.size} wraps")
        assertNull(DropKeyCore.parseFileKeyBody(overCapBody))

        val tooManyCerts = LinkedHashMap(roster)
        tooManyCerts["OVERCAP"] = identity.certificate
        assertTrue(runCatching { DropKeyCore.wrap(key, tooManyCerts) }.isFailure)
    }

    // ---- canonical signature binding ---------------------------------------

    @Test
    fun `a signed keyed event verifies until wraps cipherSha or iv is edited`() {
        val author = TlsIdentity.generate()
        val body = DropKeyCore.keyedFileBody(sample())
        val event = CircleCore.create(
            circleId = "circle-1",
            authorFp = author.fingerprint,
            identity = author,
            type = CircleEvent.FILE,
            body = body,
            lamport = 7L,
            createdMs = 1_800_000_000_000L,
        )
        assertTrue(CircleCore.verify(event))
        assertTrue(CircleCore.validFileBody(event.body))

        val sampleFp = sample().wraps.keys.first()
        val edited = mapOf(
            "wraps" to bodyObject(body).edited { fields ->
                fields["wraps"] = wrapsObject(mapOf(sampleFp to b64(ByteArray(256) { 0x11 })))
            },
            "cipherSha" to bodyObject(body).edited { it["cipherSha"] = JsonPrimitive("f".repeat(64)) },
            "iv" to bodyObject(body).edited {
                it["iv"] = JsonPrimitive(b64(ByteArray(DropKeyCore.IV_BYTES) { 0x33 }))
            },
        )
        val originalCanonical = CircleCore.canonical(event)
        for ((field, changedBody) in edited) {
            assertTrue(
                CircleCore.validFileBody(changedBody),
                "$field edit must stay gate-valid so the signature is the only refusal",
            )
            assertFalse(CircleCore.verify(event.copy(body = changedBody)), "$field edit slipped past verify")
            assertFalse(
                CircleCore.canonical(event.copy(body = changedBody)).contentEquals(originalCanonical),
                "$field is not in the signed canonical bytes",
            )
            assertNotNull(DropKeyCore.parseFileKeyBody(changedBody))
        }
        assertTrue(CircleCore.verify(event), "the untouched event must still verify")
    }

    @Test
    fun `canonical covers all seven keyed fields and an edit verifies only after re-signing`() {
        val author = TlsIdentity.generate()
        val value = sample()
        val body = DropKeyCore.keyedFileBody(value)
        val event = CircleCore.create(
            circleId = "circle-1",
            authorFp = author.fingerprint,
            identity = author,
            type = CircleEvent.FILE,
            body = body,
            lamport = 1L,
            createdMs = 1_800_000_000_000L,
        )
        val originalCanonical = CircleCore.canonical(event)
        val wrapFp = value.wraps.keys.first()

        val edits = listOf(
            "name" to bodyObject(body).edited { it["name"] = JsonPrimitive("renamed.gflit") },
            "size" to bodyObject(body).edited { it["size"] = JsonPrimitive(value.size + 1) },
            "sha256" to bodyObject(body).edited { it["sha256"] = JsonPrimitive("a".repeat(64)) },
            "keyGen" to bodyObject(body).edited { it["keyGen"] = JsonPrimitive(value.keyGen + 1) },
            "cipherSha" to bodyObject(body).edited { it["cipherSha"] = JsonPrimitive("b".repeat(64)) },
            "iv" to bodyObject(body).edited {
                it["iv"] = JsonPrimitive(b64(ByteArray(DropKeyCore.IV_BYTES) { 0x44 }))
            },
            "wraps" to bodyObject(body).edited {
                it["wraps"] = wrapsObject(mapOf(wrapFp to b64(ByteArray(256) { 0x22 })))
            },
        )
        for ((field, changedBody) in edits) {
            assertTrue(CircleCore.validFileBody(changedBody), "$field edit must be signable")
            assertFalse(
                CircleCore.canonical(event.copy(body = changedBody)).contentEquals(originalCanonical),
                "$field is missing from the canonical bytes",
            )
            assertFalse(CircleCore.verify(event.copy(body = changedBody)))
            val reSigned = CircleCore.sign(event.copy(body = changedBody, sig = ""), author)
            assertTrue(CircleCore.verify(reSigned), "$field edit should verify once re-signed")
        }
    }

    // ---- legacy / keyed gate exactness -------------------------------------

    @Test
    fun `legacy and keyed key sets are exact nested sets with four keyed-only fields`() {
        assertEquals(setOf("name", "size", "sha256"), CircleCore.FILE_BODY_KEYS)
        assertEquals(
            setOf("name", "size", "sha256", "keyGen", "cipherSha", "iv", "wraps"),
            CircleCore.KEYED_FILE_BODY_KEYS,
        )
        assertTrue(CircleCore.FILE_BODY_KEYS.all { it in CircleCore.KEYED_FILE_BODY_KEYS })
        assertEquals(
            setOf("keyGen", "cipherSha", "iv", "wraps"),
            CircleCore.KEYED_FILE_BODY_KEYS - CircleCore.FILE_BODY_KEYS,
        )

        val legacyBody = """{"name":"a.gflit","size":12,"sha256":"deadbeef"}"""
        val keyedBody = DropKeyCore.keyedFileBody(sample())
        assertTrue(CircleCore.validFileBody(legacyBody))
        assertTrue(CircleCore.validFileBody(keyedBody))
        assertNull(DropKeyCore.parseFileKeyBody(legacyBody), "legacy body is not a keyed body")
        assertNotNull(DropKeyCore.parseFileKeyBody(keyedBody))

        for (field in CircleCore.KEYED_FILE_BODY_KEYS - CircleCore.FILE_BODY_KEYS) {
            val partial = bodyObject(keyedBody).edited { it.remove(field) }
            assertFalse(CircleCore.validFileBody(partial), "keyed body missing $field was accepted")
            assertNull(DropKeyCore.parseFileKeyBody(partial))
        }
        for (field in CircleCore.FILE_BODY_KEYS) {
            val partial = bodyObject(legacyBody).edited { it.remove(field) }
            assertFalse(CircleCore.validFileBody(partial), "legacy body missing $field was accepted")
        }
    }

    @Test
    fun `gate and parser accept exactly the legacy or keyed key set and refuse every other subset`() {
        val keyedJson = bodyObject(DropKeyCore.keyedFileBody(sample()))
        val legacyJson = bodyObject("""{"name":"a.gflit","size":12,"sha256":"deadbeef"}""")

        val candidates = LinkedHashMap<String, JsonObject>()
        candidates["exact-legacy"] = legacyJson
        candidates["exact-keyed"] = keyedJson
        for (size in 1 until CircleCore.KEYED_FILE_BODY_KEYS.size) {
            for (subset in subsetsOfSize(CircleCore.KEYED_FILE_BODY_KEYS.toList(), size)) {
                val label = "subset-$size-" + subset.sorted().joinToString("_")
                candidates[label] = JsonObject(subset.associateWith { keyedJson[it]!! })
            }
        }
        candidates["keyed-plus-token"] = JsonObject(keyedJson.toMap() + ("token" to JsonPrimitive("t")))
        candidates["legacy-plus-token"] = JsonObject(legacyJson.toMap() + ("token" to JsonPrimitive("t")))
        candidates["renamed-sha256"] = JsonObject(keyedJson.toMap().let { fields ->
            fields - "sha256" + ("plainSha" to fields.getValue("sha256"))
        })

        for ((label, obj) in candidates) {
            val body = obj.toString()
            val exact = obj.keys == CircleCore.FILE_BODY_KEYS || obj.keys == CircleCore.KEYED_FILE_BODY_KEYS
            assertEquals(exact, CircleCore.validFileBody(body), "$label: gate disagrees with exact key sets")
            assertEquals(
                exact && obj.keys == CircleCore.KEYED_FILE_BODY_KEYS,
                DropKeyCore.parseFileKeyBody(body) != null,
                "$label: parser does not agree with the gate",
            )
        }
    }
}
