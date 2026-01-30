package dev.keiji.j2k_wasm_sample

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import dev.keiji.jp2k.Jp2kDecoder
import dev.keiji.jp2k.MemoryUsage
import dev.keiji.jp2k.Size
import dev.keiji.j2k_wasm_sample.ui.theme.J2kwasmsampleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DecodeResult(
    val bmp: Bitmap?,
    val bmpCropped: Bitmap?,
    val usageBefore: MemoryUsage?,
    val usageAfter: MemoryUsage?,
    val size: Size?
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            J2kwasmsampleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var bitmapCropped by remember { mutableStateOf<Bitmap?>(null) }
                    var memoryUsageBefore by remember { mutableStateOf<MemoryUsage?>(null) }
                    var memoryUsageAfter by remember { mutableStateOf<MemoryUsage?>(null) }
                    var size by remember { mutableStateOf<Size?>(null) }
                    var error by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val bytes = applicationContext.assets.open(ASSET_PATH_SAMPLE_IMAGE).use {
                                    it.readBytes()
                                }
                                Jp2kDecoder().use { decoder ->
                                    decoder.init(applicationContext)
                                    decoder.precache(bytes)
                                    val usageBefore = decoder.getMemoryUsage()
                                    val imageSize = decoder.getSize()
                                    val bmp = decoder.decodeImage()

                                    val width = imageSize.width
                                    val height = imageSize.height
                                    val cropLeft = (width * 0.125).toInt()
                                    val cropTop = (height * 0.125).toInt()
                                    val cropRight = (width * 0.875).toInt()
                                    val cropBottom = (height * 0.875).toInt()
                                    val bmpCropped = decoder.decodeImage(cropLeft, cropTop, cropRight, cropBottom)

                                    val usageAfter = decoder.getMemoryUsage()
                                    DecodeResult(
                                        bmp = bmp,
                                        bmpCropped = bmpCropped,
                                        usageBefore = usageBefore,
                                        usageAfter = usageAfter,
                                        size = imageSize
                                    )
                                }
                            }
                            bitmap = result.bmp
                            bitmapCropped = result.bmpCropped
                            memoryUsageBefore = result.usageBefore
                            memoryUsageAfter = result.usageAfter
                            size = result.size
                        } catch (e: Exception) {
                            error = e.message ?: "Unknown error"
                        }
                    }

                    Box(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            bitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Decoded Image",
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                )
                            }
                            bitmapCropped?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Decoded Image (Cropped)",
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                )
                            }
                        }

                        if (bitmap == null && error == null) {
                            CircularProgressIndicator()
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            if (memoryUsageBefore != null && memoryUsageAfter != null) {
                                Text(
                                    text = "WASM Heap: ${memoryUsageBefore?.wasmHeapSizeBytes} -> ${memoryUsageAfter?.wasmHeapSizeBytes}",
                                    modifier = Modifier.background(Color.White.copy(alpha = 0.7f))
                                )
                            }

                            size?.let { s ->
                                Text(
                                    text = "${s.width}:${s.height}",
                                    modifier = Modifier.background(Color.White.copy(alpha = 0.7f))
                                )
                            }

                            if (error != null) {
                                Text(
                                    text = "Error: $error",
                                    modifier = Modifier.background(Color.White.copy(alpha = 0.7f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ASSET_PATH_SAMPLE_IMAGE = "karin.jp2"
    }
}
