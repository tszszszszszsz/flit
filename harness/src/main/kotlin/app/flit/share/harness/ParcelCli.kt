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
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile

/**
 * The one-shot, offline QR parcel adapter. All frame grammar, CRC, signature,
 * encryption and digest work stays in [ParcelCodec] and the courier envelope it
 * calls; this file owns only the PNG render/read seam, flag validation, the
 * certificate-out-of-band gate and the two exit-code paths.
 *
 * The frame directory is the whole checkpoint: numbered PNGs, no manifest and
 * no sidecar. The recipient certificate is read for one invocation and is never
 * copied into the directory. No file name travels in a frame — `decode` writes
 * bytes only to the operator-supplied `--out`.
 */
object ParcelCli {

    /** Result of `parcel encode`. `sealedChars` is the char count of the framed
     * sealed envelope (ASCII, so the descriptor's byte count equals its chars). */
    data class EncodeResult(
        val dir: Path,
        val frames: Int,
        val sealedChars: Int,
        val sha256: String,
    )

    /** Result of `parcel decode`. sha256/signer/recipient come from the opened payload. */
    data class DecodeResult(
        val out: Path,
        val bytes: Int,
        val sha256: String,
        val signerFp: String,
        val recipientFp: String,
    )

    /** Bad args, missing cert/dir/out, I/O refusal. */
    internal const val EXIT_BAD_ARGS = 2
    /** Codec verification or recipient gate failure: no output file is written. */
    internal const val EXIT_VERIFY = 3

