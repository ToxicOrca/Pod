package app.podfamily.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Pod is dark-only by design. There is no light scheme and no dynamic
 * (wallpaper) color, so the app looks identical on every phone.
 */
object PodColors {
    val Background = Color(0xFF0E1013)
    val Surface = Color(0xFF171A1F)
    val SurfaceHigh = Color(0xFF20242B)
    val SurfaceHighest = Color(0xFF2A2F37)
    val Outline = Color(0xFF3A404A)
    val Text = Color(0xFFE8EAED)
    val TextDim = Color(0xFF9AA3AE)
    val Accent = Color(0xFF5EC8F2)
    val AccentDim = Color(0xFF0B2A3A)
    val Mint = Color(0xFF8BE3C9)
    val Good = Color(0xFF6FD08C)
    val Warn = Color(0xFFF2B35E)
    val Danger = Color(0xFFFF5A5F)
}

private val scheme = darkColorScheme(
    primary = PodColors.Accent,
    onPrimary = Color(0xFF00151F),
    primaryContainer = PodColors.AccentDim,
    onPrimaryContainer = PodColors.Accent,
    secondary = PodColors.Mint,
    onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF15332C),
    onSecondaryContainer = PodColors.Mint,
    background = PodColors.Background,
    onBackground = PodColors.Text,
    surface = PodColors.Surface,
    onSurface = PodColors.Text,
    surfaceVariant = PodColors.SurfaceHigh,
    onSurfaceVariant = PodColors.TextDim,
    surfaceContainerLowest = PodColors.Background,
    surfaceContainerLow = PodColors.Surface,
    surfaceContainer = PodColors.Surface,
    surfaceContainerHigh = PodColors.SurfaceHigh,
    surfaceContainerHighest = PodColors.SurfaceHighest,
    outline = PodColors.Outline,
    outlineVariant = Color(0xFF2A2F37),
    error = PodColors.Danger,
    onError = Color.White,
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFB3B5),
    inverseSurface = PodColors.Text,
    inverseOnSurface = PodColors.Background,
    scrim = Color.Black,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
