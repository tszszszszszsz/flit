package app.flit.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * Air-gap parcel screens.
 *
 * Show takes the ordered, opaque `List<String>` returned by
 * `ParcelCodec.encodeParcel(...)` and cycles it at [PARCEL_FRAME_INTERVAL_MS].
 * Scan feeds camera (or pasted) frame strings into `ParcelScanState`, reads only
 * its progress fields, and hands the verified bytes to an injected writer. No
 * frame grammar, CRC, digest or filename is interpreted here: a frame is an
 * opaque string, the save name is chosen locally by the caller, and the
 * verified `ParcelPayload` is the only thing that becomes durable.
 *
 * The frozen `ParcelScanState` seam supplies `add(frame): Boolean`,
 * `progressInfo: Progress(received, count)`, `complete(): ParcelPayload?` and
 * `clear()`; this screen attempts completion only when the accepted count has
 * reached the declared count, and uses `add`'s boolean to decide whether a
 * pasted batch changed anything. `MAX_PARCEL_FRAMES` below mirrors
 * `ParcelCodec.MAX_FRAMES`.
 */

/** Dwell per frame before the show card advances and wraps. */
const val PARCEL_FRAME_INTERVAL_MS = 400L

/** Hard cap on displayed or assembled frames; mirrors `ParcelCodec.MAX_FRAMES`. */
const val MAX_PARCEL_FRAMES = ParcelCodec.MAX_FRAMES

/** `Qr.encode` side for one rendered parcel frame. */
const val PARCEL_FRAME_SIZE_PX = 240

/** Cap on the paste field's newline-separated frame text. */
const val PARCEL_PASTE_MAX_CHARS = 16_000

/** Pure index step with wrap. Kept Android-free so the cadence is testable. */
fun advanceParcelIndex(current: Int, size: Int): Int =
    if (size <= 0) 0 else ((current + 1) % size + size) % size

/** Pure reverse step with wrap, for the Back control. */
fun rewindParcelIndex(current: Int, size: Int): Int =
    if (size <= 0) 0 else ((current - 1) % size + size) % size

/** "frame i of n", with `?` until a descriptor supplies the count. */
fun parcelProgressLabel(received: Int, count: Int?): String =
    "frame $received of ${count?.toString() ?: "?"}"

/**
 * Show mode: one QR per frame, advancing in codec order at a fixed cadence
 * and wrapping from the last frame to the first. The display index is local
 * Compose state; closing the card discards it and writes nothing.
 */
@Composable
fun ParcelShowCard(frames: List<String>, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onClose() }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(frames) {
        if (frames.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(PARCEL_FRAME_INTERVAL_MS)
            index = advanceParcelIndex(index, frames.size)
        }
    }
    val shown = if (frames.isEmpty()) 0 else index.coerceIn(0, frames.lastIndex)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background.copy(alpha = 0.97f))
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Text(
                "air-gap parcel",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                parcelProgressLabel(shown + 1, frames.size.takeIf { it > 0 }),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(18.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(FlitCorner.extraLarge)) {
                Box(modifier = Modifier.padding(18.dp)) {
                    if (frames.isEmpty()) {
                        Text("no frames to show", color = Color.Black)
                    } else {
                        ParcelFrameCanvas(frame = frames[shown], side = PARCEL_FRAME_SIZE_PX.dp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "hold the other phone's camera on each frame — they advance automatically",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { index = rewindParcelIndex(shown, frames.size) }) {
                    Text("Back")
                }
                OutlinedButton(onClick = { index = advanceParcelIndex(shown, frames.size) }) {
                    Text("Next")
                }
                Button(onClick = onClose) { Text("Done") }
            }
        }
    }
}

/**
 * One frame as a crisp QR. `Qr.encode` already fixes EC level M and a two-module
 * margin; the raster side is [PARCEL_FRAME_SIZE_PX] so a rendered frame is the
 * same size the camera test decodes.
 */
@Composable
private fun ParcelFrameCanvas(frame: String, side: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(frame) { Qr.encode(frame, PARCEL_FRAME_SIZE_PX) }
    Canvas(modifier = modifier.size(side)) {
        val scale = this.size.width / matrix.width
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (matrix.get(x, y)) {
                    var run = 1
                    while (x + run < matrix.width && matrix.get(x + run, y)) run++
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * scale, y * scale),
                        size = Size(run * scale, scale),
                    )
                    x += run
                } else {
                    x++
                }
            }
        }
    }
}

/** What the scan card displays after a verified parcel lands. */
private data class ParcelReceipt(
    val signerFp: String,
    val sha256: String,
    val bytes: Int,
)

/**
 * Scan mode: camera Y-plane decoding at ~8 fps, or newline-separated paste
 * when camera access is refused. Every decoded string is offered to the same
 * state holder; only a verified result reaches [onBytes], exactly once.
 */
