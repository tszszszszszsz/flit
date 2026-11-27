package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.courier.MAX_PARCELS
import app.flit.share.protocol.courier.MAX_PARCEL_TTL_MS
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure JVM codec tests for the carrier's persisted bag. No socket, no Android,
 * no file persistence: every blob is built in memory from signed `parcel`
 * events and handed straight to [CourierCarryCore.encode]/[CourierCarryCore.decode].
 *
 * Rows are edited through `JsonObject` copies rather than string surgery, so a
 * repair case changes exactly one field while leaving the signed event and
 * every other field intact. Two repair paths are distinguished: a row that
 * still deserialises is normalised or dropped individually, while a row whose
 * JSON types are wrong makes the whole blob unreadable (an empty state).
 */
class CourierCarryCodecTest {
    private val target = "A1B2C3"
    private val identity = TlsIdentity.generate()

    @BeforeTest
    fun reset() = CourierCarryCore.clear()

    /** A real signature so a decode that checks authorship cannot reject a fixture. */
    private fun signedEvent(id: String, toFp: String, createdMs: Long): CircleEvent {
        val unsigned = CircleEvent(
            id = id,
            circleId = "circle-1",
            authorFp = identity.fingerprint,
            lamport = 1L,
            type = CircleEvent.PARCEL,
            body = """{"toFp":"$toFp","envelope":"AA=="}""",
            sig = "",
            createdMs = createdMs,
            cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        )
        return CircleCore.sign(unsigned, identity)
    }

    private fun parcel(
        id: String,
        toFp: String = target,
        createdMs: Long = System.currentTimeMillis(),
        expiresMs: Long = createdMs + MAX_PARCEL_TTL_MS,
    ): CourierParcel = CourierParcel(
        id = id,
        toFp = toFp,
        fromFp = identity.fingerprint,
        createdMs = createdMs,
        expiresMs = expiresMs,
        event = signedEvent(id, toFp, createdMs),
    )

