package app.podfamily.ui.invite

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import app.podfamily.data.PodRepository
import app.podfamily.ui.components.Banner
import app.podfamily.ui.components.Hint
import app.podfamily.ui.components.PodTopBar
import app.podfamily.ui.components.PrimaryButton
import app.podfamily.ui.theme.PodColors
import kotlinx.coroutines.delay

private const val INVITE_TTL_SECONDS = 30 * 60L

@Composable
fun InviteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PodRepository.get(ctx) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    var generation by remember { mutableIntStateOf(0) }
    val state by repo.state.collectAsStateWithLifecycle()
    val memberCountAtStart = remember { repo.state.value.members.size }

    LaunchedEffect(generation) {
        qr = null; error = null
        repo.createInvite(INVITE_TTL_SECONDS)
            .onSuccess { link ->
                qr = qrBitmap(link.toUri(), 720)
                expiresAt = System.currentTimeMillis() + INVITE_TTL_SECONDS * 1000
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(expiresAt) {
        while (expiresAt > 0) {
            remaining = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (remaining == 0L) break
            delay(1000)
        }
    }
    val joined = state.members.size > memberCountAtStart
    // Tab 0: join (one-time invite). Tab 1: download the app (a permanent, harmless link).
    var tab by remember { mutableIntStateOf(0) }
    val downloadUrl = "${state.family?.serverUrl ?: ""}/download"
    val downloadQr = remember(downloadUrl) { qrBitmap(downloadUrl, 720) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PodTopBar("Invite to family", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = PodColors.Background) {
                androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Join family") })
                androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Get the app") })
            }
            if (tab == 1) {
                Hint("Step 1 for someone new: they scan this with their phone's camera, download Pod and install it (Android will ask them to allow installing apps). Then switch to \"Join family\".", Modifier.fillMaxWidth())
                Box(
                    Modifier.size(300.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(24.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { Image(downloadQr.asImageBitmap(), "Download Pod QR code", Modifier.fillMaxSize()) }
                Text(downloadUrl.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim)
                Banner("This code only downloads the app. It can't add anyone to your family, so it's fine to share.", PodColors.Good)
            }
            if (tab == 0) {
            Hint("On their phone: install Pod, tap \"Join my family\", then scan this code.", Modifier.fillMaxWidth())
            Box(
                Modifier.size(300.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(24.dp)).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qr
                when {
                    bmp != null && remaining > 0 -> Image(bmp.asImageBitmap(), "Invite QR code", Modifier.fillMaxSize())
                    error != null -> Text(error ?: "", color = PodColors.Danger, textAlign = TextAlign.Center)
                    bmp != null -> Text("Expired", color = androidx.compose.ui.graphics.Color.Black)
                    else -> CircularProgressIndicator()
                }
            }
            if (remaining > 0) {
                Text("Valid for ${remaining / 60}:${"%02d".format(remaining % 60)} · works once", style = MaterialTheme.typography.bodyMedium, color = PodColors.TextDim)
            }
            if (joined) Banner("Someone just joined. Their phone gets the family key automatically.", PodColors.Good)
            Banner("Only show this code in person. Anyone who scans it can join your family.", PodColors.Warn)
            Spacer(Modifier.height(4.dp))
            PrimaryButton("New code", onClick = { generation++ })
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
