package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.HttpExchange
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canonical stability across the twelfth Circle verb. `pad` joins TYPES without
 * changing a single existing verb value or byte of signing input: the body is
 * covered as one length-prefixed field by the same FLITCIRCLE1 writer, and
 * `sig`/`cert` stay outside the signed bytes exactly as for every earlier verb.
 *
 * The golden vectors below are generated from the documented seven-field layout
 * (domain tag, then four-byte length-prefixed UTF-8 fields with `lamport` and
 * `createdMs` as eight-byte big-endian longs) and cross-checked against the
 * existing `ReceiptCanonicalTest` vectors for the earlier verbs. They never call
 * `CircleCore.canonical` to produce their own expected bytes. No socket,
 * Android class, Gradle run or clock sleep is used; every timestamp is fixed.
 */
class PadCanonicalTest {

    private val identities = mutableListOf<TlsIdentity>()

    private fun identity(): TlsIdentity = TlsIdentity.generate().also { identities += it }

    @BeforeTest
    fun resetState() {
        CircleCore.clear()
        identities.clear()
    }

    @AfterTest
    fun forgetGeneratedPins() {
        identities.forEach { TlsIdentity.forgetPin(it.fingerprint) }
        identities.clear()
        CircleCore.clear()
    }

    // ---- additive verb set -------------------------------------------------

    @Test
    fun `pad is the twelfth verb with every existing verb value unchanged`() {
        assertEquals("pad", CircleEvent.PAD)
        assertEquals(PRE_EXISTING + CircleEvent.PAD, CircleEvent.TYPES)
        assertEquals(12, CircleEvent.TYPES.size)
        assertEquals(
            mapOf(
                "MESSAGE" to "message", "FILE" to "file", "POLL" to "poll",
                "VOTE" to "vote", "JOIN" to "join", "LEAVE" to "leave",
                "KICK" to "kick", "ROLE" to "role", "REKEY" to "rekey",
                "PARCEL" to "parcel", "RECEIPT" to "receipt", "PAD" to "pad",
            ),
            mapOf(
                "MESSAGE" to CircleEvent.MESSAGE, "FILE" to CircleEvent.FILE,
                "POLL" to CircleEvent.POLL, "VOTE" to CircleEvent.VOTE,
                "JOIN" to CircleEvent.JOIN, "LEAVE" to CircleEvent.LEAVE,
                "KICK" to CircleEvent.KICK, "ROLE" to CircleEvent.ROLE,
                "REKEY" to CircleEvent.REKEY, "PARCEL" to CircleEvent.PARCEL,
                "RECEIPT" to CircleEvent.RECEIPT, "PAD" to CircleEvent.PAD,
            ),
            "a verb value changed",
        )
        assertTrue(CircleEvent.TYPES.containsAll(PRE_EXISTING))
        assertTrue(CircleEvent.PAD in CircleEvent.TYPES)
        assertFalse(CircleEvent.TYPES.contains("Pad"), "verb values are case-sensitive")
    }

    // ---- canonical vectors -------------------------------------------------

    @Test
    fun `pad canonical bytes match the fixed golden vector for put and del`() {
        val put = vectorEvent(CircleEvent.PAD, PUT_BODY)
        val del = vectorEvent(CircleEvent.PAD, DEL_BODY)

        assertContentEquals(hex(PAD_PUT_GOLDEN), CircleCore.canonical(put), "put canonical bytes changed")
        assertContentEquals(hex(PAD_DEL_GOLDEN), CircleCore.canonical(del), "del canonical bytes changed")
        assertContentEquals(
            hex(PAD_PUT_GOLDEN),
            CircleCore.canonical(put.id, put.circleId, put.authorFp, put.lamport, put.type, put.body, put.createdMs),
            "the explicit-field canonical form disagrees with the event form",
        )
        for (bytes in listOf(CircleCore.canonical(put), CircleCore.canonical(del))) {
            assertContentEquals(TAG_FIELD, bytes.copyOfRange(0, TAG_FIELD.size), "domain tag is not FLITCIRCLE1")
            assertContentEquals(independentCanonical(put).copyOfRange(0, TAG_FIELD.size), bytes.copyOfRange(0, TAG_FIELD.size))
        }
        assertContentEquals(independentCanonical(put), CircleCore.canonical(put))
        assertContentEquals(independentCanonical(del), CircleCore.canonical(del))
    }

