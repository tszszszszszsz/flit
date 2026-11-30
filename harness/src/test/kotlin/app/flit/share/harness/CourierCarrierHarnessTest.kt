package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.PARCEL
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLI carrier's two-node walk, in one JVM but over the *production* layers:
 * [CourierCli.run] drives the one-shot commands, [CourierEnvelope] seals and
 * opens with the real RSA/AES-GCM codec, [CourierCarryCore] encodes/decodes the
 * bag, and [CircleCore] mints, verifies and dedups the events. There is no
 * lookalike cipher, no stub carry codec and no second signature path here.
 *
 * Two CLI-state nodes need two harness homes. `FLIT_HARNESS_HOME` is read via
 * [System.getenv] and cannot be changed inside a JDK 21 test JVM without an
 * `--add-opens` JVM flag, so each [Node] points `user.home` at its own temp
 * root; [harnessHome] then resolves to `<root>/.config/flit/flit-harness` at
 * call time. Every CLI call, identity load and state read is wrapped in
 * [Node.inHome], so the two nodes never share identity, rows or circles. The
 * bag is an explicit per-node `--bag` path, moved by plain file copy — the
 * carrier implements no transport.
 */
class CourierCarrierHarnessTest {

    private class Node(tag: String) {
        val root: Path = Files.createTempDirectory("courier-$tag-")
        /** The value installed as `user.home` while this node is active. */
        val home: Path = root.resolve("home").also { Files.createDirectories(it) }
        /** The portable handoff unit, passed as `--bag`. */
        val bag: Path = root.resolve("bag").also { Files.createDirectories(it) }

        fun <T> inHome(block: () -> T): T {
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                return block()
            } finally {
                if (previous == null) System.clearProperty("user.home")
                else System.setProperty("user.home", previous)
            }
        }

        fun cli(vararg argv: String): Int = inHome {
            CourierCli.run(ParsedArgs(arrayOf("courier", *argv, "--bag", bag.toString())))
        }

        fun readRows(): List<CourierCli.Row> = inHome {
            val path = CourierCli.statePath(ParsedArgs(arrayOf("courier")))
            if (!Files.exists(path)) emptyList()
            else CourierCli.decodeRows(Files.readString(path, StandardCharsets.UTF_8))
        }

        fun writeRows(rows: List<CourierCli.Row>) = inHome {
            val path = CourierCli.statePath(ParsedArgs(arrayOf("courier")))
            Files.createDirectories(path.parent)
            Files.writeString(path, CourierCli.encodeRows(rows), StandardCharsets.UTF_8)
        }

        fun bagFiles(): List<Path> = Files.list(bag).use { stream ->
            stream.filter { Files.isRegularFile(it) }.iterator().asSequence()
                .sortedBy { it.fileName.toString() }.toList()
        }

        /** Line 5 of `circle.state` is the WireJson-encoded event log. */
        fun circleEvents(): List<CircleEvent> = inHome {
            val path = harnessHome().resolve("circle.state")
            if (!Files.exists(path)) return@inHome emptyList()
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (lines.size < 6) return@inHome emptyList()
            runCatching { WireJson.json.decodeFromString<List<CircleEvent>>(lines[5]) }
                .getOrDefault(emptyList())
        }

