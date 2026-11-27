package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.MAX_ENVELOPE_CHARS
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM checks of the frozen courier envelope wire form: the `FLITCOURIER1`
 * field layout, `encode`/`decode`, the published bounds, and refusal of tampered
 * bytes. No Android, socket, file or device state is touched.
 *
 * The expected canonical bytes are rebuilt locally from the published field
 * order so the test pins the byte layout, not the implementation's own helper.
 */
class CourierEnvelopeCodecTest {
    private val now = 1_700_000_000_000L

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun raw(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.block()
        out.flush()
        return bytes.toByteArray()
    }

    private fun field(out: DataOutputStream, value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        out.writeInt(utf8.size)
        out.write(utf8)
    }

    private fun fieldBytes(value: String): ByteArray = raw { field(this, value) }

    private fun sample(
        id: String = "env-1",
        circleId: String = "circle-1",
        fromFp: String = "A".repeat(64),
        toFp: String = "B".repeat(64),
        createdMs: Long = now,
        alg: String = "AESGCM+RSAOAEP256",
        ivB64: String = b64(ByteArray(12) { (it + 1).toByte() }),
        cipherB64: String = b64("cipher-bytes".toByteArray(Charsets.UTF_8)),
        keyWrapB64: String = b64(ByteArray(256) { 0x11 }),
        sig: String = b64(ByteArray(256) { 0x22 }),
        cert: String = b64(ByteArray(1_200) { 0x33 }),
    ): CourierEnvelope = CourierEnvelope(
        id = id,
        circleId = circleId,
        fromFp = fromFp,
        toFp = toFp,
        createdMs = createdMs,
        alg = alg,
        ivB64 = ivB64,
        cipherB64 = cipherB64,
        keyWrapB64 = keyWrapB64,
        sig = sig,
        cert = cert,
    )

    /** The nine length-prefixed canonical fields, written independently of the production helper. */
    private fun expectedCanonical(e: CourierEnvelope): ByteArray = raw {
        field(this, "FLITCOURIER1")
        field(this, e.id)
        field(this, e.circleId)
        field(this, e.fromFp)
        field(this, e.toFp)
        writeLong(e.createdMs)
        field(this, e.alg)
        field(this, e.ivB64)
        field(this, e.cipherB64)
        field(this, e.keyWrapB64)
    }

    private fun expectedWire(e: CourierEnvelope): ByteArray =
        expectedCanonical(e) + fieldBytes(e.sig) + fieldBytes(e.cert)

    private data class Span(val start: Int, val end: Int)

    private data class Layout(val bytes: ByteArray, val spans: List<Span>, val createdAt: Int)

    /**
     * Reads the eleven wire fields with their byte offsets. The createdMs long
     * sits between the fifth and sixth length-prefixed fields and is not a field.
     */
    private fun layout(encoded: String): Layout {
        val bytes = Base64.getDecoder().decode(encoded)
        var at = 0
        val spans = mutableListOf<Span>()
        fun nextField(): Span {
            check(at + 4 <= bytes.size) { "truncated length prefix" }
            val length = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
            check(length >= 0 && length <= bytes.size - at - 4) { "bad field length" }
            at += 4
            val span = Span(at, at + length)
            at += length
            spans += span
            return span
        }
        repeat(5) { nextField() }
        val createdAt = at
        at += 8
        repeat(6) { nextField() }
        check(at == bytes.size) { "trailing bytes" }
        return Layout(bytes, spans, createdAt)
    }

    private fun text(l: Layout, index: Int): String =
        String(l.bytes, l.spans[index].start, l.spans[index].end - l.spans[index].start, Charsets.UTF_8)

    @Test
    fun `canonical bytes are domain tagged and four byte length prefixed`() {
        val e = sample()
        assertContentEquals(expectedCanonical(e), CourierEnvelope.canonical(e))

        val l = layout(CourierEnvelope.encode(e))
        assertEquals(11, l.spans.size, "nine canonical fields plus sig and cert; createdMs is a long")
        assertEquals("FLITCOURIER1", text(l, 0))
        assertFalse("FLITCIRCLE1" == text(l, 0), "envelope bytes must not reuse the Circle tag")
        assertFalse("FLITCARRY1" == text(l, 0), "envelope bytes must not reuse the carry tag")
        assertContentEquals(
            byteArrayOf(0, 0, 0, 12),
            l.bytes.copyOfRange(l.spans[0].start - 4, l.spans[0].start),
        )
        assertContentEquals(
            "FLITCOURIER1".toByteArray(Charsets.UTF_8),
            l.bytes.copyOfRange(l.spans[0].start, l.spans[0].end),
        )
        assertEquals(now, ByteBuffer.wrap(l.bytes, l.createdAt, 8).order(ByteOrder.BIG_ENDIAN).long)
        assertContentEquals(expectedWire(e), l.bytes, "encode is canonical plus sig and cert")
    }

