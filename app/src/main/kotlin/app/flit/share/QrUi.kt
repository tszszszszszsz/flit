package app.flit.share

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * Pairing screens: "My code" (the QR to be scanned) and the scanner.
 *
 * Both keep zero state in FlitEngine — every byte of payload flows through
 * [Qr]/[parseAddCode], and adding happens via [FlitEngine.addFromScan], the
 * exact same entry point the paste fallback uses.
 */

/**
 * The facts a flit://add confirmation must put in front of the user before any
 * pin or join. Deliberately excludes the invite token and any group key: a
 * confirmation is an eyeball check, not a second copy of the secret.
 */
data class DeepLinkPrompt(
    val host: String,
    val fingerprintPrefix: String,
    val circleId: String?,
    val isInvite: Boolean,
)

/** Pure projection of an untrusted code into confirmation text. */
fun describeDeepLink(code: QrAdd): DeepLinkPrompt {
    val host = code.ip.takeIf { it.isNotBlank() }?.let { ip ->
        if (code.port > 0) "$ip:${code.port}" else ip
    } ?: code.relay?.trim()?.takeIf { it.isNotBlank() } ?: "unknown host"
    val fp = code.fingerprint.trim().uppercase()
    return DeepLinkPrompt(
        host = host,
        fingerprintPrefix = fp.take(12).ifBlank { "not provided" },
        circleId = code.circle?.trim()?.takeIf { it.isNotBlank() },
        isInvite = !code.circle.isNullOrBlank() || !code.invite.isNullOrBlank(),
    )
}

/** Only the exact flit://add authority is accepted — lookalikes and the
 *  retired flit://pair authority are refused. Pure: no Android, no engine. */
fun isFlitAddAuthority(authority: String?): Boolean =
    authority?.equals("add", ignoreCase = true) == true

/**
 * Sets FLAG_SECURE on whichever Window actually hosts this content. Compose's
 * AlertDialog / Dialog create their own Window, so an Activity-only flag would
 * leave QR codes, invites, circle feeds and alerts screenshot-able. Transparent
 * to layout: it only wraps [content] in a lifecycle-bound window flag.
 */
@Composable
fun SecureWindow(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        var c: android.content.Context? = ctx
        var win: android.view.Window? = null
        while (c != null && win == null) {
            when (c) {
                is android.app.Dialog -> win = c.window
                is android.app.Activity -> win = c.window
            }
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    content()
}

/** Pure-white QR canvas: crisp modules at any DPI, no bitmap resampling. */
@Composable
fun QrCanvas(text: String, side: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { Qr.encode(text, 160) } // raster ≥ module count; scale is ours
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
                        topLeft = androidx.compose.ui.geometry.Offset(x * scale, y * scale),
                        size = androidx.compose.ui.geometry.Size(run * scale, scale),
                    )
                    x += run
                } else x++
            }
        }
    }
}

/**
 * "My code": full screen, engine underneath. The QR shows our current Wi‑Fi
 * address + fingerprint; the other phone's stock camera or Flit's scanner
 * both work. Honest message when there is no usable local address (yet).
 *
 * Pass [invite] to show a v3 circle invite instead of a plain pairing code.
 * When the code carries [QrAdd.circle]/[QrAdd.invite] the card says so and
 * points the scanner at Circle → Join by QR, never at Add → Scan code — a
 * circle invite is not a device pairing. No group key is displayed: it is
 * exchanged over the pinned route only after the invite is accepted.
 */
