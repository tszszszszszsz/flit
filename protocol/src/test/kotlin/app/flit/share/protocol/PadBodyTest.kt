package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure, structural tests for the Circle pad body gate. No crypto, no sockets,
 * no state: every assertion is on [PadCore.validPadBody] and on the two closed
 * key sets [PadCore.PAD_PUT_KEYS] / [PadCore.PAD_DEL_KEYS]. A pad body is one of
 * exactly two shapes, `{"op":"put","key":"k","text":"v"}` or
 * `{"op":"del","key":"k"}`: all three fields are non-blank bounded JSON strings
 * and `CircleEvent.createdMs` is the only time, so a second time field in the
 * body is extra. The body builders mint only bodies the gate accepts.
 */
class PadBodyTest {

    private fun putBody(): String = PadCore.padPutBody("k", "v")
    private fun delBody(): String = PadCore.padDelBody("k")

    private fun obj(body: String): JsonObject =
        WireJson.json.parseToJsonElement(body) as JsonObject

    private fun bodyWith(base: String, edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val fields = obj(base).toMap().toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    private fun refused(body: String, why: String) {
        assertFalse(PadCore.validPadBody(body), "gate accepted $why: ${body.take(80)}")
    }

    /** The gate parses and compares only: hostile input is refused, never thrown. */
    private fun total(body: String, why: String) {
        val result = runCatching { PadCore.validPadBody(body) }
        assertTrue(result.isSuccess, "gate threw on $why: ${body.take(80)}")
        assertFalse(result.getOrDefault(false), "gate accepted $why: ${body.take(80)}")
    }

    // ---- exact keys and accepted shapes ------------------------------------

    @Test
    fun `pad key sets are the frozen closed three and two keys`() {
        assertEquals(setOf("op", "key", "text"), PadCore.PAD_PUT_KEYS)
        assertEquals(setOf("op", "key"), PadCore.PAD_DEL_KEYS)
        assertEquals(PadCore.PAD_PUT_KEYS, obj(putBody()).keys)
        assertEquals(PadCore.PAD_DEL_KEYS, obj(delBody()).keys)
        assertFalse("createdMs" in PadCore.PAD_PUT_KEYS)
        assertFalse("createdMs" in PadCore.PAD_DEL_KEYS)
    }

    @Test
    fun `validPadBody accepts both closed shapes in any key order`() {
        assertTrue(PadCore.validPadBody(putBody()))
        assertTrue(PadCore.validPadBody(delBody()))
        assertTrue(PadCore.validPadBody("""{"text":"v","key":"k","op":"put"}"""))
        assertTrue(PadCore.validPadBody("""{"key":"k","op":"del"}"""))
        for (body in listOf(putBody(), delBody())) {
            assertTrue(body.length <= CircleCore.MAX_BODY_CHARS)
        }
    }

    @Test
    fun `validPadBody refuses every missing key`() {
        for (key in PadCore.PAD_PUT_KEYS) {
            refused(bodyWith(putBody()) { it.remove(key) }, "put body missing $key")
        }
        for (key in PadCore.PAD_DEL_KEYS) {
            refused(bodyWith(delBody()) { it.remove(key) }, "del body missing $key")
        }
        refused("{}", "empty object")
        refused("""{"op":"put","key":"k"}""", "put with the del key set")
        refused("""{"op":"put","text":"v"}""", "put without a key")
        refused("""{"key":"k"}""", "body without an op")
    }

    @Test
    fun `validPadBody refuses any extra key on either shape`() {
        val extras = listOf("createdMs", "lamport", "authorFp", "path", "token", "extra", "KEY", "Text")
        for (extra in extras) {
            refused(bodyWith(putBody()) { it[extra] = JsonPrimitive("x") }, "put with extra $extra")
            refused(bodyWith(delBody()) { it[extra] = JsonPrimitive("x") }, "del with extra $extra")
        }
        refused("""{"op":"del","key":"k","text":"v"}""", "del carrying put's text key")
        refused("""{"op":"put","key":"k","text":"v","key2":"k2"}""", "second line key")
    }

    @Test
    fun `validPadBody refuses renamed keys and unknown verbs`() {
        for (body in listOf(
            """{"verb":"put","key":"k","text":"v"}""",
            """{"op":"put","lineKey":"k","text":"v"}""",
            """{"op":"put","key":"k","value":"v"}""",
            """{"OP":"put","key":"k","text":"v"}""",
            """{"op":"PUT","key":"k","text":"v"}""",
            """{"verb":"del","key":"k"}""",
            """{"op":"del","lineKey":"k"}""",
            """{"op":"DEL","key":"k"}""",
            """{"op":"message","key":"k","text":"v"}""",
            """{"op":"","key":"k","text":"v"}""",
        )) {
            refused(body, "renamed or unknown key/verb")
        }
    }

    // ---- field types, blanks and bounds ------------------------------------

    @Test
    fun `validPadBody refuses non-string values for every field`() {
        val bad: List<JsonElement> = listOf(
            JsonPrimitive(7), JsonPrimitive(true), JsonNull,
            JsonObject(emptyMap()), JsonArray(emptyList()),
        )
        for (key in PadCore.PAD_PUT_KEYS) {
            for (value in bad) {
                refused(bodyWith(putBody()) { it[key] = value }, "put $key with value $value")
            }
        }
        for (key in PadCore.PAD_DEL_KEYS) {
            for (value in bad) {
                refused(bodyWith(delBody()) { it[key] = value }, "del $key with value $value")
            }
        }
        // No field of a pad body is a number: text is content, not a length.
        refused("""{"op":"put","key":"k","text":7}""", "numeric text")
        refused("""{"op":1,"key":"k","text":"v"}""", "numeric op")
    }

    @Test
    fun `validPadBody refuses blank op key and text`() {
        for (key in PadCore.PAD_PUT_KEYS) {
            for (blank in listOf("", " ", "\t\n ")) {
                refused(bodyWith(putBody()) { it[key] = JsonPrimitive(blank) }, "put blank $key")
            }
        }
        for (key in PadCore.PAD_DEL_KEYS) {
            for (blank in listOf("", " ", "\t\n ")) {
                refused(bodyWith(delBody()) { it[key] = JsonPrimitive(blank) }, "del blank $key")
            }
        }
    }

    @Test
    fun `validPadBody bounds the key at 128 characters`() {
        assertEquals(128, PadCore.MAX_KEY_CHARS)
        val atCap = "x".repeat(PadCore.MAX_KEY_CHARS)
        val overCap = "x".repeat(PadCore.MAX_KEY_CHARS + 1)
        assertTrue(PadCore.validPadBody(bodyWith(putBody()) { it["key"] = JsonPrimitive(atCap) }))
        assertTrue(PadCore.validPadBody(bodyWith(delBody()) { it["key"] = JsonPrimitive(atCap) }))
        refused(bodyWith(putBody()) { it["key"] = JsonPrimitive(overCap) }, "over-cap put key")
        refused(bodyWith(delBody()) { it["key"] = JsonPrimitive(overCap) }, "over-cap del key")
    }

    @Test
    fun `validPadBody bounds the text at 4096 characters`() {
        assertEquals(4096, PadCore.MAX_TEXT_CHARS)
        val atCap = "t".repeat(PadCore.MAX_TEXT_CHARS)
        val atCapBody = bodyWith(putBody()) { it["text"] = JsonPrimitive(atCap) }
        assertTrue(PadCore.validPadBody(atCapBody))
        assertTrue(atCapBody.length <= CircleCore.MAX_BODY_CHARS)
        refused(
            bodyWith(putBody()) { it["text"] = JsonPrimitive("t".repeat(PadCore.MAX_TEXT_CHARS + 1)) },
            "over-cap text",
        )
    }

    @Test
    fun `validPadBody accepts a body exactly at MAX_BODY_CHARS and refuses one over`() {
        val body = putBody()
        // Whitespace inside the object keeps every field individually valid
        // while moving the whole body to the cap; the length check is first.
        val atCap = body.dropLast(1) +
            " ".repeat(CircleCore.MAX_BODY_CHARS - body.length) + "}"
        assertEquals(CircleCore.MAX_BODY_CHARS, atCap.length)
        assertTrue(PadCore.validPadBody(atCap))
        refused(atCap + " ", "body one character over MAX_BODY_CHARS")
        refused(" ".repeat(CircleCore.MAX_BODY_CHARS) + body, "far over-cap body")
    }

    // ---- builders mint bodies the gate accepts -----------------------------

    @Test
    fun `padPutBody emits the exact three-key put shape the gate accepts`() {
        val body = PadCore.padPutBody("a", "hello")
        assertEquals("""{"op":"put","key":"a","text":"hello"}""", body)
        assertEquals(PadCore.PAD_PUT_KEYS, obj(body).keys)
        assertEquals("put", (obj(body)["op"] as JsonPrimitive).content)
        assertTrue(PadCore.validPadBody(body))

        val escaped = PadCore.padPutBody("line \"1\"\n", "tab\tback\\slash")
        assertTrue(PadCore.validPadBody(escaped))
        assertEquals("line \"1\"\n", (obj(escaped)["key"] as JsonPrimitive).content)
        assertEquals("tab\tback\\slash", (obj(escaped)["text"] as JsonPrimitive).content)
    }

    @Test
    fun `padDelBody emits the exact two-key del shape the gate accepts`() {
        val body = PadCore.padDelBody("a")
        assertEquals("""{"op":"del","key":"a"}""", body)
        assertEquals(PadCore.PAD_DEL_KEYS, obj(body).keys)
        assertEquals("del", (obj(body)["op"] as JsonPrimitive).content)
        assertTrue(PadCore.validPadBody(body))
    }

    @Test
    fun `builders mint accepted bodies including a put at the text cap`() {
        val key = "k".repeat(PadCore.MAX_KEY_CHARS)
        val text = "t".repeat(PadCore.MAX_TEXT_CHARS)
        val put = PadCore.padPutBody(key, text)
        assertTrue(PadCore.validPadBody(put))
        assertTrue(put.length <= CircleCore.MAX_BODY_CHARS)
        assertEquals(text, (obj(put)["text"] as JsonPrimitive).content)
        assertEquals(key, (obj(put)["key"] as JsonPrimitive).content)

        val del = PadCore.padDelBody(key)
        assertTrue(PadCore.validPadBody(del))
        assertTrue(del.length <= CircleCore.MAX_BODY_CHARS)

        val unicode = PadCore.padPutBody("ключ🙂", "café " + String(Character.toChars(0x1F642)))
        assertTrue(PadCore.validPadBody(unicode))
        assertEquals("ключ🙂", (obj(unicode)["key"] as JsonPrimitive).content)
    }

    @Test
    fun `builders refuse blank and over-cap fields before signing`() {
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("", "v") }
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody(" ", "v") }
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("k", "") }
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("k", "\t\n ") }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padPutBody("k".repeat(PadCore.MAX_KEY_CHARS + 1), "v")
        }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padPutBody("k", "v".repeat(PadCore.MAX_TEXT_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> { PadCore.padDelBody("") }
        assertFailsWith<IllegalArgumentException> { PadCore.padDelBody("  ") }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padDelBody("k".repeat(PadCore.MAX_KEY_CHARS + 1))
        }
    }

    // ---- malformed input and totality --------------------------------------

    @Test
    fun `validPadBody refuses blank malformed and non-object bodies`() {
        for (body in listOf(
            "", " ", "\t\n", "{", "}", "[]", "null", "true", "42", "\"text\"",
            """{"op":"put","key":"k","text":"v"} trailing""",
            """{"op":"put","key":"k","text":"v"""",
            """["put","k","v"]""",
            """{"op":"put","key":"k","text":"v"},""",
        )) {
            refused(body, "malformed or non-object body")
        }
    }

    @Test
    fun `validPadBody stays total on hostile input and never throws`() {
        val nul = Char(0).toString()
        val high = Char(0xD800).toString()
        val low = Char(0xDC00).toString()
        val hostile = listOf(
            nul, high, low, high + low,
            "{\"op\":\"put\",\"key\":\"k\",\"text\":" + nul + "\"v\"}",
            "{\"op\":\"put\",\"key\":\"\\uZZZZ\",\"text\":\"v\"}",
            "{\"op\":\"put\",\"key\":\"k\",\"text\":\"v\\q\"}",
            """{"op":"put","key":"k","text":"v}""",
            """{"op":"put","key":"k","text":"v"} trailing""",
            "{\"op\":\"put\",\"key\":\"k\",\"text\":\"" +
                "x".repeat(CircleCore.MAX_BODY_CHARS) + "\"}",
            "[".repeat(256) + "1" + "]".repeat(256),
            "{\"op\":" + "{\"nested\":".repeat(64) + "1" + "}".repeat(64) + "}",
            """{"op":{},"key":[],"text":null}""",
            """{"op":"put","key":"k","text":"v","":""}""",
        )
        for (body in hostile) {
            total(body, "hostile body")
        }
        // No state is retained: a refused hostile body cannot colour the gate.
        assertTrue(PadCore.validPadBody(putBody()))
        assertTrue(PadCore.validPadBody(delBody()))
    }
}