@Composable
fun ParcelScanCard(
    identity: TlsIdentity,
    onBytes: (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    BackHandler { onClose() }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    // Per-dialog assembly: no partial parcel survives closing or completion.
    val scan = remember(identity) { ParcelScanState(identity.fingerprint, identity) }
    var received by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(0) } // 0 = descriptor not seen yet
    var receipt by remember { mutableStateOf<ParcelReceipt?>(null) }
    var delivered by remember { mutableStateOf(false) }
    // The analyzer runs off-main. Every state write and the writer call happen
    // on the main thread, and are suppressed once this dialog has gone away.
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    fun reset() {
        scan.clear()
        received = 0
        count = 0
        receipt = null
        delivered = false
    }

    /** Main-thread-only; returns true when the frame was newly accepted. */
    fun offer(frame: String): Boolean {
        if (!alive.get() || receipt != null) return false
        val accepted = scan.add(frame)
        val progress = scan.progressInfo
        received = progress.received
        count = progress.count ?: 0
        if (count > 0 && received >= count) {
            val payload = scan.complete()
            if (payload != null && !delivered && alive.get()) {
                delivered = true
                receipt = ParcelReceipt(payload.signerFp, payload.sha256, payload.bytes.size)
                onBytes(payload.bytes)
            }
        }
        return accepted
    }

    fun offerOnMain(frame: String) {
        mainHandler.post {
            if (alive.get()) offer(frame)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val landed = receipt
        if (landed != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(FlitCorner.extraLarge),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("parcel received", style = MaterialTheme.typography.titleLarge)
                        Text(
                            parcelProgressLabel(received, count.takeIf { it > 0 }),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "from ${landed.signerFp.take(12)}…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "sha256 ${landed.sha256}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${landed.bytes} bytes",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                            Text("Done")
                        }
                    }
                }
            }
        } else {
            if (granted) {
                ParcelCameraView(onFrame = { text -> offerOnMain(text) }, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    var manual by rememberSaveable { mutableStateOf("") }
                    Text(
                        "camera access is off — paste the frame strings, one per line",
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.take(PARCEL_PASTE_MAX_CHARS) },
                        minLines = 3,
                        placeholder = { Text("FLITPARCEL1.1.…\nFLITPARCEL1.1.…") },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        OutlinedButton(onClick = onClose) { Text("Cancel") }
                        OutlinedButton(
                            enabled = manual.isNotBlank(),
                            onClick = {
                                var anyAccepted = false
                                manual.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .forEach { text -> if (offer(text)) anyAccepted = true }
                                // Only consume the user's text when it actually
                                // changed the scan; a fully rejected batch stays.
                                if (anyAccepted) manual = ""
                            },
                        ) { Text("Add frames") }
                    }
                    OutlinedButton(
                        onClick = { ask.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Try the camera again") }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    parcelProgressLabel(received, count.takeIf { it > 0 }),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { reset() }) {
                    Text("Reset", color = Color.White)
                }
            }
            Text(
                "point at the sender's frames in order",
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
            )
        }
    }
}

/**
 * CameraX wiring kept to the `CameraQrView` shape: one Y plane, at most ~8 fps,
 * copied with row/pixel strides and decoded through `Qr.decodeYPlane`.
 */
@Composable
private fun ParcelCameraView(onFrame: (String) -> Unit, modifier: Modifier = Modifier) {
    // The controller binds to the composition's lifecycle owner, not to the
    // AndroidView factory's Context (which is not a LifecycleOwner).
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val lastAttempt = remember { AtomicLong(0) }
    val analyzerActive = remember { AtomicBoolean(true) }
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { viewCtx ->
            val ctl = LifecycleCameraController(viewCtx)
            controller = ctl
            ctl.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            ctl.setImageAnalysisAnalyzer(executor, { proxy: ImageProxy ->
                try {
                    if (!analyzerActive.get()) return@setImageAnalysisAnalyzer
                    val now = System.nanoTime()
                    if (now - lastAttempt.get() <= 120_000_000L) return@setImageAnalysisAnalyzer
                    lastAttempt.set(now)
                    val plane = proxy.planes[0]
                    val rot = when (proxy.imageInfo.rotationDegrees) {
                        90 -> 1
                        180 -> 2
                        270 -> 3
                        else -> 0
                    }
                    val rows = plane.rowStride
                    val pixel = plane.pixelStride
                    val width = proxy.width
                    val height = proxy.height
                    val cap = rows * (height - 1) + (width - 1) * pixel + 1
                    val y = ByteArray(cap)
                    plane.buffer.rewind()
                    for (r in 0 until height) {
                        plane.buffer.position(r * rows)
                        plane.buffer.get(y, r * rows, (width - 1) * pixel + 1)
                    }
                    Qr.decodeYPlane(
                        y = y,
                        width = width,
                        height = height,
                        rowStride = rows,
                        pixelStride = pixel,
                        rotationTimes = rot,
                    )?.let { text -> if (analyzerActive.get()) onFrame(text) }
                } catch (_: Throwable) {
                    // a skewed or noisy frame is not an error surface
                } finally {
                    runCatching { proxy.close() }
                }
            })
            ctl.bindToLifecycle(lifecycleOwner)
            PreviewView(viewCtx).also { it.controller = ctl }
        },
        onRelease = {
            // Stop the analyzer before unbinding: a closed dialog must not have
            // an in-flight frame left that could still reach the writer.
            analyzerActive.set(false)
            controller?.let { ctl ->
                runCatching { ctl.clearImageAnalysisAnalyzer() }
                runCatching { ctl.unbind() }
            }
            controller = null
            executor.shutdown()
        },
    )
}