        fun rejectedFiles(): Long = inHome {
            val dir = harnessHome().resolve(CourierCli.REJECTED_DIR)
            if (!Files.exists(dir)) 0L
            else Files.list(dir).use { it.count() }
        }
    }

    private fun copyBag(from: Node, to: Node) {
        Files.createDirectories(to.bag)
        for (file in from.bagFiles()) {
            Files.copy(file, to.bag.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun certB64(identity: TlsIdentity): String =
        Base64.getEncoder().encodeToString(identity.certificate.encoded)

    private fun envelopeOf(event: CircleEvent): String =
        ((WireJson.json.parseToJsonElement(event.body) as JsonObject)["envelope"] as JsonPrimitive).content

    /** A signed event sealed by the production codec, then wrapped by the production carry codec. */
    private fun makeParcel(
        sender: TlsIdentity,
        circleId: String,
        toFp: String,
        recipientCert: X509Certificate,
        text: String,
        createdMs: Long,
    ): app.flit.share.protocol.courier.CourierParcel {
        val inner = CircleCore.create(
            circleId, sender.fingerprint, sender, CircleEvent.MESSAGE,
            """{"text":"$text"}""", lamport = 0L, createdMs = createdMs,
        )
        val envelope = CourierEnvelope.seal(
            listOf(inner), circleId, sender.fingerprint, toFp, recipientCert, sender, createdMs,
        ) ?: error("production seal refused a small, valid event")
        val body = buildJsonObject {
            put("toFp", JsonPrimitive(toFp))
            put("envelope", JsonPrimitive(CourierEnvelope.encode(envelope)))
        }.toString()
        val parcelEvent = CircleCore.create(
            circleId, sender.fingerprint, sender, PARCEL, body, lamport = 1L, createdMs = createdMs,
        )
        return CourierCarryCore.parcelOf(parcelEvent) ?: error("production parcelOf refused a valid parcel")
    }

    private fun flipFirst(s: String): String {
        require(s.isNotEmpty())
        val replacement = if (s[0] == 'A') 'B' else 'A'
        return replacement + s.substring(1)
    }

    @Test
    fun `drop carry then receive folds one event and marks exact rows across two homes`() {
        val a = Node("a-drop")
        val b = Node("b-recv")
        val circleId = "courier-harness-circle"
        val aId = a.inHome { loadOrCreateIdentity() }
        val bId = b.inHome { loadOrCreateIdentity() }
        val aFp = aId.fingerprint
        val bFp = bId.fingerprint

        // drop: a signed event is sealed into one deterministic .parcel file.
        assertEquals(
            0,
            a.cli("drop", "--to", bFp, "--cert", certB64(bId), "--text", "sealed note", "--circle", circleId),
        )
        val pending = a.readRows().single()
        assertEquals("pending", pending.state)
        assertEquals(bFp, pending.recipientFp)
        assertEquals(aFp, pending.senderFp)
        assertEquals(circleId, pending.circleId)
        assertTrue(pending.createdMs > 0L)
        assertEquals(pending.createdMs + CourierCarryCore.MAX_PARCEL_TTL_MS, pending.expiresMs)

        val aFiles = a.bagFiles()
        assertEquals(1, aFiles.size)
        assertEquals("${pending.id}.parcel", aFiles.single().fileName.toString())
        val onDisk = CourierCarryCore.decode(Files.readString(aFiles.single(), StandardCharsets.UTF_8))!!
            .parcels.single()
        assertEquals(pending.id, onDisk.id)
        assertEquals(bFp, onDisk.toFp)
        assertEquals(aFp, onDisk.fromFp)
        assertEquals(pending.expiresMs, onDisk.expiresMs)
        assertTrue(CircleCore.verify(onDisk.event), "the dropped parcel event must be production-signed")

        // carry: the local bag is verified and the row moves pending -> carried.
        assertEquals(0, a.cli("carry"))
        assertEquals("carried", a.readRows().single().state)

        // Plain directory copy is the handoff; B also needs the local row bookkeeping.
        copyBag(a, b)
        b.writeRows(a.readRows())

        // receive: production open returns the event, which is folded into circle.state.
        assertEquals(0, b.cli("receive"))
        val delivered = b.readRows().single()
        assertEquals("delivered", delivered.state)
        assertEquals(pending.id, delivered.id)
        assertEquals(bFp, delivered.recipientFp)
        assertEquals(aFp, delivered.senderFp)
        assertEquals(circleId, delivered.circleId)
        assertEquals(pending.createdMs, delivered.createdMs)
        assertEquals(pending.expiresMs, delivered.expiresMs)

        val folded = b.circleEvents()
        assertEquals(1, folded.size)
        val event = folded.single()
        assertEquals(circleId, event.circleId)
        assertEquals(aFp, event.authorFp)
        assertEquals(CircleEvent.MESSAGE, event.type)
        assertTrue(CircleCore.verify(event), "the folded event must pass production verification")
        assertTrue(event.body.contains("sealed note"), "the folded event carries the dropped text")
    }

    @Test
    fun `a parcel for a third fingerprint stays pending at the carrier and is re-exported`() {
        val a = Node("a-third")
        val b = Node("b-carrier")
        val c = Node("c-third")
        val circleId = "courier-third-fp"
        val aId = a.inHome { loadOrCreateIdentity() }
        b.inHome { loadOrCreateIdentity() }
        val cId = c.inHome { loadOrCreateIdentity() }
        val cFp = cId.fingerprint

        assertEquals(
            0,
            a.cli("drop", "--to", cFp, "--cert", certB64(cId), "--text", "for c", "--circle", circleId),
        )
        val row = a.readRows().single()
        assertEquals("pending", row.state)
        assertEquals(cFp, row.recipientFp)

        // B is a carrier, not the recipient: open never concludes this parcel.
        copyBag(a, b)
        b.writeRows(a.readRows())
        assertEquals(0, b.cli("receive"))
        assertEquals("pending", b.readRows().single().state, "a wrong-recipient parcel must not be refused")
        assertEquals(1, b.bagFiles().size, "the parcel stays in the bag for the next carrier")
        assertTrue(b.circleEvents().isEmpty(), "a carrier folds nothing it cannot open")

        // The next carrier is the addressee: the very same bytes now fold once.
        copyBag(b, c)
        c.writeRows(a.readRows())
        assertEquals(0, c.cli("receive"))
        val delivered = c.readRows().single()
        assertEquals("delivered", delivered.state)
        assertEquals(row.id, delivered.id)
        assertEquals(cFp, delivered.recipientFp)
        assertEquals(1, c.circleEvents().size)
        assertEquals(CircleEvent.MESSAGE, c.circleEvents().single().type)
    }

    @Test
    fun `a tampered parcel is refused quarantined and never folded`() {
        val a = Node("a-tamper")
        val b = Node("b-tamper")
        val circleId = "courier-tampered"
        val aId = a.inHome { loadOrCreateIdentity() }
        val bId = b.inHome { loadOrCreateIdentity() }
        val now = System.currentTimeMillis()

        // Seal a real parcel for B, then corrupt only the ciphertext inside the
        // envelope. The parcel bytes stay valid for CourierCarry; the signature
        // over the canonical envelope no longer verifies.
        val original = makeParcel(aId, circleId, bId.fingerprint, bId.certificate, "tamper target", now)
        val envelope = CourierEnvelope.decode(envelopeOf(original.event))
            ?: error("production envelope decode refused a freshly sealed envelope")
        val broken = envelope.copy(cipherB64 = flipFirst(envelope.cipherB64))
        assertNull(
            CourierEnvelope.open(broken, bId, now),
            "production open must refuse the tampered envelope",
        )
        val badBody = buildJsonObject {
            put("toFp", JsonPrimitive(bId.fingerprint))
            put("envelope", JsonPrimitive(CourierEnvelope.encode(broken)))
        }.toString()
        val badEvent = CircleCore.create(
            circleId, aId.fingerprint, aId, PARCEL, badBody, lamport = 1L, createdMs = now,
        )
        val badParcel = CourierCarryCore.parcelOf(badEvent)!!
        Files.writeString(
            a.bag.resolve("${badParcel.id}.parcel"),
            CourierCarryCore.encode(CarryState(listOf(badParcel))),
            StandardCharsets.UTF_8,
        )
        a.writeRows(
            listOf(
                CourierCli.Row(
                    badParcel.id, bId.fingerprint, aId.fingerprint, circleId,
                    "pending", badParcel.createdMs, badParcel.expiresMs,
                ),
            ),
        )

        copyBag(a, b)
        b.writeRows(a.readRows())
        assertEquals(CourierCli.EXIT_OPEN_REFUSED, b.cli("receive"))

        assertEquals("refused", b.readRows().single().state)
        assertTrue(b.circleEvents().isEmpty(), "a refused parcel must not reach circle.state")
        assertEquals(1L, b.rejectedFiles(), "the refused parcel is quarantined in courier-rejected/")
        assertTrue(b.bagFiles().isEmpty(), "a quarantined parcel leaves the portable bag")
    }

    @Test
    fun `sweep expires an old parcel and deletes its file`() {
        val a = Node("a-sweep")
        val circleId = "courier-swept"
        val aId = a.inHome { loadOrCreateIdentity() }
        val droppedAt = System.currentTimeMillis()

        // --ttl injects the clock: drop seals at t0, sweep runs at t0 + TTL so
        // the SWEEP boundary nowMs >= expiresMs is exact and wall-clock-free.
        assertEquals(
            0,
            a.cli(
                "drop", "--to", aId.fingerprint, "--cert", certB64(aId),
                "--text", "old", "--circle", circleId, "--ttl", droppedAt.toString(),
            ),
        )
        val row = a.readRows().single()
        assertEquals("pending", row.state)
        assertEquals(droppedAt + CourierCarryCore.MAX_PARCEL_TTL_MS, row.expiresMs)
        assertEquals(1, a.bagFiles().size)

        assertEquals(
            0,
            a.cli("sweep", "--ttl", (droppedAt + CourierCarryCore.MAX_PARCEL_TTL_MS).toString()),
        )
        val swept = a.readRows().single()
        assertEquals("expired", swept.state)
        assertEquals(row.id, swept.id)
        assertEquals(row.expiresMs, swept.expiresMs)
        assertTrue(a.bagFiles().isEmpty(), "sweep deletes the expired envelope file")
    }

    @Test
    fun `a duplicate envelope id does not double fold`() {
        val a = Node("a-dup")
        val b = Node("b-dup")
        val circleId = "courier-dup"
        val aId = a.inHome { loadOrCreateIdentity() }
        val bId = b.inHome { loadOrCreateIdentity() }

        assertEquals(
            0,
            a.cli("drop", "--to", bId.fingerprint, "--cert", certB64(bId), "--text", "once", "--circle", circleId),
        )
        val row = a.readRows().single()
        val file = a.bagFiles().single()
        val parcel = CourierCarryCore.decode(Files.readString(file, StandardCharsets.UTF_8))!!.parcels.single()
        val envelope = CourierEnvelope.decode(envelopeOf(parcel.event))!!
        val expectedId = CourierEnvelope.open(envelope, bId, System.currentTimeMillis())!!.single().id

        copyBag(a, b)
        b.writeRows(a.readRows())
        assertEquals(0, b.cli("receive"))
        assertEquals(1, b.circleEvents().size)
        assertEquals("delivered", b.readRows().single().state)

        // A second carrier hands over the very same envelope id. The archived
        // parcel is put back in the bag and received again: CircleCore.dedup
        // keeps one event and the delivered row is unchanged.
        val archived = b.inHome {
            Files.list(harnessHome().resolve(CourierCli.RECEIVED_DIR)).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        }.single()
        Files.copy(archived, b.bag.resolve(archived.fileName), StandardCopyOption.REPLACE_EXISTING)
        assertEquals(0, b.cli("receive"))

        val folded = b.circleEvents()
        assertEquals(1, folded.size, "the duplicate id must not fold twice")
        assertEquals(expectedId, folded.single().id)
        assertTrue(b.readRows().all { it.state == "delivered" })
    }

    @Test
    fun `a certificate that does not match --to writes no parcel`() {
        val a = Node("a-badcert")
        val aId = a.inHome { loadOrCreateIdentity() }
        val stranger = TlsIdentity.generate()

        a.cli(
            "drop", "--to", aId.fingerprint, "--cert", certB64(stranger),
            "--text", "never sealed", "--circle", "courier-badcert",
        )

        assertTrue(a.bagFiles().isEmpty(), "no envelope is written on a certificate/--to mismatch")
        assertTrue(a.readRows().isEmpty(), "no row is added on a refused drop")
    }

    @Test
    fun `an unopened parcel survives production encode decode encode byte for byte`() {
        val a = Node("a-roundtrip")
        val aId = a.inHome { loadOrCreateIdentity() }
        val parcel = makeParcel(
            aId, "courier-roundtrip", aId.fingerprint, aId.certificate, "portable",
            System.currentTimeMillis(),
        )

        val once = CourierCarryCore.encode(CarryState(listOf(parcel)))
        val reloaded = CourierCarryCore.decode(once)!!.parcels.single()
        assertEquals(once, CourierCarryCore.encode(CarryState(listOf(reloaded))))
        assertTrue(CircleCore.verify(reloaded.event))
    }
}
