package com.raah360.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.raah360.AppViewModel
import com.raah360.core.VenueEditor
import java.io.File

@Composable
fun MarkersScreen(vm: AppViewModel) {
    val venue by vm.venue.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Landmarks", "What the camera recognises to locate you", onBack = { vm.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Print the Raah360 markers at 18 cm wide, tape each one at its planned spot, then open Mapper and tap " +
                        "“Save pose” when the camera recognises it. You can also register real signs/posters as photo landmarks in Mapper.",
                    color = R360.Muted, style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = {
                    val out = File(ctx.cacheDir, "exports").apply { mkdirs() }
                    val f = File(out, "raah360_markers.pdf")
                    ctx.assets.open("markers/raah360_markers.pdf").use { input -> f.outputStream().use { input.copyTo(it) } }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Print / share markers"))
                }, modifier = Modifier.padding(vertical = 10.dp)) { Text("Share printable markers (PDF)") }
            }
            items(venue.landmarks, key = { it.id }) { l ->
                val bmp = remember(l.image, venue.revision) { vm.repo.loadBitmap(venue.id, l.image, 256) }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(bmp, l.name, 64.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = R360.Text, style = MaterialTheme.typography.titleMedium)
                        if (l.hint.isNotBlank()) Text(l.hint, color = R360.Muted, style = MaterialTheme.typography.bodyMedium)
                        Pill(
                            if (l.located) "Measured on site" else "Planned position (not measured yet)",
                            if (l.located) R360.Info else R360.Warn, Modifier.padding(top = 4.dp)
                        )
                    }
                    if (l.image?.startsWith("asset:") == false) {
                        IconButton(onClick = { vm.updateVenue(VenueEditor.removeLandmark(venue, l.id)) }) {
                            Icon(Icons.Default.Delete, "Delete", tint = R360.Muted)
                        }
                    }
                }
            }
        }
    }
}
