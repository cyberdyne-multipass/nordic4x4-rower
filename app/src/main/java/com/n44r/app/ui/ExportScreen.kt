package com.n44r.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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

/** Step 2 after a workout: the share card (square) next to the share buttons. */
@Composable
fun ExportScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val image = remember { vm.exportedImage() }

    Row(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bmp = remember(image) { image?.let { decodeScaled(it, 1080) } }
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), contentDescription = "Trainingsbild",
                modifier = Modifier.fillMaxHeight().aspectRatio(1f), contentScale = ContentScale.Fit
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Export", style = MaterialTheme.typography.headlineMedium)
            if (image != null) {
                Button(onClick = {
                    val uri = vm.shareUri(context, image)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Bild teilen"))
                }, modifier = Modifier.width(260.dp)) { Text("Bild teilen") }
            }
            OutlinedButton(onClick = {
                val uris = ArrayList(vm.shareUris(context))
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Alle Dateien teilen"))
            }, modifier = Modifier.width(260.dp)) { Text("Alles teilen (inkl. FIT + Log)") }
            OutlinedButton(onClick = { vm.startOver() }, modifier = Modifier.width(260.dp)) { Text("Neues Training") }
        }
    }
}

/** Decodes a PNG downsampled to roughly the given max edge. */
private fun decodeScaled(file: File, maxEdge: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