@Composable
fun QrShowCard(onClose: () -> Unit, invite: QrAdd? = null) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onClose() }
    var self by remember { mutableStateOf<QrAdd?>(null) }
    LaunchedEffect(Unit) {
        // An explicit invite is already the payload to show; don't wait on the
        // engine. Otherwise it may still be booting (identity load is async).
        if (invite != null) return@LaunchedEffect
        while (self == null) {
            self = FlitEngine.selfQrAdd()
            if (self == null) delay(300)
        }
    }
    val shown = invite ?: self
    val hasInvite = shown?.let { !it.circle.isNullOrBlank() || !it.invite.isNullOrBlank() } ?: false
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background.copy(alpha = 0.97f))
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        SecureWindow { } // pairing codes and circle invites stay off screenshots
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Surface(color = Color.White, shape = RoundedCornerShape(FlitCorner.extraLarge)) {
                Box(modifier = Modifier.padding(18.dp)) {
                    val c = shown
                    if (c != null) {
                        QrCanvas(text = c.uri(), side = 268.dp)
                    } else {
                        Text(
                            "no local address yet\n(is Wi‑Fi on and connected?)",
                            color = Color.Black,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (hasInvite) "circle invite" else shown?.alias ?: "",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                shown?.let { code ->
                    if (code.ip.isNotBlank()) "${code.ip}:${code.port}"
                    else code.relay ?: "?"
                } ?: "?",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.7f),
            )
            Text(
                shown?.fingerprint?.take(12)?.let { if (hasInvite) "host fp $it…" else "fp $it…" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.45f),
            )
            if (hasInvite) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "circle ${shown?.circle.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface.copy(alpha = 0.8f),
                )
                Text(
                    "invite ${shown?.invite.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (hasInvite) {
                    "scan with Flit → Circle → Join by QR to enter this circle"
                } else {
                    "point the other phone's camera here — or open Flit → Add → Scan code on it"
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Done") }
        }
    }
}

/**
 * The scanner: CameraX preview + a stride-aware Y-plane analyzer. A hit
 * reports out exactly once (the UI closes), then the engine probes, pins
 * and badges — scan → verified in one move.
 */
@Composable
fun QrScanCard(onCode: (String) -> Unit, onClose: () -> Unit) {
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        SecureWindow { } // the scan surface is secret too
        if (granted) {
            CameraQrView(onCode = onCode, modifier = Modifier.fillMaxSize())
        } else {
            // Refused (or in flight): the paste path is fully equivalent for
            // a shared code text; the camera is only the zero-effort default.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                var manual by remember { mutableStateOf("") }
                Text(
                    "camera access is off — paste the code text instead",
                    color = Color.White.copy(alpha = 0.85f),
                )
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    singleLine = true,
                    placeholder = { Text("flit://add?v=2&…") },
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
                            onCode(manual)
                            onClose()
                        },
                    ) { Text("Add") }
                }
                OutlinedButton(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Try the camera again")
                }
            }
        }
        Text(
            "point at another device's Flit code",
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
        )
    }
}

/**
 * CameraX wiring kept minimal: preview via LifecycleCameraController, decode
 * at most ~8 fps, Y-plane only (a QR IS luminance data).
 */
@Composable
private fun CameraQrView(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val lastAttempt = remember { AtomicLong(0) }
    AndroidView(
        modifier = modifier,
        factory = { viewCtx ->
            val ctl = LifecycleCameraController(viewCtx)
            ctl.cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
            ctl.setImageAnalysisAnalyzer(executor, { proxy: androidx.camera.core.ImageProxy ->
                try {
                    val now = System.nanoTime()
                    if (now - lastAttempt.get() <= 120_000_000L) return@setImageAnalysisAnalyzer
                    lastAttempt.set(now)
                    val plane = proxy.planes[0]
                    val rot = when (proxy.imageInfo.rotationDegrees) { 90 -> 1; 180 -> 2; 270 -> 3; else -> 0 }
                    val rows = plane.rowStride
                    val pixel = plane.pixelStride
                    val width = proxy.width
                    val height = proxy.height
                    // Copy exactly what the sampler will read: rowStarts and
                    // (width-1)*pixelStride inside each row — never OOB even
                    // when the driver over-trims the tail.
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
                    )?.let { text -> onCode(text) }
                } catch (_: Throwable) {
                    // frames keep coming; a skew frame is not an error surface
                } finally {
                    runCatching { proxy.close() }
                }
            })
            ctl.bindToLifecycle(viewCtx as androidx.lifecycle.LifecycleOwner)
            PreviewView(viewCtx).also { it.controller = ctl }
        },
        onRelease = { executor.shutdown() },
    )
}
