package app.flit.share.harness

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Comparator
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure seams of the parcel CLI: the usage/flag contract, the recipient-cert
 * gate, the numbered frame file names and the exit codes returned by
 * [ParcelCli.run]. No socket, no route and no device claim is touched here.
 *
 * The frame grammar, CRC, digest and recipient checks belong to `ParcelCodec`
 * and have their own protocol tests; the two-home encode/decode fold belongs
 * to `ParcelCliHarnessTest`. The one case here that renders frames uses the
 * production codec to make a parcel addressed to a *different* identity so
 * the decode side has to refuse it — it never exercises a second crypto path.
 */
class ParcelCliTest {

    companion object {
        /**
         * [harnessHome] reads FLIT_HARNESS_HOME before `user.home`. With the
         * variable pinned, redirecting `user.home` cannot isolate the harness
         * home, so the affected cases skip instead of silently writing into the
         * caller's real harness home.
         */
        private val fixedHarnessHome: String? =
            System.getenv("FLIT_HARNESS_HOME")?.takeIf { it.isNotBlank() }

        /** `user.home` is JVM-global: only one test may switch it at a time. */
        private val homeLock = Any()

        /** Best-effort recursive removal of a private temp root after a test. */
        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder<Path>()).forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    // ---- small local helpers ------------------------------------------------

