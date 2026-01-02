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
import dev.keiji.jp2k.Jp2kDecoder
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
                    var error by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        try {
                            val decodedBitmap = withContext(Dispatchers.IO) {
                                Jp2kDecoder().use { decoder ->
                                    decoder.init(applicationContext)
                                    applicationContext.assets.open(ASSET_PATH_SAMPLE_IMAGE).use {
                                        val bytes = it.readBytes()
                                        decoder.decodeImage(bytes)
                                    }
                                }
                            }
                            bitmap = decodedBitmap
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
                        } else if (error != null) {
                            Text("Error: $error")
                        } else {
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
