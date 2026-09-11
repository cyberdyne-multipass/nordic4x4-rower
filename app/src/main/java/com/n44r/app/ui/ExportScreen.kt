package com.n44r.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.n44r.app.MainViewModel
import java.io.File

/** Step 2 after a workout: the three share cards side by side, each shareable on its own. */
@Composable
fun ExportScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val images = remember { vm.exportedImages() }
    val labels = listOf("Quadrat", "Querformat", "Hochformat")

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Export", style = MaterialTheme.typography.headlineMedium)
        Text("Tippe auf Teilen unter dem gewünschten Format", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            images.forEachIndexed { i, file ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(file) { decodeScaled(file, 900) }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), contentDescription = labels.getOrNull(i),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(labels.getOrNull(i) ?: file.name, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        val uri = vm.shareUri(context, file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Bild teilen"))
                    }) { Text("Teilen") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = {
                val uris = ArrayList(vm.shareUris(context))
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Alle Dateien teilen"))
            }) { Text("Alles teilen (inkl. FIT)") }
            Button(onClick = { vm.startOver() }) { Text("Neues Training") }
        }
    }
}

/** Decodes a PNG downsampled to roughly the given max edge - the cards are up to 1920 px. */
private fun decodeScaled(file: File, maxEdge: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