    @Test
    fun `all seven fields are covered while sig and cert stay outside the bytes`() {
        val e = vectorEvent(CircleEvent.PAD, PUT_BODY)
        val signed = CircleCore.canonical(e)

        val edited = listOf(
            CircleCore.canonical(e.copy(id = "id-2")),
            CircleCore.canonical(e.copy(circleId = "circle-2")),
            CircleCore.canonical(e.copy(authorFp = "WXYZ")),
            CircleCore.canonical(e.copy(lamport = e.lamport + 1)),
            CircleCore.canonical(e.copy(type = CircleEvent.MESSAGE)),
            CircleCore.canonical(e.copy(body = """{"op":"put","key":"k","text":"changed"}""")),
            CircleCore.canonical(e.copy(createdMs = e.createdMs + 1)),
        )
        edited.forEachIndexed { index, bytes ->
            assertFalse(bytes.contentEquals(signed), "canonical field ${index + 1} is not covered")
        }
        assertEquals(7, edited.size)

        assertContentEquals(signed, CircleCore.canonical(e.copy(sig = "c2ln", cert = "Y2VydA==")), "sig/cert entered the signed bytes")
        assertContentEquals(signed, CircleCore.canonical(e.copy()), "a data-class copy changed the signed bytes")
        assertFalse(independentCanonical(e, "FLITCIRCLE2").contentEquals(signed), "domain tag is not covered")
    }

    // ---- signature ---------------------------------------------------------

    @Test
    fun `a minted pad event verifies and its body has one of the two closed shapes`() {
        val id = identity()
        val putBody = PadCore.padPutBody("k", "v")
        val delBody = PadCore.padDelBody("k")
        assertEquals(PUT_BODY, putBody)
        assertEquals(DEL_BODY, delBody)

        val put = CircleCore.create("circle-1", id.fingerprint, id, CircleEvent.PAD, putBody, 7L, FIXED_MS)
        val del = CircleCore.create("circle-1", id.fingerprint, id, CircleEvent.PAD, delBody, 8L, FIXED_MS)
        for (event in listOf(put, del)) {
            assertEquals(CircleEvent.PAD, event.type)
            assertEquals(id.fingerprint, event.authorFp)
            assertTrue(event.sig.isNotBlank())
            assertTrue(PadCore.validPadBody(event.body), "minted body refused by its gate")
            assertTrue(CircleCore.verify(event), "minted pad does not verify")
            assertTrue(id.verify(id.fingerprint, CircleCore.canonical(event), event.sig), "signature is not over canonical bytes")
            assertTrue(
                TlsIdentity.verifyWithCertificate(id.certificate, CircleCore.canonical(event), event.sig),
                "embedded cert does not verify the canonical bytes",
            )
        }
        val reSigned = CircleCore.sign(put.copy(sig = ""), id)
        assertTrue(CircleCore.verify(reSigned))
        assertContentEquals(CircleCore.canonical(put), CircleCore.canonical(reSigned))

        // Event shape and body gate: only {op,key,text} for put and {op,key}
        // for del. No second time field, path, author name, per-line key or
        // local status string may enter the signed body.
        assertTrue(PadCore.validPadBody(PUT_BODY))
        assertTrue(PadCore.validPadBody(DEL_BODY))
        for (refused in listOf(
            """{"op":"put","key":"k","text":"v","createdMs":7}""",
            """{"op":"put","key":"k","text":"v","path":"/tmp/k"}""",
            """{"op":"put","key":"k","text":"v","author":"alice"}""",
            """{"op":"put","key":"k","text":"v","status":"local"}""",
            """{"op":"put","key":"k","text":"v","keyId":"per-line"}""",
            """{"op":"del","key":"k","text":"v"}""",
            """{"op":"nope","key":"k"}""",
            """{"op":"put","key":"k","text":""}""",
            """{"op":"put","key":"","text":"v"}""",
            """{"op":"put","key":"k","text":1}""",
        )) {
            assertFalse(PadCore.validPadBody(refused), "pad gate accepted $refused")
        }
    }