    private const val IMAGE_SIZE = 768
    /** Dense frames can be unreadable at 768px; encode escalates the *whole*
     * frame set to this size when any frame fails its round-trip — never a mix. */
    private const val LARGE_IMAGE_SIZE = 1024
    /** A third escalation for dense frames when the JVM is under suite load. */
    private const val LARGEST_IMAGE_SIZE = 1536
    private const val MARGIN = 2
    /** Decode hints: try hard, and look only for the QR format we wrote. */
    private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )
    private const val FRAME_FORMAT = "frame-%04d.png"
    private val FRAME_NAME = Regex("^frame-.*\\.png$")
    private val MAX_FP_CHARS = 128

    /** Failure carrying the command's exit code; [run] turns it into a `!!` line. */
    internal class ParcelCliFailure(val exitCode: Int, message: String) : Exception(message)

    // ==== command surface =====================================================

    fun usage(): String = """parcel <encode|decode>
  encode <file> --dir DIR --to FP --cert PATH
          parse Base64 DER X.509 from --cert, check its fingerprint against --to,
          and render one frame-%04d.png per codec frame into an empty --dir
          (768px PNGs, or 1024px when a dense frame needs the extra module size;
          the frame strings are never changed, only their rendering)
  decode --dir DIR --out PATH
          fold every frame-*.png into the payload and write the opened bytes to
          --out only when the codec verifies it for this CLI's identity
  exit codes: 0 verified, 2 bad args / missing cert, dir or out, 3 verification failure"""

    /**
     * Dispatches one `parcel` command and returns its exit code. Result objects
     * are produced by the two functions below; only the deliberate
     * [ParcelCliFailure] is printed and mapped to 2/3 here. Any other throwable
     * is unexpected and propagates to `Harness.main`, which exits 1 — it must
     * never be disguised as a bad argument or a verification refusal.
     */
    internal fun run(args: ParsedArgs): Int = try {
        when (args.positional.getOrNull(1)) {
            "encode" -> {
                parcelEncode(args)
                0
            }
            "decode" -> {
                parcelDecode(args)
                0
            }
            else -> {
                println(usage())
                EXIT_BAD_ARGS
            }
        }
    } catch (e: ParcelCliFailure) {
        println("!! ${e.message}")
        e.exitCode
    }

    // ==== encode ==============================================================

    /**
     * Reads one file, gates the certificate supplied out of band against --to,
     * seals it through [ParcelCodec.encodeParcel] and writes one PNG per opaque
     * frame string. Each PNG is round-tripped through zxing before it is written.
     */
    internal fun parcelEncode(args: ParsedArgs): EncodeResult {
        val fileArg = args.positional.getOrNull(2)
        val dirArg = args.opt("--dir")
        val toArg = args.opt("--to")
        val certArg = args.opt("--cert")
        if (fileArg.isNullOrBlank() || dirArg.isNullOrBlank() ||
            toArg.isNullOrBlank() || certArg.isNullOrBlank()
        ) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "parcel encode needs <file> --dir DIR --to FP --cert PATH")
        }
        val file = Path.of(fileArg)
        if (!Files.isRegularFile(file)) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "no file: $fileArg")
        }
        val recipientFp = norm(toArg)
            ?: throw ParcelCliFailure(EXIT_BAD_ARGS, "bad --to $toArg")
        val dir = Path.of(dirArg)
        val target = dir.toAbsolutePath().normalize()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target)) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "--dir is not a directory: $dirArg")
        }
        if (Files.isDirectory(target) && frameFiles(target).isNotEmpty()) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "frames already in $dirArg — nothing written")
        }
        // The frame dir is the whole checkpoint: a rename can only claim an
        // absent or empty directory, so refuse one holding unrelated files
        // rather than crashing or silently mixing frames with its contents.
        if (Files.isDirectory(target) &&
            runCatching { Files.list(target).use { it.findAny().isPresent } }.getOrDefault(true)
        ) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "--dir is not empty: $dirArg — nothing written")
        }
        // Stage beside --dir so the final rename never crosses a filesystem
        // boundary and can be one atomic operation.
        val parent = target.parent
            ?: throw ParcelCliFailure(EXIT_BAD_ARGS, "bad --dir $dirArg")

        val cert = parseCert(Path.of(certArg))
            ?: throw ParcelCliFailure(EXIT_BAD_ARGS, "bad --cert — expected Base64 DER X.509, nothing written")
        val certFp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull()
        if (certFp?.equals(recipientFp, ignoreCase = true) != true) {
            throw ParcelCliFailure(
                EXIT_BAD_ARGS,
                "certificate ${certFp?.take(12) ?: "?"} differs from --to ${recipientFp.take(12)} — nothing written",
            )
        }

        val payload = runCatching { Files.readAllBytes(file) }
            .getOrNull()
            ?: throw ParcelCliFailure(EXIT_BAD_ARGS, "could not read $fileArg")
        if (payload.isEmpty() || payload.size > ParcelCodec.MAX_AIR_GAP_PAYLOAD_BYTES) {
            throw ParcelCliFailure(
                EXIT_BAD_ARGS,
                "payload ${payload.size} byte(s) outside 1..${ParcelCodec.MAX_AIR_GAP_PAYLOAD_BYTES} — nothing written",
            )
        }

        val identity = loadOrCreateIdentity()
        val signerFp = identity.fingerprint
        val parcelId = UUID.randomUUID().toString().replace("-", "")
        val frames = ParcelCodec.encodeParcel(payload, parcelId, recipientFp, cert, signerFp, identity)
        if (frames.isEmpty()) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "seal or framing refused — no frames written")
        }
        val sealedChars = ParcelCodec.parse(frames.first())?.totalBytes ?: 0
        val sha256 = sha256Hex(payload)

        if (!runCatching { Files.createDirectories(parent) }.isSuccess) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "could not create --dir parent $parent")
        }
        var renderSize = IMAGE_SIZE
        val staged = runCatching { Files.createTempDirectory(parent, ".parcel-frames-") }
            .getOrElse { throw ParcelCliFailure(EXIT_BAD_ARGS, "could not stage frames in $parent") }
        try {
            val rendered = renderFrames(frames)
            renderSize = rendered.first
            rendered.second.forEachIndexed { index, image ->
                val png = staged.resolve(FRAME_FORMAT.format(index + 1))
                val wrote = runCatching { ImageIO.write(image, "png", png.toFile()) }.getOrNull() ?: false
                if (!wrote) throw ParcelCliFailure(EXIT_BAD_ARGS, "could not write frame ${index + 1}")
            }
            // --dir is either absent or a confirmed empty directory: retire the
            // empty shell so the whole finished set lands in a single rename.
            val replacedEmpty = Files.isDirectory(target)
            if (replacedEmpty) Files.delete(target)
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, target)
            } catch (t: Throwable) {
                if (replacedEmpty && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    runCatching { Files.createDirectories(target) }
                }
                throw t
            }
        } catch (t: Throwable) {
            deleteTree(staged)
            throw t
        }

        println("${ts()} parcel encode $fileArg -> $dir: ${frames.size} frame(s), png=${renderSize}px, sealed=$sealedChars chars, sha256=$sha256")
        println("${ts()}   signer $signerFp  recipient $recipientFp  ${payload.size} bytes")
        return EncodeResult(dir, frames.size, sealedChars, sha256)
    }

    // ==== decode ==============================================================

    /**
     * Reads every `frame-*.png`, keeps each opaque frame string, and opens it
     * through [ParcelCodec.complete] with this CLI's own identity. Bytes are
     * written to --out only when the opened payload is addressed to this CLI.
     * --out must be outside --dir and is created exclusively (CREATE_NEW,
     * NOFOLLOW_LINKS): an existing file or link is never overwritten and output
     * can never be folded back in as a frame.
     */
    internal fun parcelDecode(args: ParsedArgs): DecodeResult {
        val dirArg = args.opt("--dir")
        val outArg = args.opt("--out")
        if (dirArg.isNullOrBlank() || outArg.isNullOrBlank()) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "parcel decode needs --dir DIR --out PATH")
        }
        val dir = Path.of(dirArg)
        if (!Files.isDirectory(dir)) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "no frame dir: $dirArg")
        }
        val out = Path.of(outArg)
        if (insideDir(out, dir)) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "--out must be outside --dir: $outArg")
        }
        if (Files.exists(out, LinkOption.NOFOLLOW_LINKS)) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "output already exists: $outArg")
        }

        val identity = loadOrCreateIdentity()
        val me = norm(identity.fingerprint)
            ?: throw ParcelCliFailure(EXIT_BAD_ARGS, "bad local fingerprint")

        val frames = mutableListOf<String>()
        val seen = LinkedHashMap<Int, Int>()
        var duplicate = 0
        var rejected = 0
        var expected = 0
        for (png in frameFiles(dir)) {
            val image = runCatching { ImageIO.read(png.toFile()) }.getOrNull()
            val text = if (image != null) readFrame(image) else null
            if (text == null) {
                rejected++
                println("${ts()}   ${png.fileName}: no readable QR, rejected")
                continue
            }
            val desc = ParcelCodec.parse(text)
            if (desc == null) {
                rejected++
                println("${ts()}   ${png.fileName}: unreadable frame, rejected")
                continue
            }
            expected = maxOf(expected, desc.count)
            if (seen.put(desc.seq, (seen[desc.seq] ?: 0) + 1) != null) {
                duplicate++
                println("${ts()}   ${png.fileName}: duplicate frame ${desc.seq}")
            }
            frames.add(text)
        }
        val missing = if (expected > 0) (1..expected).filter { it !in seen } else emptyList()

        val payload = ParcelCodec.complete(frames, identity.fingerprint, identity) ?: throw ParcelCliFailure(
            EXIT_VERIFY,
            "parcel verification failed (frames=${frames.size}, missing=${missing})",
        )
        if (!norm(payload.recipientFp).equals(me, ignoreCase = true)) {
            throw ParcelCliFailure(
                EXIT_VERIFY,
                "parcel addressed to ${payload.recipientFp}, not ${identity.fingerprint}",
            )
        }

        val parent = out.toAbsolutePath().normalize().parent
        if (parent != null && !runCatching { Files.createDirectories(parent) }.isSuccess) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "could not create --out parent $parent")
        }
        val wrote = runCatching {
            Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS).use { stream ->
                stream.write(payload.bytes)
            }
        }
        if (wrote.isFailure) {
            throw ParcelCliFailure(
                EXIT_BAD_ARGS,
                "could not write --out $outArg (${wrote.exceptionOrNull()?.message ?: "refused"})",
            )
        }

        println("${ts()} parcel decode $outArg: ${payload.bytes.size} bytes, sha256=${payload.sha256}")
        println("${ts()}   signer ${payload.signerFp}  recipient ${payload.recipientFp}")
        println("${ts()}   seen=${seen.size} duplicate=$duplicate rejected=$rejected missing=$missing")
        return DecodeResult(out, payload.bytes.size, payload.sha256, payload.signerFp, payload.recipientFp)
    }

    // ==== image seam ==========================================================

    /** zxing QR render at [size]×[size], EC M, UTF-8, margin 2 — same hints as Qr.encode. */
    private fun render(frame: String, size: Int): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to MARGIN,
        )
        val matrix = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    /**
     * Render the whole set at one size and prove every PNG round-trips before
     * any is staged. A single failure escalates all frames through
     * [LARGE_IMAGE_SIZE] and [LARGEST_IMAGE_SIZE] — the opaque frame strings
     * are untouched, only their pixel size changes.
     */
    private fun renderFrames(frames: List<String>): Pair<Int, List<BufferedImage>> {
        var failed = -1
        for (size in intArrayOf(IMAGE_SIZE, LARGE_IMAGE_SIZE, LARGEST_IMAGE_SIZE)) {
            val images = frames.map { render(it, size) }
            val bad = images.indices.firstOrNull { readFrame(images[it]) != frames[it] }
            if (bad == null) return size to images
            failed = bad
        }
        throw ParcelCliFailure(
            EXIT_BAD_ARGS,
            "frame ${failed + 1} did not survive QR round-trip at ${IMAGE_SIZE}px, ${LARGE_IMAGE_SIZE}px or ${LARGEST_IMAGE_SIZE}px — nothing further written",
        )
    }

    /**
     * RGBLuminanceSource + HybridBinarizer + a hinted MultiFormatReader. Try the
     * original pixels first, then the 1..4 integer downscales: dense frames can
     * need TRY_HARDER at one scale or the larger render's extra module size.
     */
    private fun readFrame(image: BufferedImage): String? {
        decodeImage(image)?.let { return it }
        for (d in 1..4) {
            val w = image.width / d
            val h = image.height / d
            if (w < 1 || h < 1) continue
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.drawImage(image, 0, 0, w, h, null)
            g.dispose()
            decodeImage(small)?.let { return it }
        }
        return null
    }

    /** One zxing attempt with TRY_HARDER and QR_CODE as the only possible format. */
    private fun decodeImage(image: BufferedImage): String? {
        val w = image.width
        val h = image.height
        val px = IntArray(w * h)
        image.getRGB(0, 0, w, h, px, 0, w)
        return runCatching {
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))),
                DECODE_HINTS,
            ).text
        }.getOrNull()
    }

    // ==== pure helpers ========================================================

    /**
     * True when [child] is [dir] itself or lies beneath it. Existing parents
     * are resolved through links so a symlinked parent cannot smuggle --out
     * back into the frame directory; a lexical prefix check alone would miss it.
     */
    private fun insideDir(child: Path, dir: Path): Boolean {
        fun nearestExisting(p: Path): Path {
            var cur = p.toAbsolutePath().normalize()
            while (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                cur = cur.parent ?: return cur
            }
            return runCatching { cur.toRealPath() }.getOrDefault(cur)
        }
        return nearestExisting(child).startsWith(nearestExisting(dir))
    }

    /** Best-effort cleanup of a staged frame tree after a failed encode. */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            val paths = Files.walk(root).use { it.toList() }
            for (p in paths.asReversed()) runCatching { Files.deleteIfExists(p) }
        }
    }

    /** Only `frame-*.png` files, in lexicographic (= codec frame) order. */
    private fun frameFiles(dir: Path): List<Path> = if (Files.isDirectory(dir)) {
        runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.isRegularFile() && FRAME_NAME.matches(it.fileName.toString()) }
                    .sorted(compareBy<Path> { it.fileName.toString() })
                    .toList()
            }
        }.getOrDefault(emptyList())
    } else emptyList()

    /** Base64 DER X.509 from a file, parsed with CertificateFactory. */
    private fun parseCert(path: Path): X509Certificate? {
        val raw = runCatching { Files.readString(path, Charsets.UTF_8) }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return runCatching {
            val der = Base64.getMimeDecoder().decode(raw)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as? X509Certificate
        }.getOrNull()
    }

    private fun norm(fp: String?): String? = fp?.trim()?.uppercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_FP_CHARS }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