    @Test
    fun `every covered field changes canonical bytes while sig and cert are excluded`() {
        val e = sample()
        val base = CourierEnvelope.canonical(e)
        val changed = listOf(
            e.copy(id = "env-2"),
            e.copy(circleId = "circle-2"),
            e.copy(fromFp = "C".repeat(64)),
            e.copy(toFp = "D".repeat(64)),
            e.copy(createdMs = now + 1L),
            e.copy(alg = "AESGCM+RSAOAEP256X"),
            e.copy(ivB64 = b64(ByteArray(12) { 0x44 })),
            e.copy(cipherB64 = b64("other-cipher".toByteArray(Charsets.UTF_8))),
            e.copy(keyWrapB64 = b64(ByteArray(256) { 0x55 })),
        )
        changed.forEachIndexed { index, candidate ->
            assertFalse(
                base.contentEquals(CourierEnvelope.canonical(candidate)),
                "field change $index left the canonical bytes untouched",
            )
        }

        val reSigned = e.copy(sig = b64(ByteArray(64) { 0x66 }), cert = b64(ByteArray(2_400) { 0x77 }))
        assertContentEquals(base, CourierEnvelope.canonical(reSigned))
        assertFalse(
            CourierEnvelope.encode(e).contentEquals(CourierEnvelope.encode(reSigned)),
            "sig and cert still travel on the wire even though they are unsigned",
        )
    }

    @Test
    fun `length prefixes stop delimiter collisions and count UTF-8 bytes not chars`() {
        val splitAfterOne = sample(id = "a", circleId = "bc")
        val splitAfterTwo = sample(id = "ab", circleId = "c")
        assertFalse(
            CourierEnvelope.canonical(splitAfterOne).contentEquals(CourierEnvelope.canonical(splitAfterTwo)),
            "uncovered concatenation would make both values abc",
        )

        val unicode = sample(id = "é")
        val l = layout(CourierEnvelope.encode(unicode))
        val idSpan = l.spans[1]
        assertEquals(2, idSpan.end - idSpan.start, "é is two UTF-8 bytes, not one char")
        assertEquals("é", text(l, 1))
    }

    @Test
    fun `encode decode round trip preserves every field within the wire cap`() {
        val signed = sample()
        val encoded = CourierEnvelope.encode(signed)
        assertTrue(encoded.length <= MAX_ENVELOPE_CHARS)
        assertEquals(signed, CourierEnvelope.decode(encoded))

        val unsigned = sample(sig = "", cert = "")
        assertEquals(unsigned, CourierEnvelope.decode(CourierEnvelope.encode(unsigned)))
    }