    @Test
    fun `a forged authorFp or a changed covered field fails verify`() {
        val signer = identity()
        val victim = identity()
        TlsIdentity.forgetPin(victim.fingerprint) // first-seen claimed author must start unpinned

        val forged = CircleCore.create(
            "circle-1", victim.fingerprint, signer, CircleEvent.PAD, PUT_BODY, 7L, FIXED_MS,
        )
        assertFalse(CircleCore.verify(forged), "signer's pad verified under the victim's fingerprint")
        assertNull(TlsIdentity.pinned(victim.fingerprint), "failed verify poisoned the pin store")
        assertFalse(
            TlsIdentity.verify(victim.fingerprint, CircleCore.canonical(forged), forged.sig),
            "foreign fingerprint verified the signature",
        )

        val honest = CircleCore.create(
            "circle-1", signer.fingerprint, signer, CircleEvent.PAD, PUT_BODY, 7L, FIXED_MS,
        )
        assertTrue(CircleCore.verify(honest))
        val stranger = identity()
        val edits = mapOf(
            "authorFp" to honest.copy(authorFp = "DEADBEEF"),
            "body" to honest.copy(body = DEL_BODY),
            "id" to honest.copy(id = "other-id"),
            "circleId" to honest.copy(circleId = "other-circle"),
            "type" to honest.copy(type = CircleEvent.MESSAGE),
            "lamport" to honest.copy(lamport = honest.lamport + 1),
            "createdMs" to honest.copy(createdMs = honest.createdMs + 1),
            "sig" to honest.copy(sig = "not base64 !!"),
            "blank sig" to honest.copy(sig = ""),
            "cert" to honest.copy(cert = certB64(stranger)),
        )
        for ((label, edited) in edits) {
            assertFalse(CircleCore.verify(edited), "$label edit still verifies")
        }
    }

    // ---- existing verbs stay byte-identical --------------------------------

    @Test
    fun `existing verbs keep byte-identical canonical order dedup and accept`() {
        assertEquals(PRE_EXISTING.size + 1, CircleEvent.TYPES.size)
        for ((type, expectedHex) in EXISTING_GOLDEN) {
            assertContentEquals(
                hex(expectedHex),
                CircleCore.canonical(vectorEvent(type, EXISTING_BODIES.getValue(type))),
                "$type canonical bytes changed",
            )
        }

        val a = vectorEvent(CircleEvent.MESSAGE, EXISTING_BODIES.getValue(CircleEvent.MESSAGE), id = "e-1", authorFp = "AAAA", lamport = 1L)
        val b = vectorEvent(CircleEvent.VOTE, EXISTING_BODIES.getValue(CircleEvent.VOTE), id = "e-2", authorFp = "BBBB", lamport = 1L)
        val c = vectorEvent(CircleEvent.RECEIPT, EXISTING_BODIES.getValue(CircleEvent.RECEIPT), id = "e-3", authorFp = "AAAA", lamport = 2L)
        val scrambled = listOf(b, a, b, c, a)
        assertEquals(listOf("e-1", "e-2", "e-3"), CircleCore.order(scrambled).map { it.id }.distinct())
        assertEquals(listOf("e-1", "e-2", "e-3"), CircleCore.order(CircleCore.dedup(scrambled)).map { it.id })

        val low = vectorEvent(CircleEvent.MESSAGE, """{"text":"low"}""", id = "same", authorFp = "AAAA", lamport = 1L)
        val high = low.copy(body = """{"text":"high"}""", lamport = 2L)
        assertEquals(low, CircleCore.dedup(listOf(high, low)).single())
        assertEquals(low, CircleCore.dedup(listOf(low, high)).single())

        assertEquals(6 * 60 * 60 * 1000L, CircleCore.REPLAY_WINDOW_MS)
        assertEquals(5 * 60 * 1000L, CircleCore.CLOCK_SKEW_MS)
        assertEquals(4096, CircleCore.MAX_SEEN)
        assertEquals(1024, CircleCore.MAX_EVENTS_PER_REQUEST)
        assertEquals(8 * 1024 * 1024, CircleCore.MAX_REQUEST_BYTES)
        assertEquals(1024, CircleCore.MAX_SIG_CHARS)
        assertEquals(8 * 1024, CircleCore.MAX_CERT_CHARS)

        val now = 1_700_000_000_000L
        val onTime = vectorEvent(CircleEvent.MESSAGE, EXISTING_BODIES.getValue(CircleEvent.MESSAGE), id = "acc-1", createdMs = now)
        assertEquals(CircleCore.Decision.Invalid, CircleCore.evaluate(onTime.copy(id = ""), now))
        assertEquals(
            CircleCore.Decision.OutsideWindow,
            CircleCore.evaluate(onTime.copy(createdMs = now - CircleCore.REPLAY_WINDOW_MS - 1L), now),
        )
        assertEquals(
            CircleCore.Decision.OutsideWindow,
            CircleCore.evaluate(onTime.copy(createdMs = now + CircleCore.CLOCK_SKEW_MS + 1L), now),
        )
        assertEquals(CircleCore.Decision.Accept, CircleCore.accept(onTime, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.accept(onTime, now + 1L))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(onTime, now + 1L))
    }

