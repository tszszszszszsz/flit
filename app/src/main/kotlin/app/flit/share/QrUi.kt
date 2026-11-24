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
 */
@Composable
fun QrShowCard(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onClose() }
    var self by remember { mutableStateOf<QrAdd?>(null) }
    LaunchedEffect(Unit) {
        // The engine may still be booting (identity load is async); retry briefly.
        while (self == null) {
            self = FlitEngine.selfQrAdd()
            if (self == null) delay(300)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background.copy(alpha = 0.97f))
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Surface(color = Color.White, shape = RoundedCornerShape(FlitCorner.extraLarge)) {
                Box(modifier = Modifier.padding(18.dp)) {
                    val c = self
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
            Text(self?.alias ?: "", style = MaterialTheme.typography.titleLarge)
            Text(
                self?.let { code ->
                    if (code.ip.isNotBlank()) "${code.ip}:${code.port}"
                    else code.relay ?: "?"
                } ?: "?",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.7f),
            )
            Text(
                self?.fingerprint?.take(12)?.let { "fp $it…" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "point the other phone's camera here — or open Flit → Add → Scan code on it",
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
