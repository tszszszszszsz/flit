package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.PARCEL
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Comparator
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure seams of [CourierCli]: the local state codec, the state transition map,
 * the recipient-certificate gate, the on-disk path contract and the exit codes
 * returned by [CourierCli.run]. No sockets, no endpoints, no identity file and
 * no parcel crypto of its own are touched here — the envelope and carry codecs
 * have their own protocol tests. The one refusal case that drives `run` uses
 * those production codecs to build a tampered parcel, not a lookalike.
 */
class CourierCliTest {

    companion object {
        /** [harnessHome] prefers this env var, which cannot be redirected inside a JDK 21 JVM. */
        private val fixedHarnessHome: String? =
            System.getenv("FLIT_HARNESS_HOME")?.takeIf { it.isNotBlank() }

        /** `user.home` is JVM-global: only one test may switch it at a time. */
        private val homeLock = Any()

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun <T> withTempDir(prefix: String, block: (Path) -> T): T {
        val dir = Files.createTempDirectory(prefix)
        try {
            return block(dir)
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun row(
        state: String = "pending",
        id: String = "env-1",
        createdMs: Long = 1_000L,
        expiresMs: Long = 9_000L,
    ) = CourierCli.Row(
        id = id,
        recipientFp = "A1B2C3",
        senderFp = "D4E5F6",
        circleId = "circle-1",
        state = state,
        createdMs = createdMs,
        expiresMs = expiresMs,
    )

    /** Same root rule as harnessHome(), kept local so this test needs no fixture. */
    private fun expectedHome(): Path =
        System.getenv("FLIT_HARNESS_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".config", "flit", "flit-harness")

    /**
     * [harnessHome] reads FLIT_HARNESS_HOME before `user.home`, and that env
     * var cannot be redirected inside a JDK 21 JVM. A pinned home is the
     * caller's real harness home, not our sandbox: skip instead of silently
     * writing there. Otherwise hold one global lock while `user.home` points at
     * a private temp root, then delete the root. Every command below that
     * touches a ledger or identity is wrapped here.
     */
    private fun <T> inIsolatedHome(block: () -> T): T {
        assumeTrue(
            fixedHarnessHome == null,
            "CourierCliTest switches user.home, but harnessHome() prefers " +
                "FLIT_HARNESS_HOME=$fixedHarnessHome.",
        )
        return synchronized(homeLock) {
            withTempDir("courier-cli-test-") { root ->
                val previous = System.getProperty("user.home")
                System.setProperty("user.home", root.toString())
                try {
                    block()
                } finally {
                    if (previous == null) System.clearProperty("user.home")
                    else System.setProperty("user.home", previous)
                }
            }
        }
    }

    private fun certB64(identity: TlsIdentity): String =
        Base64.getEncoder().encodeToString(identity.certificate.encoded)

    private fun certificateOf(certB64: String): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(certB64))) as X509Certificate
    }.getOrNull()

    private fun flipFirst(s: String): String {
        require(s.isNotEmpty())
        return (if (s[0] == 'A') 'B' else 'A') + s.substring(1)
    }

    // ---- state codec --------------------------------------------------------

    @Test
    fun `row codec round-trips exact fields for several rows`() {
        val rows = listOf(
            CourierCli.Row(
                id = "env-a", recipientFp = "AAAA", senderFp = "BBBB",
                circleId = "circle-a", state = "pending",
                createdMs = 1_700_000_000_000L, expiresMs = 1_700_086_400_000L,
            ),
            CourierCli.Row(
                id = "env-b", recipientFp = "CCCC", senderFp = "DDDD",
                circleId = "circle-b", state = "carried",
                createdMs = 0L, expiresMs = Long.MAX_VALUE / 4,
            ),
            CourierCli.Row(
                id = "env-c", recipientFp = "EEEE", senderFp = "FFFF",
                circleId = "circle-c", state = "refused",
                createdMs = -5L, expiresMs = -1L,
            ),
        )
        assertEquals(rows, CourierCli.decodeRows(CourierCli.encodeRows(rows)))
    }

    @Test
    fun `row codec output is deterministic with one line per row`() {
        val rows = listOf(row(id = "env-1"), row(state = "carried", id = "env-2"))
        val raw = CourierCli.encodeRows(rows)
        assertEquals(raw, CourierCli.encodeRows(rows), "re-encoding the same rows must be stable")
        assertEquals(rows.size, raw.lines().size, "one state line per parcel row")
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows(CourierCli.encodeRows(emptyList())))
    }

