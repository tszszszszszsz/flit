package app.flit.share.harness

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Comparator
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parcel CLI's two-node walk, in one JVM but over the production layers:
 * [ParcelCli] renders and reads real PNGs with zxing, [ParcelCodec] seals and
 * opens through `CourierEnvelope`, and each side loads its own long-lived
 * `TlsIdentity`. There is no lookalike frame string, cipher or image step here.
 *
 * `FLIT_HARNESS_HOME` is read by [harnessHome] before `user.home`, and cannot be
 * changed inside a JDK 21 test JVM without an `--add-opens` flag. Each [Node]
 * therefore points `user.home` at its own temp root; `loadOrCreateIdentity()`
 * resolves to `<root>/.config/flit/flit-harness/identity.p12` at call time. Every
 * CLI call is wrapped in [Node.inHome], so the sender and recipient never share
 * an identity. The frame directory is a plain temp path handed between nodes.
 *
 * Because `user.home` is JVM-global, all tests run through [isolatedHome]: the
 * walk is skipped when the environment pins one harness home (rather than
 * silently sharing the caller's real home), a single lock is held while
 * `user.home` is switched, and every node's temp root is deleted afterwards.
 */
class ParcelCliHarnessTest {

    companion object {
        /**
         * [harnessHome] reads this env var before `user.home`. With it set the
         * per-node switch cannot produce two homes, so the walk is skipped
         * instead of sharing (and corrupting) a real harness home.
         */
        private val fixedHarnessHome: String? =
            System.getenv("FLIT_HARNESS_HOME")?.takeIf { it.isNotBlank() }

        /** `user.home` is JVM-global: only one test may switch it at a time. */
        private val homeLock = Any()

        /** Temp roots opened by [Node] while the lock is held, cleaned up after the block. */
        private val tempRoots = mutableListOf<Path>()

        /** Best-effort recursive removal of one node's private temp root. */
        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder<Path>()).forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }

        private fun <T> isolatedHome(block: () -> T): T {
            assumeTrue(
                fixedHarnessHome == null,
                "ParcelCliHarnessTest switches user.home per node, but " +
                    "harnessHome() prefers FLIT_HARNESS_HOME=$fixedHarnessHome.",
            )
            return synchronized(homeLock) {
                tempRoots.clear()
                try {
                    block()
                } finally {
                    tempRoots.forEach(::deleteTree)
                    tempRoots.clear()
                }
            }
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private class Node(tag: String) {
            val root: Path = Files.createTempDirectory("parcel-$tag-").also { tempRoots.add(it) }
            val home: Path = root.resolve("home").also { Files.createDirectories(it) }

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

            fun identity(): TlsIdentity = inHome { loadOrCreateIdentity() }
        }
    }

    private fun framePngs(frames: Path): List<Path> = Files.list(frames).use { stream ->
        stream.filter { Files.isRegularFile(it) }.iterator().asSequence()
            .sortedBy { it.fileName.toString() }.toList()
    }

    /** The recipient cert travels out of band: a file holding unpadded-safe Base64 DER. */
    private fun encodeParcelFor(sender: Node, recipient: Node, payload: ByteArray): Path {
        val recipientId = recipient.identity()
        val cert = sender.root.resolve("recipient.b64")
        Files.writeString(
            cert,
            Base64.getEncoder().encodeToString(recipientId.certificate.encoded),
            StandardCharsets.UTF_8,
        )
        val input = sender.root.resolve("payload.bin")
        Files.write(input, payload)
        val frames = sender.root.resolve("frames").also { Files.createDirectories(it) }
        val args = ParsedArgs(
            arrayOf(
                "parcel", "encode", input.toString(),
                "--dir", frames.toString(),
                "--to", recipientId.fingerprint,
                "--cert", cert.toString(),
            ),
        )
        val result = sender.inHome { ParcelCli.parcelEncode(args) }
        assertEquals(frames.toString(), result.dir.toString(), "encode must fill the --dir it was given")
        assertEquals(sha256Hex(payload), result.sha256.lowercase(), "encode hashes the file bytes")
        assertTrue(result.frames > 0, "a seeded multi-frame payload must produce frames")
        assertTrue(result.sealedChars > 0, "encode reports the sealed envelope size")
        return frames
    }

    /**
     * Read one rendered PNG back to the opaque frame string, like a scanner.
     * A dense frame may not decode at full resolution, so sample at scales
     * 1..4 exactly as production `ParcelCli.readFrame` does and fail only after every
     * attempt has missed.
     */
    private fun frameString(png: Path): String {
        val img = ImageIO.read(png.toFile()) ?: error("$png is not an image")
        for (d in 1..6) {
            val w = img.width / d
            val h = img.height / d
            if (w < 1 || h < 1) continue
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.drawImage(img, 0, 0, w, h, null)
            g.dispose()
            val px = IntArray(w * h)
            small.getRGB(0, 0, w, h, px, 0, w)
            val text = runCatching {
                MultiFormatReader()
                    .decode(
                        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))),
                        mapOf<DecodeHintType, Any>(
                            DecodeHintType.TRY_HARDER to true,
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        ),
                    ).text
            }.getOrNull()
            if (text != null) return text
        }
        error("$png has no readable frame at scales 1..6")
    }

    /** Rewrite a PNG so it carries a frame whose own CRC no longer matches. */
    private fun rewriteWithBrokenCrc(png: Path) {
        val parts = frameString(png).split('.').toMutableList()
        require(parts.size == 12) { "not a parcel frame: $png" }
        val crc = parts[11]
        require(crc.length == 8) { "bad CRC field: $crc" }
        parts[11] = (if (crc[0] == '0') '1' else '0') + crc.substring(1)
        val broken = parts.joinToString(".")

        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bits = QRCodeWriter().encode(broken, BarcodeFormat.QR_CODE, 768, 768, hints)
        val img = BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 768) {
            for (x in 0 until 768) {
                img.setRGB(x, y, if (bits[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        ImageIO.write(img, "PNG", png.toFile())
        assertEquals(broken, frameString(png), "the tampered frame must be the one on disk")
    }

    @Test
    fun `encode with recipient cert then decode with recipient identity lands exact bytes`() = isolatedHome {
        val sender = Node("a-encode")
        val recipient = Node("b-decode")
        val senderId = sender.identity()
        val recipientId = recipient.identity()
        // 1024 bytes stays below the codec's sealed-envelope Base64 cap; a
        // larger fixture can overflow CourierEnvelope.MAX_ENVELOPE_CHARS.
        val payload = ByteArray(1024).also { Random(42).nextBytes(it) }
        val frames = encodeParcelFor(sender, recipient, payload)

        val pngs = framePngs(frames)
        assertEquals(pngs.size, pngs.map { it.fileName.toString() }.distinct().size)
        pngs.forEachIndexed { i, png ->
            assertEquals("frame-%04d.png".format(i + 1), png.fileName.toString())
            val frame = frameString(png)
            assertEquals(
                i + 1,
                ParcelCodec.parse(frame)?.seq,
                "every rendered PNG must re-decode to a parseable production frame",
            )
        }

        val out = recipient.root.resolve("landed.bin")
        val decodeArgs = ParsedArgs(
            arrayOf("parcel", "decode", "--dir", frames.toString(), "--out", out.toString()),
        )
        val decoded = recipient.inHome { ParcelCli.parcelDecode(decodeArgs) }
        assertEquals(out.toString(), decoded.out.toString())
        assertEquals(payload.size, decoded.bytes)
        assertEquals(sha256Hex(payload), decoded.sha256.lowercase())
        assertTrue(decoded.signerFp.equals(senderId.fingerprint, ignoreCase = true))
        assertTrue(decoded.recipientFp.equals(recipientId.fingerprint, ignoreCase = true))
        assertContentEquals(payload, Files.readAllBytes(out), "the landed bytes must be the original file")

        // Decode is read-only on the frame directory: a second run over the
        // unchanged PNGs lands the same bytes and the CLI reports success.
        val again = recipient.root.resolve("landed-again.bin")
        val againArgs = ParsedArgs(
            arrayOf("parcel", "decode", "--dir", frames.toString(), "--out", again.toString()),
        )
        assertEquals(0, recipient.inHome { ParcelCli.run(againArgs) })
        assertContentEquals(payload, Files.readAllBytes(again))
    }

    @Test
    fun `a frame with a broken CRC is refused and no output is written`() = isolatedHome {
        val sender = Node("a-tamper")
        val recipient = Node("b-tamper")
        val payload = ByteArray(2048).also { Random(7).nextBytes(it) }
        val frames = encodeParcelFor(sender, recipient, payload)

        val pngs = framePngs(frames)
        assertTrue(pngs.size > 1, "the tamper case needs more than one frame")
        rewriteWithBrokenCrc(pngs[pngs.size / 2])

        val out = recipient.root.resolve("never.bin")
        val args = ParsedArgs(
            arrayOf("parcel", "decode", "--dir", frames.toString(), "--out", out.toString()),
        )
        assertEquals(3, recipient.inHome { ParcelCli.run(args) }, "verification must fail")
        assertFalse(Files.exists(out), "an unverified parcel must leave no output file")
    }

    @Test
    fun `a parcel addressed to the recipient is refused by a different identity`() = isolatedHome {
        val sender = Node("a-wrong")
        val recipient = Node("b-wrong")
        val other = Node("c-wrong")
        val payload = ByteArray(1024).also { Random(11).nextBytes(it) }
        val frames = encodeParcelFor(sender, recipient, payload)
        val otherId = other.identity()

        assertTrue(
            !otherId.fingerprint.equals(recipient.identity().fingerprint, ignoreCase = true),
            "the decoding CLI must not be the parcel's recipient",
        )
        val out = other.root.resolve("never.bin")
        val args = ParsedArgs(
            arrayOf("parcel", "decode", "--dir", frames.toString(), "--out", out.toString()),
        )
        assertEquals(3, other.inHome { ParcelCli.run(args) }, "a wrong recipient must fail verification")
        assertFalse(Files.exists(out), "a wrongly addressed parcel must leave no output file")
    }
}
