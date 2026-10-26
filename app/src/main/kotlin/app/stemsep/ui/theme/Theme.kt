package app.stemsep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StemSepColors = darkColorScheme(
    primary = Color(0xFFB9A7FF),
    onPrimary = Color(0xFF1D123D),
    primaryContainer = Color(0xFF3B2A70),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF7CD6C3),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1C4B43),
    onSecondaryContainer = Color(0xFFB8F3E5),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF222A35),
    onSurfaceVariant = Color(0xFFB8C0CC),
    outline = Color(0xFF47515F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun StemSepTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StemSepColors,
        typography = Typography(),
        content = content,
    )
}