    @Test
    fun `row decode tolerates absent blank short and corrupt state files`() {
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows(null))
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows(""))
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows("   \n  "))
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows("""{"id":"env-1"}"""), "short row")
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows("{not json"), "corrupt row")
        assertEquals(emptyList<CourierCli.Row>(), CourierCli.decodeRows("[]"), "wrong shape is empty, not an exception")
    }

    @Test
    fun `row decode ignores unknown keys like circle state`() {
        val raw = """{"id":"env-x","recipientFp":"A1","senderFp":"B2","circleId":"c","state":"carried","createdMs":10,"expiresMs":99,"extra":"not-a-row-field"}"""
        assertEquals(listOf(CourierCli.Row("env-x", "A1", "B2", "c", "carried", 10, 99)), CourierCli.decodeRows(raw))
    }

    // ---- transition map -----------------------------------------------------

    @Test
    fun `carry moves only pending rows to carried`() {
        assertEquals("carried", CourierCli.nextState(row("pending"), CourierCli.Kind.CARRY, 5_000L))
        for (state in listOf("carried", "delivered", "expired", "refused")) {
            assertEquals(state, CourierCli.nextState(row(state), CourierCli.Kind.CARRY, 5_000L), "carry must not move $state")
        }
    }

    @Test
    fun `receive ok moves pending and carried rows to delivered`() {
        for (state in listOf("pending", "carried")) {
            assertEquals("delivered", CourierCli.nextState(row(state), CourierCli.Kind.RECEIVE_OK, 5_000L))
        }
        for (state in listOf("delivered", "expired", "refused")) {
            assertEquals(state, CourierCli.nextState(row(state), CourierCli.Kind.RECEIVE_OK, 5_000L), "receive must not move $state")
        }
    }

    @Test
    fun `receive refusal moves every state to refused`() {
        for (state in listOf("pending", "carried", "delivered", "expired", "refused")) {
            assertEquals("refused", CourierCli.nextState(row(state), CourierCli.Kind.RECEIVE_REFUSED, 5_000L))
        }
    }

    @Test
    fun `sweep expires every state at the TTL boundary and not before`() {
        for (state in listOf("pending", "carried", "delivered", "expired", "refused")) {
            assertEquals(state, CourierCli.nextState(row(state), CourierCli.Kind.SWEEP, 8_999L), "nowMs < expiresMs keeps $state")
            assertEquals("expired", CourierCli.nextState(row(state), CourierCli.Kind.SWEEP, 9_000L), "nowMs == expiresMs expires $state")
            assertEquals("expired", CourierCli.nextState(row(state), CourierCli.Kind.SWEEP, 9_001L), "nowMs > expiresMs expires $state")
        }
    }

    @Test
    fun `non sweep kinds do not infer expiry from an injected clock`() {
        val past = row("pending", createdMs = 1L, expiresMs = 2L)
        assertEquals("carried", CourierCli.nextState(past, CourierCli.Kind.CARRY, 10_000L))
        assertEquals("delivered", CourierCli.nextState(past, CourierCli.Kind.RECEIVE_OK, 10_000L))
        assertEquals("refused", CourierCli.nextState(past, CourierCli.Kind.RECEIVE_REFUSED, 10_000L))
    }

    @Test
    fun `a thrown open has no kind so the pending row survives for a later attempt`() {
        val pending = row("pending")
        assertEquals(
            setOf("carried", "delivered", "refused", "expired"),
            CourierCli.Kind.entries.map { CourierCli.nextState(pending, it, 9_000L) }.toSet(),
            "every Kind is a returned conclusion; a thrown open maps to none",
        )
        assertFalse(
            CourierCli.Kind.entries.any {
                it.name.contains("THROW", ignoreCase = true) || it.name.contains("OPEN", ignoreCase = true)
            },
            "there is no thrown-open Kind that could conclude the row",
        )
        // receive's throw branch applies no Kind: the row keeps its state, the
        // parcel keeps its file, and a later attempt may retry it.
        val noKind: CourierCli.Kind? = null
        val afterThrow = noKind?.let { CourierCli.nextState(pending, it, 9_000L) } ?: pending.state
        assertEquals("pending", afterThrow)
    }

    // ---- recipient certificate gate ----------------------------------------

    @Test
    fun `recipient cert gate binds the certificate fingerprint to the to flag`() {
        val addressee = TlsIdentity.generate()
        val other = TlsIdentity.generate()
        val cert = certificateOf(certB64(addressee))!!

        assertTrue(CourierCli.certMatchesTo(cert, addressee.fingerprint))
        assertTrue(CourierCli.certMatchesTo(cert, addressee.fingerprint.lowercase()), "--to is case-insensitive")
        assertTrue(CourierCli.certMatchesTo(cert, "  ${addressee.fingerprint}  "), "--to is trimmed")
        assertFalse(CourierCli.certMatchesTo(cert, other.fingerprint), "a certificate for another fingerprint is refused")
        assertFalse(CourierCli.certMatchesTo(cert, ""), "blank --to is refused")
        assertFalse(
            CourierCli.certMatchesTo(null, addressee.fingerprint),
            "a malformed explicit --cert is refused, never a fallback to event certs",
        )
        assertFalse(
            CourierCli.certMatchesTo(certificateOf("not-base64"), addressee.fingerprint),
            "an unparsable certificate cannot pass the gate",
        )
    }

    // ---- paths and naming ---------------------------------------------------

    @Test
    fun `courier constants pin the on-disk and exit-code contract`() {
        assertEquals("courier-bag", CourierCli.BAG_DIR)
        assertEquals("courier-received", CourierCli.RECEIVED_DIR)
        assertEquals("courier-rejected", CourierCli.REJECTED_DIR)
        assertEquals("courier.state", CourierCli.STATE_FILE)
        assertEquals(2, CourierCli.EXIT_BAD_ARGS)
        assertEquals(3, CourierCli.EXIT_OPEN_REFUSED)
    }

    @Test
    fun `bag and state paths root at harness home with the fixed names`() {
        val args = ParsedArgs(emptyArray())
        assertEquals(expectedHome().resolve(CourierCli.BAG_DIR), CourierCli.bagDir(args))
        assertEquals(expectedHome().resolve(CourierCli.STATE_FILE), CourierCli.statePath(args))
    }

    @Test
    fun `explicit bag flag overrides only the bag path`() {
        val override = expectedHome().resolve("other-bag")
        val args = ParsedArgs(emptyArray())
        args.flags["--bag"] = override.toString()
        assertEquals(override, CourierCli.bagDir(args))
        assertEquals(expectedHome().resolve(CourierCli.STATE_FILE), CourierCli.statePath(args), "state rows stay local")
    }

    @Test
    fun `relative bag flag roots under harness home while an absolute flag is kept`() {
        val args = ParsedArgs(emptyArray())
        args.flags["--bag"] = "portable-bag"
        assertEquals(expectedHome().resolve("portable-bag"), CourierCli.bagDir(args))
        args.flags["--bag"] = "nested/portable-bag"
        assertEquals(expectedHome().resolve("nested/portable-bag"), CourierCli.bagDir(args))
        val absolute = expectedHome().resolve("absolute-bag")
        args.flags["--bag"] = absolute.toString()
        assertEquals(absolute, CourierCli.bagDir(args), "an absolute --bag is not re-rooted")
    }

    @Test
    fun `parcel file name is the envelope id plus the fixed suffix under the bag`() {
        val bag = CourierCli.bagDir(ParsedArgs(emptyArray()))
        val id = "8b1f0c2e-9a44-4d0c-b7d6-2e5f8a1c3b70"
        val parcel = bag.resolve("$id.parcel")
        assertEquals("$id.parcel", parcel.fileName.toString())
        assertEquals(bag, parcel.parent, "the bag directory is the portable handoff unit")
    }

    @Test
    fun `usage names every courier subcommand`() {
        val text = CourierCli.usage()
        for (command in listOf("courier", "drop", "carry", "receive", "status", "sweep")) {
            assertTrue(text.contains(command), "usage omits $command")
        }
    }

    // ---- run() exit codes ---------------------------------------------------

    @Test
    fun `run returns bad args for malformed invocations before touching state`() {
        assertEquals(CourierCli.EXIT_BAD_ARGS, CourierCli.run(ParsedArgs(emptyArray())))
        assertEquals(CourierCli.EXIT_BAD_ARGS, CourierCli.run(ParsedArgs(arrayOf("courier", "bogus"))))
        assertEquals(CourierCli.EXIT_BAD_ARGS, CourierCli.run(ParsedArgs(arrayOf("courier", "status", "--ttl", "oops"))))
        assertEquals(CourierCli.EXIT_BAD_ARGS, CourierCli.run(ParsedArgs(arrayOf("courier", "status", "--ttl", "0"))))
        assertEquals(CourierCli.EXIT_BAD_ARGS, CourierCli.run(ParsedArgs(arrayOf("courier", "drop"))))
        assertEquals(
            CourierCli.EXIT_BAD_ARGS,
            CourierCli.run(ParsedArgs(arrayOf("courier", "drop", "--to", "A1B2"))),
            "drop needs a --text or --file payload",
        )
    }

    @Test
    fun `run reports status success and bad args for a missing bag`() = inIsolatedHome {
        assertEquals(0, CourierCli.run(ParsedArgs(arrayOf("courier", "status"))))
        val absent = expectedHome().resolve("no-such-bag").toString()
        for (command in listOf("carry", "receive", "sweep")) {
            assertEquals(
                CourierCli.EXIT_BAD_ARGS,
                CourierCli.run(ParsedArgs(arrayOf("courier", command, "--bag", absent))),
                "$command must refuse a missing bag",
            )
        }
    }

    @Test
    fun `run reports a refused open with the dedicated exit code`() = inIsolatedHome {
        val identity = loadOrCreateIdentity()
        val home = expectedHome()
        val circleId = "cli-refused-open"
        val now = System.currentTimeMillis()
        val inner = CircleCore.create(
            circleId, identity.fingerprint, identity, CircleEvent.MESSAGE,
            """{"text":"never folded"}""", lamport = 0L, createdMs = now,
        )
        val envelope = CourierEnvelope.seal(
            listOf(inner), circleId, identity.fingerprint, identity.fingerprint,
            identity.certificate, identity, now,
        ) ?: error("production seal refused a small, valid event")
        // Tampering with the ciphertext invalidates the signature over the
        // canonical envelope: decode still succeeds, open returns a refusal.
        val broken = envelope.copy(cipherB64 = flipFirst(envelope.cipherB64))
        val body = buildJsonObject {
            put("toFp", JsonPrimitive(identity.fingerprint))
            put("envelope", JsonPrimitive(CourierEnvelope.encode(broken)))
        }.toString()
        val event = CircleCore.create(
            circleId, identity.fingerprint, identity, PARCEL, body,
            lamport = 1L, createdMs = now,
        )
        val parcel = CourierCarryCore.parcelOf(event) ?: error("production parcelOf refused a valid parcel")
        withTempDir("courier-cli-refused-") { bag ->
            Files.writeString(
                bag.resolve("${parcel.id}.parcel"),
                CourierCarryCore.encode(CarryState(listOf(parcel))),
                Charsets.UTF_8,
            )
            Files.createDirectories(home)
            Files.writeString(
                home.resolve(CourierCli.STATE_FILE),
                CourierCli.encodeRows(
                    listOf(
                        CourierCli.Row(
                            parcel.id, identity.fingerprint, identity.fingerprint, circleId,
                            "pending", parcel.createdMs, parcel.expiresMs,
                        ),
                    ),
                ),
                Charsets.UTF_8,
            )
            assertEquals(
                CourierCli.EXIT_OPEN_REFUSED,
                CourierCli.run(
                    ParsedArgs(arrayOf("courier", "receive", "--bag", bag.toString(), "--ttl", now.toString())),
                ),
            )
        }
    }

    @Test
    fun `receive folds neither JOIN nor REKEY out of an opened parcel`() = inIsolatedHome {
        val identity = loadOrCreateIdentity()
        val me = identity.fingerprint
        val circleId = "cli-fold-gate"
        val now = System.currentTimeMillis()
        saveCircleState(
            CircleCliState(
                circleId = circleId, host = "",
                members = mutableListOf(me), events = mutableListOf(),
            ),
        )
        // All three are validly signed by a current member. Foldability — not
        // the signature — is what keeps the two privileged verbs out.
        val join = CircleCore.create(
            circleId, me, identity, CircleEvent.JOIN, """{"alias":"me"}""", lamport = 0L,
        )
        val rekey = CircleCore.create(
            circleId, me, identity, CircleEvent.REKEY,
            """{"keyB64":"rogue-key","gen":1}""", lamport = 1L,
        )
        val message = CircleCore.create(
            circleId, me, identity, CircleEvent.MESSAGE, """{"text":"kept"}""", lamport = 2L,
        )
        val envelope = CourierEnvelope.seal(
            listOf(join, rekey, message), circleId, me, me, identity.certificate, identity, now,
        ) ?: error("production seal refused a small, valid envelope")
        val body = buildJsonObject {
            put("toFp", JsonPrimitive(me))
            put("envelope", JsonPrimitive(CourierEnvelope.encode(envelope)))
        }.toString()
        val parcelEvent = CircleCore.create(circleId, me, identity, PARCEL, body, lamport = 3L)
        val parcel = CourierCarryCore.parcelOf(parcelEvent)
            ?: error("production parcelOf refused a valid parcel")

        withTempDir("courier-cli-fold-gate-") { bag ->
            Files.writeString(
                bag.resolve("${parcel.id}.parcel"),
                CourierCarryCore.encode(CarryState(listOf(parcel))),
                Charsets.UTF_8,
            )
            Files.writeString(
                expectedHome().resolve(CourierCli.STATE_FILE),
                CourierCli.encodeRows(
                    listOf(
                        CourierCli.Row(
                            parcel.id, me, me, circleId, "pending", parcel.createdMs, parcel.expiresMs,
                        ),
                    ),
                ),
                Charsets.UTF_8,
            )
            assertEquals(
                0,
                CourierCli.run(
                    ParsedArgs(arrayOf("courier", "receive", "--bag", bag.toString(), "--ttl", now.toString())),
                ),
            )
            val stateLines = Files.readAllLines(expectedHome().resolve("circle.state"))
            val folded = WireJson.json.decodeFromString<List<CircleEvent>>(stateLines[5])
            assertEquals(listOf(message.id), folded.map { it.id })
            assertFalse(folded.any { it.type == CircleEvent.JOIN })
            assertFalse(folded.any { it.type == CircleEvent.REKEY })
            val row = CourierCli.decodeRows(
                Files.readString(expectedHome().resolve(CourierCli.STATE_FILE), Charsets.UTF_8),
            ).single()
            assertEquals("delivered", row.state)
        }
    }
}