    // ---- wire surface ------------------------------------------------------

    @Test
    fun `the pad verb adds no route header DTO field or v2_2 status`() {
        val routeFields = Protocol::class.java.declaredFields.map { it.name }
            .filter { it.startsWith("PATH_") || it == "API_ROOT" || it == "FLIT_ROOT" }.toSet()
        assertEquals(
            setOf(
                "API_ROOT", "FLIT_ROOT", "PATH_INFO", "PATH_REGISTER",
                "PATH_PREPARE_UPLOAD", "PATH_UPLOAD", "PATH_CANCEL", "PATH_PARTIAL",
                "PATH_PANIC", "PATH_CIRCLE_JOIN", "PATH_CIRCLE_SYNC",
            ),
            routeFields,
        )
        assertEquals(
            setOf("HEADER_PANIC_CERT", "HEADER_CIRCLE_CERT", "HEADER_FLIT_CLIENT", "HEADER_FLIT_REFUSAL"),
            Protocol::class.java.declaredFields.map { it.name }.filter { it.startsWith("HEADER_") }.toSet(),
        )
        assertEquals("2.2", Protocol.VERSION)
        assertFalse(routeFields.any { it.contains("pad", ignoreCase = true) }, "a pad route was added")

        val id = identity()
        val pad = CircleCore.create("circle-1", id.fingerprint, id, CircleEvent.PAD, PUT_BODY, 7L, FIXED_MS)
        val request = CircleRequest(
            action = CircleRequest.ACTION_SYNC,
            circleId = "circle-1",
            invite = "invite",
            memberFp = id.fingerprint,
            events = listOf(pad),
            body = "spare",
        )
        val response = CircleResponse(
            ok = true, key = "k", members = listOf(id.fingerprint), events = listOf(pad),
            reason = "", serverCert = "cert", sig = "sig",
        )
        val requestKeys = (WireJson.json.parseToJsonElement(WireJson.encode(request)) as JsonObject).keys
        val responseKeys = (WireJson.json.parseToJsonElement(WireJson.encode(response)) as JsonObject).keys
        assertEquals(setOf("action", "circleId", "invite", "memberFp", "events", "body"), requestKeys)
        assertEquals(setOf("ok", "key", "members", "events", "reason", "serverCert", "sig"), responseKeys)
        assertFalse((requestKeys + responseKeys).any { it.contains("pad", ignoreCase = true) }, "a pad DTO field was added")

        val seen = mutableListOf<CircleRequest>()
        val server = ServerCore(
            id,
            ServerConfig(tls = false, destDir = Files.createTempDirectory("pad-canonical")),
            object : ServerDelegate {
                override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision =
                    PrepareDecision.Accept(request.files.keys)

                override fun onCircle(request: CircleRequest, ip: String): CircleResponse {
                    seen += request
                    return CircleResponse(ok = true)
                }
            },
        )
        val certHeader = mapOf(
            Protocol.HEADER_CIRCLE_CERT.lowercase() to
                Base64.getEncoder().encodeToString(id.certificate.encoded),
        )
        val sync = StatusExchange("POST", Protocol.PATH_CIRCLE_SYNC, WireJson.encode(request), certHeader)
        val unknownPadRoute = StatusExchange("POST", "${Protocol.FLIT_ROOT}/circle/pad", "{}", certHeader)
        val malformed = StatusExchange("POST", Protocol.PATH_CIRCLE_SYNC, "{", certHeader)
        val info = StatusExchange("GET", Protocol.PATH_INFO)
        runBlocking {
            server.handle(sync)
            server.handle(unknownPadRoute)
            server.handle(malformed)
            server.handle(info)
        }
        assertEquals(200, sync.status, "pad must travel on the existing sync route")
        assertEquals(1, seen.size)
        assertEquals(CircleEvent.PAD, seen.single().events.single().type)
        assertEquals(404, unknownPadRoute.status, "a pad route appeared")
        assertEquals(400, malformed.status)
        assertEquals(200, info.status)
    }

