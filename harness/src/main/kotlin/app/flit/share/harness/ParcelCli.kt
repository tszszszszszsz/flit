package app.flit.share.harness

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
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
    private const val MARGIN = 2
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
  decode --dir DIR --out PATH
          fold every frame-*.png into the payload and write the opened bytes to
          --out only when the codec verifies it for this CLI's identity
  exit codes: 0 verified, 2 bad args / missing cert, dir or out, 3 verification failure"""

    /**
     * Dispatches one `parcel` command and returns its exit code. Result objects
     * are produced by the two functions below; failures are printed here so the
     * caller never has to interpret an exception.
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
    } catch (e: Throwable) {
        println("!! ${e.message ?: e.javaClass.simpleName}")
        EXIT_BAD_ARGS
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
        if (payload.isEmpty() || payload.size > ParcelCodec.MAX_AIR_GAP_PARCEL_BYTES) {
            throw ParcelCliFailure(
                EXIT_BAD_ARGS,
                "payload ${payload.size} byte(s) outside 1..${ParcelCodec.MAX_AIR_GAP_PARCEL_BYTES} — nothing written",
            )
        }

        if (Files.isDirectory(dir) && frameFiles(dir).isNotEmpty()) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "frames already in $dir — nothing written")
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

        if (!runCatching { Files.createDirectories(dir) }.isSuccess) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "could not create --dir $dir")
        }
        frames.forEachIndexed { index, frame ->
            val image = render(frame)
            if (readFrame(image) != frame) {
                throw ParcelCliFailure(EXIT_BAD_ARGS, "frame ${index + 1} did not survive QR round-trip — nothing further written")
            }
            val png = dir.resolve(FRAME_FORMAT.format(index + 1))
            val wrote = runCatching { ImageIO.write(image, "png", png.toFile()) }.getOrNull() ?: false
            if (!wrote) throw ParcelCliFailure(EXIT_BAD_ARGS, "could not write $png")
        }

        println("${ts()} parcel encode $fileArg -> $dir: ${frames.size} frame(s), sealed=$sealedChars chars, sha256=$sha256")
        println("${ts()}   signer $signerFp  recipient $recipientFp  ${payload.size} bytes")
        return EncodeResult(dir, frames.size, sealedChars, sha256)
    }

    // ==== decode ==============================================================

    /**
     * Reads every `frame-*.png`, keeps each opaque frame string, and opens it
     * through [ParcelCodec.complete] with this CLI's own identity. Bytes are
     * written to --out only when the opened payload is addressed to this CLI.
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
        if (Files.exists(out)) {
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

        val parent = out.parent
        if (parent != null && !runCatching { Files.createDirectories(parent) }.isSuccess) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "could not create --out parent $parent")
        }
        if (!runCatching { Files.write(out, payload.bytes) }.isSuccess) {
            throw ParcelCliFailure(EXIT_BAD_ARGS, "could not write --out $outArg")
        }

        println("${ts()} parcel decode $outArg: ${payload.bytes.size} bytes, sha256=${payload.sha256}")
        println("${ts()}   signer ${payload.signerFp}  recipient ${payload.recipientFp}")
        println("${ts()}   seen=${seen.size} duplicate=$duplicate rejected=$rejected missing=$missing")
        return DecodeResult(out, payload.bytes.size, payload.sha256, payload.signerFp, payload.recipientFp)
    }

    // ==== image seam ==========================================================

    /** zxing QR render at 768×768, EC M, UTF-8, margin 2 — same hints as Qr.encode. */
    private fun render(frame: String): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to MARGIN,
        )
        val matrix = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, IMAGE_SIZE, IMAGE_SIZE, hints)
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until IMAGE_SIZE) {
            for (x in 0 until IMAGE_SIZE) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    /** RGBLuminanceSource + HybridBinarizer + MultiFormatReader, sampled at scales 1..4. */
    private fun readFrame(image: BufferedImage): String? {
        for (d in 1..4) {
            val w = image.width / d
            val h = image.height / d
            if (w < 1 || h < 1) continue
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.drawImage(image, 0, 0, w, h, null)
            g.dispose()
            val px = IntArray(w * h)
            small.getRGB(0, 0, w, h, px, 0, w)
            val text = runCatching {
                MultiFormatReader().decode(
                    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))),
                ).text
            }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    // ==== pure helpers ========================================================

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