    /** Extract the row objects of an encoded bag, without assuming their order. */
    private fun rowsOf(state: CarryState): List<JsonObject> {
        val root = WireJson.json.parseToJsonElement(CourierCarryCore.encode(state)) as? JsonObject
            ?: return emptyList()
        val rows = root["parcels"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { it as? JsonObject }
    }

    private fun rowsToRaw(rows: List<JsonObject>): String = buildJsonObject {
        put("parcels", buildJsonArray { rows.forEach { add(it) } })
    }.toString()

    private fun JsonObject.changed(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(toMap() + pairs.toMap())

    private fun JsonObject.event(): JsonObject = this["event"] as? JsonObject
        ?: throw AssertionError("fixture row has no event object")

    // ---- round-trip --------------------------------------------------------

    @Test
    fun `encode and decode round-trip an ordered bag without changing any field`() {
        val at = System.currentTimeMillis()
        val state = CarryState(
            listOf(
                parcel("p1", createdMs = at),
                parcel("p2", createdMs = at + 1L),
                parcel("p3", createdMs = at + 2L, toFp = "D4E5F6"),
            ),
        )
        val raw = CourierCarryCore.encode(state)
        assertTrue(raw.isNotBlank(), "encode produced no bytes")

        val decoded = CourierCarryCore.decode(raw)
        assertNotNull(decoded)
        assertEquals(state, decoded, "a decoded bag must equal the encoded one")
        assertEquals(raw, CourierCarryCore.encode(decoded), "encoding must be deterministic")
    }

    @Test
    fun `an encoded empty bag decodes back to an empty bag`() {
        val decoded = CourierCarryCore.decode(CourierCarryCore.encode(CarryState()))
        assertNotNull(decoded)
        assertEquals(emptyList(), decoded.parcels)
    }

    // ---- corrupt blobs are total -------------------------------------------

    @Test
    fun `decode is total on null blank truncated and wrong-shaped blobs`() {
        val corrupt: List<String?> = listOf(
            null,
            "",
            "   ",
            "{",
            "[",
            "null",
            "[]",
            "{}",
            "garbage",
            """{"parcels":"not-an-array"}""",
            """{"parcels":[1,2,3]}""",
            """{"parcels":[{}]}""",
            """{"parcels":[null]}""",
            "x".repeat(64 * 1024),
        )
        for (raw in corrupt) {
            assertEmpty(CourierCarryCore.decode(raw), "corrupt blob: ${raw?.take(40)}")
        }
    }

    @Test
    fun `decode of a blob with a wrong field type yields an empty state`() {
        val row = rowsOf(CarryState(listOf(parcel("p1")))).single()
        val unreadable = listOf(
            JsonObject(row.toMap() - "toFp"),
            JsonObject(row.toMap() - "event"),
            row.changed("event" to JsonPrimitive(42)),
            row.changed("createdMs" to JsonPrimitive("yesterday")),
            row.changed("expiresMs" to JsonPrimitive("later")),
            row.changed("id" to JsonPrimitive(17)),
        )
        for (broken in unreadable) {
            assertEmpty(
                CourierCarryCore.decode(rowsToRaw(listOf(row, broken))),
                "one unreadable row must not leave a partial bag: ${broken.keys}",
            )
        }
    }

    // ---- tolerant repair ---------------------------------------------------

    @Test
    fun `decode drops rows with a blank or oversized id or toFp and keeps the rest`() {
        val at = System.currentTimeMillis()
        val goodRow = rowsOf(CarryState(listOf(parcel("good", createdMs = at)))).single()

        val blankId = JsonObject(
            goodRow.toMap() + mapOf(
                "id" to JsonPrimitive(""),
                "event" to JsonObject(goodRow.event().toMap() + ("id" to JsonPrimitive(""))),
            ),
        )
        val blankToFp = JsonObject(
            goodRow.toMap() + mapOf(
                "id" to JsonPrimitive("blank-to"),
                "toFp" to JsonPrimitive("   "),
                "event" to JsonObject(
                    goodRow.event().toMap() +
                        ("id" to JsonPrimitive("blank-to")) +
                        ("body" to JsonPrimitive("""{"toFp":"","envelope":"AA=="}""")),
                ),
            ),
        )
        val longId = goodRow.changed("id" to JsonPrimitive("x".repeat(CircleCore.MAX_FIELD_CHARS + 1)))
        val longToFp = goodRow.changed("toFp" to JsonPrimitive("B".repeat(CircleCore.MAX_FIELD_CHARS + 1)))

        val raw = rowsToRaw(listOf(blankId, goodRow, blankToFp, longId, longToFp))
        val decoded = CourierCarryCore.decode(raw)
        assertNotNull(decoded)
        assertEquals(listOf("good"), decoded.parcels.map { it.id })
    }

    @Test
    fun `decode drops rows with a non-positive or overflowing createdMs and keeps the rest`() {
        val at = System.currentTimeMillis()
        val goodRow = rowsOf(CarryState(listOf(parcel("good", createdMs = at)))).single()
        val zero = goodRow.changed("id" to JsonPrimitive("zero"), "createdMs" to JsonPrimitive(0L))
        val negative = goodRow.changed("id" to JsonPrimitive("negative"), "createdMs" to JsonPrimitive(-1L))
        val overflow = goodRow.changed(
            "id" to JsonPrimitive("overflow"),
            "createdMs" to JsonPrimitive(Long.MAX_VALUE),
        )

        val decoded = CourierCarryCore.decode(rowsToRaw(listOf(zero, goodRow, negative, overflow)))
        assertNotNull(decoded)
        assertEquals(listOf("good"), decoded.parcels.map { it.id })
    }

    @Test
    fun `decode drops structurally expired rows and expire applies the wall clock`() {
        val now = System.currentTimeMillis()
        val live = parcel("live", createdMs = now)
        val inverted = parcel("inverted", createdMs = now, expiresMs = now - 1L)
        val old = parcel("old", createdMs = now - MAX_PARCEL_TTL_MS - 60_000L)
        val raw = rowsToRaw(rowsOf(CarryState(listOf(inverted, live, old))))

        val decoded = CourierCarryCore.decode(raw)
        assertNotNull(decoded)
        assertEquals(listOf("old", "live"), decoded.parcels.map { it.id })
        assertEquals(
            old.createdMs + MAX_PARCEL_TTL_MS,
            decoded.parcels.first { it.id == "old" }.expiresMs,
            "a held row may not pin its own expiry",
        )
        assertEquals(listOf("live"), CourierCarryCore.expire(decoded, now).parcels.map { it.id })
    }

    @Test
    fun `decode keeps the first row for a duplicate id`() {
        val at = System.currentTimeMillis()
        val first = parcel("dup", toFp = "AAAA", createdMs = at)
        val other = parcel("other", toFp = "BBBB", createdMs = at)
        val firstRow = rowsOf(CarryState(listOf(first))).single()
        val otherRow = rowsOf(CarryState(listOf(other))).single()
        // Same id and same signed event, different row-level addressee: the
        // first copy must win even though the later copy is in the same blob.
        val clone = firstRow.changed("toFp" to JsonPrimitive("CCCC"))

        val decoded = CourierCarryCore.decode(rowsToRaw(listOf(firstRow, otherRow, clone)))
        assertNotNull(decoded)
        assertEquals(listOf("dup", "other"), decoded.parcels.map { it.id })
        assertEquals("AAAA", decoded.parcels.first { it.id == "dup" }.toFp)
    }

    @Test
    fun `decode repairs a numeric expiry that disagrees with createdMs plus the TTL`() {
        val at = System.currentTimeMillis()
        val rows = listOf("e1", "e2", "e3").map { id ->
            rowsOf(CarryState(listOf(parcel(id, createdMs = at)))).single()
        }
        val wrong = listOf(
            rows[0].changed("expiresMs" to JsonPrimitive(at + 1L)),
            rows[1].changed("expiresMs" to JsonPrimitive(at + MAX_PARCEL_TTL_MS - 1L)),
            rows[2].changed("expiresMs" to JsonPrimitive(at + MAX_PARCEL_TTL_MS + 1L)),
        )

        val decoded = CourierCarryCore.decode(rowsToRaw(wrong))
        assertNotNull(decoded)
        assertEquals(listOf("e1", "e2", "e3"), decoded.parcels.map { it.id })
        assertEquals(
            listOf(at + MAX_PARCEL_TTL_MS, at + MAX_PARCEL_TTL_MS, at + MAX_PARCEL_TTL_MS),
            decoded.parcels.map { it.expiresMs },
        )
    }

    @Test
    fun `decode trims an over-cap bag by order rather than arrival`() {
        val at = System.currentTimeMillis()
        val parcels = (0 until MAX_PARCELS + 12).map { i ->
            parcel("p${i.toString().padStart(3, '0')}", createdMs = at)
        }
        val shuffled = parcels.shuffled(Random(7))
        val decoded = CourierCarryCore.decode(rowsToRaw(rowsOf(CarryState(shuffled))))
        assertNotNull(decoded)
        assertEquals(MAX_PARCELS, decoded.parcels.size)
        assertEquals(parcels.takeLast(MAX_PARCELS).map { it.id }, decoded.parcels.map { it.id })
    }

    private fun assertEmpty(decoded: CarryState?, label: String) {
        assertTrue(decoded?.parcels.isNullOrEmpty(), "$label decoded to ${decoded?.parcels?.size} row(s)")
    }
}