    /**
     * [harnessHome] reads FLIT_HARNESS_HOME before `user.home`, and that env
     * var cannot be redirected inside a JDK 21 JVM. When the runner pins one,
     * these cases skip rather than silently use the real harness home;
     * otherwise `user.home` points at a private temp root for the block. The
     * global lock serialises the JVM-wide property mutation, and the temp root
     * is removed in the `finally` so no identity or frame fixture leaks.
     */
    private fun <T> inIsolatedHome(block: () -> T): T {
        assumeTrue(
            fixedHarnessHome == null,
            "ParcelCliTest switches user.home for isolation, but " +
                "harnessHome() prefers FLIT_HARNESS_HOME=$fixedHarnessHome.",
        )
        return synchronized(homeLock) {
            val previous = System.getProperty("user.home")
            val root = Files.createTempDirectory("parcel-cli-test-")
            System.setProperty("user.home", root.toString())
            try {
                block()
            } finally {
                if (previous == null) System.clearProperty("user.home")
                else System.setProperty("user.home", previous)
                deleteTree(root)
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** `--cert PATH` carries the Base64 DER of one X.509 certificate. */
    private fun writeCert(path: Path, identity: TlsIdentity): Path {
        Files.writeString(path, Base64.getEncoder().encodeToString(identity.certificate.encoded), Charsets.UTF_8)
        return path
    }

    private fun regularFiles(dir: Path): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.list(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.sorted().toList() }

    private fun frameNames(dir: Path): List<String> = regularFiles(dir).map { it.fileName.toString() }

    private fun pngCount(dir: Path): Int = frameNames(dir).count { it.endsWith(".png") }

    /** Same image seam the CLI uses: 768x768 PNG, EC M, margin 2, UTF-8. */
    private fun writeFramePng(path: Path, frame: String) {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val size = 768
        val matrix = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        ImageIO.write(image, "PNG", path.toFile())
    }

    // ---- usage and flag parsing ---------------------------------------------

    @Test
    fun `usage names both commands and every required flag`() {
        val text = ParcelCli.usage()
        for (token in listOf("parcel", "encode", "decode", "--dir", "--to", "--cert", "--out")) {
            assertTrue(text.contains(token), "usage omits $token")
        }
    }

    @Test
    fun `parsed args keep the positional commands and value flags intact`() {
        assertTrue(
            ParsedArgs.VALUE_FLAGS.containsAll(listOf("--dir", "--to", "--cert", "--out")),
            "--dir, --to, --cert and --out must all consume a value",
        )

        val encode = ParsedArgs(
            arrayOf("parcel", "encode", "payload.bin", "--dir", "frames", "--to", "A1B2C3", "--cert", "cert.b64"),
        )
        assertEquals(listOf("parcel", "encode", "payload.bin"), encode.positional)
        assertEquals("frames", encode.opt("--dir"))
        assertEquals("A1B2C3", encode.opt("--to"))
        assertEquals("cert.b64", encode.opt("--cert"))
        assertEquals(null, encode.opt("--out"), "encode has no --out value")

        val decode = ParsedArgs(arrayOf("parcel", "decode", "--dir", "frames", "--out", "landed.bin"))
        assertEquals(listOf("parcel", "decode"), decode.positional)
        assertEquals("frames", decode.opt("--dir"))
        assertEquals("landed.bin", decode.opt("--out"))
        assertEquals(null, decode.opt("--cert"), "decode has no --cert value")
        assertFalse(decode.positional.contains("landed.bin"), "a value flag must not leak into positional")

        val bare = ParsedArgs(arrayOf("parcel", "decode", "--dir"))
        assertEquals("", bare.flags["--dir"])
        assertEquals(null, bare.opt("--dir"), "an empty flag value is treated as absent")
    }

    @Test
    fun `repeated value flags keep every occurrence for poll options`() {
        val args = ParsedArgs(
            arrayOf(
                "circle", "poll", "--text", "Ship?",
                "--option", "yes", "--option", "no", "--option", "maybe",
            ),
        )
        assertEquals(listOf("yes", "no", "maybe"), args.opts("--option"))
        assertEquals("maybe", args.opt("--option"), "the last value still wins for single-value callers")
        assertTrue(args.has("--option"))
        assertTrue(args.opts("--missing").isEmpty())

        // The documented comma form travels through the same flatMap path.
        val comma = ParsedArgs(arrayOf("circle", "poll", "--option", "yes,no,maybe"))
        assertEquals(listOf("yes,no,maybe"), comma.opts("--option"))
        assertEquals(listOf("yes", "no", "maybe"), comma.opts("--option").flatMap { it.split(',') })
    }

    @Test
    fun `encode refuses a payload above the codec payload cap before rendering`() = inIsolatedHome {
        val dir = Files.createTempDirectory("parcel-cap-")
        // One byte over the codec's real payload ceiling; the frame-layer
        // defensive ceiling is much larger and must not be used here.
        val payload = ByteArray(ParcelCodec.MAX_AIR_GAP_PAYLOAD_BYTES + 1).also { SecureRandom().nextBytes(it) }
        val file = Files.write(dir.resolve("too-big.bin"), payload)
        val frames = Files.createDirectory(dir.resolve("frames"))
        val recipient = TlsIdentity.generate()
        val cert = writeCert(dir.resolve("recipient.b64"), recipient)

        val code = ParcelCli.run(
            ParsedArgs(
                arrayOf(
                    "parcel", "encode", file.toString(), "--dir", frames.toString(),
                    "--to", recipient.fingerprint, "--cert", cert.toString(),
                ),
            ),
        )
        assertEquals(2, code, "an oversized payload is bad args, not a late seal refusal")
        assertEquals(0, pngCount(frames), "nothing is rendered for a refused payload")
    }

    // ---- exit codes ---------------------------------------------------------

    @Test
    fun `run returns bad args for absent or unknown commands`() {
        assertEquals(2, ParcelCli.run(ParsedArgs(emptyArray())))
        assertEquals(2, ParcelCli.run(ParsedArgs(arrayOf("parcel"))))
        assertEquals(2, ParcelCli.run(ParsedArgs(arrayOf("parcel", "bogus"))))
        assertEquals(2, ParcelCli.run(ParsedArgs(arrayOf("parcel", "encode"))))
        assertEquals(2, ParcelCli.run(ParsedArgs(arrayOf("parcel", "decode"))))
    }

    @Test
    fun `run returns bad args when a required flag is missing`() {
        val dir = Files.createTempDirectory("parcel-missing-flags-")
        val payload = Files.write(dir.resolve("payload.bin"), byteArrayOf(1, 2, 3, 4))
        val frames = Files.createDirectory(dir.resolve("frames"))
        val file = payload.toString()

        assertEquals(
            2,
            ParcelCli.run(ParsedArgs(arrayOf("parcel", "encode", file, "--dir", frames.toString()))),
            "encode needs --to and --cert",
        )
        assertEquals(
            2,
            ParcelCli.run(
                ParsedArgs(arrayOf("parcel", "encode", file, "--dir", frames.toString(), "--to", "ABCD")),
            ),
            "encode needs --cert",
        )
        assertEquals(
            2,
            ParcelCli.run(ParsedArgs(arrayOf("parcel", "decode", "--dir", frames.toString()))),
            "decode needs --out",
        )
        assertEquals(
            2,
            ParcelCli.run(ParsedArgs(arrayOf("parcel", "decode", "--out", dir.resolve("out.bin").toString()))),
            "decode needs --dir",
        )
        assertEquals(0, pngCount(frames), "a bad invocation never renders a frame")
    }

    @Test
    fun `run returns bad args for a missing cert file or a missing frame directory`() {
        val dir = Files.createTempDirectory("parcel-missing-paths-")
        val payload = Files.write(dir.resolve("payload.bin"), byteArrayOf(9, 8, 7))
        val frames = Files.createDirectory(dir.resolve("frames"))
        val recipient = TlsIdentity.generate()

        assertEquals(
            2,
            ParcelCli.run(
                ParsedArgs(
                    arrayOf(
                        "parcel", "encode", payload.toString(), "--dir", frames.toString(),
                        "--to", recipient.fingerprint, "--cert", dir.resolve("no-cert.b64").toString(),
                    ),
                ),
            ),
            "a missing --cert is bad args, not a silent fallback",
        )

        assertEquals(
            2,
            ParcelCli.run(
                ParsedArgs(
                    arrayOf(
                        "parcel", "decode", "--dir", dir.resolve("no-frames").toString(),
                        "--out", dir.resolve("out.bin").toString(),
                    ),
                ),
            ),
            "a missing --dir is bad args",
        )
        assertEquals(0, pngCount(frames))
        assertFalse(Files.exists(dir.resolve("out.bin")))
    }

    // ---- recipient certificate gate ----------------------------------------

    @Test
    fun `cert gate refuses a mismatched or malformed certificate before rendering`() {
        val dir = Files.createTempDirectory("parcel-cert-gate-")
        val payload = Files.write(dir.resolve("payload.bin"), byteArrayOf(5, 6, 7, 8))
        val frames = Files.createDirectory(dir.resolve("frames"))
        val addressee = TlsIdentity.generate()
        val other = TlsIdentity.generate()
        val cert = writeCert(dir.resolve("recipient.b64"), addressee)

        fun encode(to: String): Int = ParcelCli.run(
            ParsedArgs(
                arrayOf(
                    "parcel", "encode", payload.toString(), "--dir", frames.toString(),
                    "--to", to, "--cert", cert.toString(),
                ),
            ),
        )

        assertEquals(2, encode(other.fingerprint), "--to must be bound to the certificate in hand")
        assertEquals(
            2,
            encode("  ${other.fingerprint.lowercase()}  "),
            "--to is normalised, but normalising cannot make a different cert match",
        )
        assertEquals(0, pngCount(frames), "the refusal happens before the first PNG is written")

        Files.writeString(cert, "not-base64", Charsets.UTF_8)
        assertEquals(2, encode(addressee.fingerprint), "an unparsable certificate cannot pass the gate")
        assertEquals(0, pngCount(frames), "no frame survives a refused encode")
    }

    // ---- frame naming and the encode result --------------------------------

    @Test
    fun `encode writes exactly the zero padded frame names and nothing else`() = inIsolatedHome {
        val dir = Files.createTempDirectory("parcel-naming-")
        val payload = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val file = Files.write(dir.resolve("payload.bin"), payload)
        val frames = Files.createDirectory(dir.resolve("frames"))
        val recipient = TlsIdentity.generate()
        val cert = writeCert(dir.resolve("recipient.b64"), recipient)

        val code = ParcelCli.run(
            ParsedArgs(
                arrayOf(
                    "parcel", "encode", file.toString(), "--dir", frames.toString(),
                    "--to", recipient.fingerprint, "--cert", cert.toString(),
                ),
            ),
        )
        assertEquals(0, code, "a valid self-contained encode succeeds")

        val names = frameNames(frames)
        assertTrue(names.isNotEmpty(), "a small payload must still produce at least one frame")
        assertEquals(
            names.indices.map { "frame-%04d.png".format(it + 1) },
            names,
            "lexicographic order equals codec frame order, one frame per numbered PNG",
        )
        assertFalse(names.any { it == "manifest" || it == "identity.p12" }, "the frame dir is the whole checkpoint")
    }

    @Test
    fun `parcelEncode reports the frame dir count frame count and payload digest`() = inIsolatedHome {
        val dir = Files.createTempDirectory("parcel-encode-result-")
        val payload = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val file = Files.write(dir.resolve("payload.bin"), payload)
        val frames = Files.createDirectory(dir.resolve("frames"))
        val recipient = TlsIdentity.generate()
        val cert = writeCert(dir.resolve("recipient.b64"), recipient)

        val result = ParcelCli.parcelEncode(
            ParsedArgs(
                arrayOf(
                    "parcel", "encode", file.toString(), "--dir", frames.toString(),
                    "--to", recipient.fingerprint, "--cert", cert.toString(),
                ),
            ),
        )
        assertEquals(frames, result.dir)
        assertTrue(result.frames > 0, "a valid encode returns at least one frame")
        assertEquals(result.frames, frameNames(frames).size, "one numbered PNG per returned frame")
        assertEquals(sha256Hex(payload), result.sha256, "the printed hash is over the file bytes")
        assertTrue(result.sealedChars > 0, "the sealed envelope has a positive encoded length")
    }

    @Test
    fun `run returns verification failure for a parcel addressed elsewhere`() = inIsolatedHome {
        val dir = Files.createTempDirectory("parcel-verify-")
        val frames = Files.createDirectory(dir.resolve("frames"))
        val out = dir.resolve("out.bin")
        val signer = loadOrCreateIdentity()
        val other = TlsIdentity.generate()
        val payload = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val encoded = ParcelCodec.encodeParcel(
            payload, "parcel-cli-verify", other.fingerprint, other.certificate, signer.fingerprint, signer,
        )
        assertTrue(encoded.isNotEmpty(), "the production codec must seal a small valid parcel")
        encoded.forEachIndexed { index, frame ->
            writeFramePng(frames.resolve("frame-%04d.png".format(index + 1)), frame)
        }

        assertEquals(
            3,
            ParcelCli.run(ParsedArgs(arrayOf("parcel", "decode", "--dir", frames.toString(), "--out", out.toString()))),
            "a parcel for another fingerprint is a verification failure, not a success",
        )
        assertFalse(Files.exists(out), "no unverified bytes are written")
    }
}