    @Test
    fun `decode accepts fields at the published caps and refuses one past each cap`() {
        val maxField = "A".repeat(CircleCore.MAX_FIELD_CHARS)
        val overField = maxField + "A"
        assertEquals(maxField, CourierEnvelope.decode(CourierEnvelope.encode(sample(id = maxField)))?.id)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(id = overField))))

        assertEquals(maxField, CourierEnvelope.decode(CourierEnvelope.encode(sample(circleId = maxField)))?.circleId)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(circleId = overField))))

        assertEquals(maxField, CourierEnvelope.decode(CourierEnvelope.encode(sample(fromFp = maxField)))?.fromFp)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(fromFp = overField))))

        assertEquals(maxField, CourierEnvelope.decode(CourierEnvelope.encode(sample(toFp = maxField)))?.toFp)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(toFp = overField))))

        val maxAlg = "a".repeat(CircleCore.MAX_FIELD_CHARS)
        assertEquals(maxAlg, CourierEnvelope.decode(CourierEnvelope.encode(sample(alg = maxAlg)))?.alg)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(alg = maxAlg + "a"))))

        val maxSig = b64(ByteArray(768)) // 1024 chars
        val overSig = b64(ByteArray(769)) // 1028 chars
        assertEquals(maxSig, CourierEnvelope.decode(CourierEnvelope.encode(sample(sig = maxSig)))?.sig)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(sig = overSig))))

        val maxCert = b64(ByteArray(6_144)) // 8192 chars
        val overCert = b64(ByteArray(6_145)) // 8196 chars
        assertEquals(maxCert, CourierEnvelope.decode(CourierEnvelope.encode(sample(cert = maxCert)))?.cert)
        assertNull(CourierEnvelope.decode(CourierEnvelope.encode(sample(cert = overCert))))
    }

    @Test
    fun `decode refuses null blank non base64 short and non positive clock input`() {
        assertNull(CourierEnvelope.decode(null))
        assertNull(CourierEnvelope.decode(""))
        assertNull(CourierEnvelope.decode("   "))
        assertNull(CourierEnvelope.decode("not base64 !!"))
        assertNull(CourierEnvelope.decode(b64(ByteArray(0))))
        assertNull(CourierEnvelope.decode(b64(byteArrayOf(0, 0, 0))))
        assertNull(CourierEnvelope.decode(b64(byteArrayOf(0, 0, 0, 1))))
        assertNull(CourierEnvelope.decode(b64(expectedWire(sample(createdMs = 0L)))))
        assertNull(CourierEnvelope.decode(b64(expectedWire(sample(createdMs = -1L)))))
    }

    @Test
    fun `decode refuses every truncation of a signed wire envelope`() {
        val bytes = Base64.getDecoder().decode(CourierEnvelope.encode(sample()))
        assertEquals(sample(), CourierEnvelope.decode(b64(bytes)))
        for (cut in 1 until bytes.size) {
            assertNull(
                CourierEnvelope.decode(b64(bytes.copyOfRange(0, cut))),
                "a wire truncated at byte $cut was accepted",
            )
        }
    }

    @Test
    fun `decode refuses oversized negative and short field lengths`() {
        fun wireWithPrefix(length: Int, payload: ByteArray = ByteArray(0)): String = b64(raw {
            field(this, "FLITCOURIER1")
            writeInt(length)
            write(payload)
        })

        assertNull(CourierEnvelope.decode(wireWithPrefix(Int.MAX_VALUE)))
        assertNull(CourierEnvelope.decode(wireWithPrefix(1_000_000)))
        assertNull(CourierEnvelope.decode(wireWithPrefix(-1)))
        assertNull(CourierEnvelope.decode(wireWithPrefix(100, ByteArray(5))))
        assertNull(CourierEnvelope.decode(wireWithPrefix(0)))
    }

    @Test
    fun `decode refuses unknown trailing fields`() {
        val bytes = Base64.getDecoder().decode(CourierEnvelope.encode(sample()))
        assertNull(CourierEnvelope.decode(b64(bytes + fieldBytes("extra"))))
        assertNull(CourierEnvelope.decode(b64(bytes + fieldBytes(""))))
        assertNull(CourierEnvelope.decode(b64(bytes + byteArrayOf(0, 0, 0, 0))))
    }

    @Test
    fun `encoded envelope longer than MAX_ENVELOPE_CHARS is refused by decode`() {
        val oversized = sample(cipherB64 = "A".repeat(20_000))
        val encoded = b64(expectedWire(oversized))
        assertTrue(encoded.length > MAX_ENVELOPE_CHARS)
        assertNull(CourierEnvelope.decode(encoded))
    }

    @Test
    fun `a tampered ciphertext field decodes but fails signature verification`() {
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val event = CircleCore.create(
            "circle-1", sender.fingerprint, sender, CircleEvent.MESSAGE,
            """{"text":"courier"}""", 1L, now,
        )
        val sealed = requireNotNull(
            CourierEnvelope.seal(
                listOf(event), "circle-1", sender.fingerprint, recipient.fingerprint,
                recipient.certificate, sender, now,
            ),
        )
        assertTrue(CourierEnvelope.verify(sealed))

        val wire = layout(CourierEnvelope.encode(sealed))
        val cipher = wire.spans[7] // tag, id, circleId, fromFp, toFp, long, alg, ivB64, cipherB64
        val tampered = wire.bytes.copyOf()
        val first = tampered[cipher.start]
        tampered[cipher.start] = if (first == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()

        val decoded = CourierEnvelope.decode(b64(tampered))
        assertNotNull(decoded)
        assertFalse(CourierEnvelope.verify(decoded), "a changed ciphertext must break the signature")
    }
}
