package com.silence.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * QR code scanner for adding contacts.
 *
 * Scans `silence://<base64_pubkey>` format QR codes using the
 * zxing-android-embedded library's DecoratedBarcodeView.
 *
 * On successful scan, emits the public key and stops scanning.
 * The caller is responsible for navigating away after receiving the key.
 */
@Composable
fun QrScanScreen(
    onScanned: (publicKeyB64: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Hold a reference to the DecoratedBarcodeView for pause/resume
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    // Camera permission
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // When barcodeView becomes available, start scanning
    LaunchedEffect(barcodeView) {
        barcodeView?.resume()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Scan Contact QR", style = MaterialTheme.typography.titleLarge)
        }

        if (hasPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        DecoratedBarcodeView(ctx).also { bv ->
                            barcodeView = bv
                            val formats = listOf(BarcodeFormat.QR_CODE)
                            bv.barcodeView.decoderFactory =
                                com.journeyapps.barcodescanner.DefaultDecoderFactory(formats)

                            bv.decodeContinuous(object : BarcodeCallback {
                                override fun barcodeResult(result: BarcodeResult?) {
                                    val text = result?.text ?: return
                                    if (text.startsWith("silence://")) {
                                        val pubKey = text.removePrefix("silence://")
                                        // Stop scanning, post result to main thread
                                        bv.pause()
                                        mainHandler.post { onScanned(pubKey) }
                                    }
                                }

                                override fun possibleResultPoints(
                                    result: List<com.google.zxing.ResultPoint>?
                                ) {
                                    // no-op
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission required")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant permission")
                    }
                }
            }
        }
    }
}
