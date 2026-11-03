package app.podfamily.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podfamily.ui.components.Card
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.theme.PodColors

/** One third-party component and its license. [file] is a bundled license text under assets/licenses/. */
private data class Notice(val name: String, val use: String, val license: String, val url: String, val file: String? = null)

private val NOTICES = listOf(
    Notice("Google Tink", "Encryption", "Apache License 2.0", "https://github.com/tink-crypto/tink-java", "Apache-2.0.txt"),
    Notice("MapLibre Native", "Map drawing", "BSD 2-Clause", "https://github.com/maplibre/maplibre-native", "MapLibre-BSD-2-Clause.txt"),
    Notice("OkHttp", "Networking", "Apache License 2.0", "https://github.com/square/okhttp", "Apache-2.0.txt"),
    Notice("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Language and libraries", "Apache License 2.0", "https://github.com/JetBrains/kotlin", "Apache-2.0.txt"),
    Notice("Android Jetpack (AndroidX, Compose, WorkManager)", "App framework and screens", "Apache License 2.0", "https://developer.android.com/jetpack", "Apache-2.0.txt"),
    Notice("ZXing and zxing-android-embedded", "QR codes", "Apache License 2.0", "https://github.com/journeyapps/zxing-android-embedded", "Apache-2.0.txt"),
    Notice("Google Play services Location", "Battery-friendly location", "Google APIs Terms of Service (not open source)", "https://developers.google.com/terms"),
    Notice("OpenStreetMap data", "Map data, via Protomaps", "© OpenStreetMap contributors, ODbL 1.0", "https://www.openstreetmap.org/copyright"),
    Notice("Protomaps basemaps", "Map styles and tiles", "BSD 3-Clause (styles); data © OpenStreetMap", "https://github.com/protomaps/basemaps"),
    Notice("Noto Sans", "Map label font", "SIL Open Font License 1.1", "https://fonts.google.com/noto"),
)

/**
 * "Open-source licenses": credits every third-party component and shows the full license
 * texts the licenses ask to be included. Drawn in the main window (see CLAUDE.md UI rules).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    BackHandler { if (open != null) open = null else onBack() }

    open?.let { file ->
        val text = remember(file) {
            runCatching { ctx.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
                .getOrDefault("License text not found.")
        }
        Column(Modifier.fillMaxSize().background(PodColors.Background).systemBarsPadding()) {
            PodTopBar(file.removeSuffix(".txt"), onBack = { open = null })
            Text(
                text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PodColors.TextDim,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(PodColors.Background).systemBarsPadding()) {
        PodTopBar("Open-source licenses", onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Hint("Pod is built on these projects. Thank you to everyone who makes them.") }
            items(NOTICES) { n ->
                Card(Modifier.fillMaxWidth().let { if (n.file != null) it.clickable { open = n.file } else it }) {
                    Text(n.name, style = MaterialTheme.typography.titleMedium)
                    Text("${n.use} · ${n.license}", style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim)
                    Text(n.url, style = MaterialTheme.typography.bodySmall, color = PodColors.Accent)
                    if (n.file != null) Text("Tap to read the license", style = MaterialTheme.typography.bodySmall, color = PodColors.TextDim)
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp)) }
        }
    }
}
