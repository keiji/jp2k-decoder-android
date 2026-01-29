package dev.keiji.j2k_wasm_sample

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            J2kwasmsampleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
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
                                    val usageBefore = decoder.getMemoryUsage()
                                    val imageSize = decoder.getSize(bytes)
                                    val bmp = decoder.decodeImage(bytes)
                                    val usageAfter = decoder.getMemoryUsage()
                                    object {
                                        val bmp = bmp
                                        val usageBefore = usageBefore
                                        val usageAfter = usageAfter
                                        val size = imageSize
                                    }
                                }
                            }
                            bitmap = result.bmp
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
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = "Decoded Image",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (memoryUsageBefore != null && memoryUsageAfter != null) {
                            Text(
                                text = "WASM Heap: ${memoryUsageBefore?.wasmHeapSizeBytes} -> ${memoryUsageAfter?.wasmHeapSizeBytes}",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(Color.White.copy(alpha = 0.7f))
                                    .padding(8.dp)
                            )
                        }

                        if (size != null) {
                            Text(
                                text = "${size?.width}:${size?.height}",
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.White.copy(alpha = 0.7f))
                                    .padding(8.dp)
                            )
                        }

                        if (error != null) {
                            Text("Error: $error")
                        } else if (bitmap == null) {
                            CircularProgressIndicator()
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