    // ---- helpers -----------------------------------------------------------

    private class StatusExchange(
        override val method: String,
        override val path: String,
        private val body: String = "",
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpExchange {
        override val origin: String = "192.0.2.10"
        var status: Int = 0
        var response: String = ""
        private var at = 0

        override fun query(name: String): String? = null
        override fun header(name: String): String? = headers[name.lowercase()]
        override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (at >= bytes.size) return 0
            val n = minOf(len, bytes.size - at)
            System.arraycopy(bytes, at, buf, offset, n)
            at += n
            return n
        }
        override suspend fun respondText(status: Int, contentType: String, body: String) {
            this.status = status
            response = body
        }
        override suspend fun respondStatus(status: Int) {
            this.status = status
        }
    }

    private fun vectorEvent(
        type: String,
        body: String,
        id: String = "id-1",
        circleId: String = "circle-1",
        authorFp: String = "ABCD",
        lamport: Long = 7L,
        createdMs: Long = FIXED_MS,
        sig: String = "",
        cert: String = "",
    ): CircleEvent = CircleEvent(id, circleId, authorFp, lamport, type, body, sig, createdMs, cert)

    /** Rebuilds the documented canonical layout without calling CircleCore.canonical. */
    private fun independentCanonical(e: CircleEvent, tag: String = "FLITCIRCLE1"): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun field(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size)
            out.write(raw)
        }
        field(tag)
        field(e.id)
        field(e.circleId)
        field(e.authorFp)
        out.writeLong(e.lamport)
        field(e.type)
        field(e.body)
        out.writeLong(e.createdMs)
        out.flush()
        return bytes.toByteArray()
    }

    private fun certB64(id: TlsIdentity): String =
        Base64.getEncoder().encodeToString(id.certificate.encoded)

    companion object {
        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
            ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte()
        }

        private const val FIXED_MS = 1_800_000_000_000L
        private const val PUT_BODY = """{"op":"put","key":"k","text":"v"}"""
        private const val DEL_BODY = """{"op":"del","key":"k"}"""

        /** Four-byte big-endian length 11 followed by the UTF-8 domain tag. */
        private val TAG_FIELD: ByteArray =
            hex("0000000b") + "FLITCIRCLE1".toByteArray(Charsets.UTF_8)

        private val PRE_EXISTING = setOf(
            CircleEvent.MESSAGE,
            CircleEvent.FILE,
            CircleEvent.POLL,
            CircleEvent.VOTE,
            CircleEvent.JOIN,
            CircleEvent.LEAVE,
            CircleEvent.KICK,
            CircleEvent.ROLE,
            CircleEvent.REKEY,
            CircleEvent.PARCEL,
            CircleEvent.RECEIPT,
        )

        private val EXISTING_BODIES = mapOf(
            CircleEvent.MESSAGE to """{"text":"hi"}""",
            CircleEvent.FILE to """{"name":"a.txt","size":12,"sha256":"abc"}""",
            CircleEvent.POLL to """{"pollId":"p1","question":"q","options":["a","b"]}""",
            CircleEvent.VOTE to """{"pollId":"p1","option":1}""",
            CircleEvent.JOIN to """{"alias":"alice"}""",
            CircleEvent.LEAVE to "{}",
            CircleEvent.KICK to """{"kickId":"k1","targetFp":"ABCD","mode":"admin","approve":true}""",
            CircleEvent.ROLE to """{"targetFp":"ABCD","role":"ADMIN"}""",
            CircleEvent.REKEY to """{"keyB64":"a2V5","gen":1}""",
            CircleEvent.PARCEL to """{"toFp":"ABCD","envelope":"ZW52"}""",
            CircleEvent.RECEIPT to """{"dropId":"drop-1","sha256":"deadbeef","keyId":"key-1"}""",
        )

        /** Golden pad vectors, generated from the seven-field layout. */
        private const val PAD_PUT_GOLDEN =
            "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
            "652d310000000441424344000000000000000700000003706164000000217b22" +
            "6f70223a22707574222c226b6579223a226b222c2274657874223a2276227d00" +
            "0001a3185c5000"

        private const val PAD_DEL_GOLDEN =
            "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
            "652d310000000441424344000000000000000700000003706164000000167b22" +
            "6f70223a2264656c222c226b6579223a226b227d000001a3185c5000"

        /** Golden bytes for the eleven pre-pad verbs, unchanged by PAD. */
        private val EXISTING_GOLDEN = mapOf(
            CircleEvent.MESSAGE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000076d6573736167650000" +
                "000d7b2274657874223a226869227d000001a3185c5000",
            CircleEvent.FILE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000466696c65000000297b" +
                "226e616d65223a22612e747874222c2273697a65223a31322c22736861323536" +
                "223a22616263227d000001a3185c5000",
            CircleEvent.POLL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004706f6c6c000000327b" +
                "22706f6c6c4964223a227031222c227175657374696f6e223a2271222c226f70" +
                "74696f6e73223a5b2261222c2262225d7d000001a3185c5000",
            CircleEvent.VOTE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004766f74650000001a7b" +
                "22706f6c6c4964223a227031222c226f7074696f6e223a317d000001a3185c50" +
                "00",
            CircleEvent.JOIN to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000046a6f696e000000117b" +
                "22616c696173223a22616c696365227d000001a3185c5000",
            CircleEvent.LEAVE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000056c6561766500000002" +
                "7b7d000001a3185c5000",
            CircleEvent.KICK to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000046b69636b0000003f7b" +
                "226b69636b4964223a226b31222c227461726765744670223a2241424344222c" +
                "226d6f6465223a2261646d696e222c22617070726f7665223a747275657d0000" +
                "01a3185c5000",
            CircleEvent.ROLE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004726f6c65000000227b" +
                "227461726765744670223a2241424344222c22726f6c65223a2241444d494e22" +
                "7d000001a3185c5000",
            CircleEvent.REKEY to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000572656b657900000019" +
                "7b226b6579423634223a2261325635222c2267656e223a317d000001a3185c50" +
                "00",
            CircleEvent.PARCEL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000670617263656c000000" +
                "217b22746f4670223a2241424344222c22656e76656c6f7065223a225a573532" +
                "227d000001a3185c5000",
            CircleEvent.RECEIPT to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000007726563656970740000" +
                "00377b2264726f704964223a2264726f702d31222c22736861323536223a2264" +
                "65616462656566222c226b65794964223a226b65792d31227d000001a3185c50" +
                "00",
        )
    }
}
