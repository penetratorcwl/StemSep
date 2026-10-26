package app.stemsep.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

enum class ThemePalette(
    val label: String,
    val previewColors: List<Color>,
) {
    MATERIAL_YOU(
        label = "Material You",
        previewColors = listOf(Color(0xFFB9A7FF), Color(0xFF7CD6C3), Color(0xFFFFB4C8)),
    ),
    VIOLET(
        label = "Violet",
        previewColors = listOf(Color(0xFFC9B8FF), Color(0xFF8B6CFF), Color(0xFF44307A)),
    ),
    TEAL(
        label = "Teal",
        previewColors = listOf(Color(0xFF6FEFD9), Color(0xFF00A896), Color(0xFF1C4B43)),
    ),
    GRAPHITE(
        label = "Graphite",
        previewColors = listOf(Color(0xFFD6D6DE), Color(0xFF6E6E78), Color(0xFF1D1E24)),
    ),
    ;

    companion object {
        fun fromId(id: String?): ThemePalette = entries.firstOrNull { it.name == id } ?: MATERIAL_YOU
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.MATERIAL_YOU,
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFC9B8FF),
    onPrimary = Color(0xFF2A165F),
    primaryContainer = Color(0xFF44307A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF7CD6C3),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1C4B43),
    onSecondaryContainer = Color(0xFFB8F3E5),
    tertiary = Color(0xFFFFB4C8),
    onTertiary = Color(0xFF5E1130),
    tertiaryContainer = Color(0xFF7B2947),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF252A34),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF4A4E5A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val VioletLight = lightColorScheme(
    primary = Color(0xFF5B3FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF006B5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF74F8E2),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8A4A5F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3B071D),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val TealDark = darkColorScheme(
    primary = Color(0xFF6FEFD9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF8CF8E4),
    secondary = Color(0xFFB0CCC4),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFA9C7FF),
    onTertiary = Color(0xFF00325B),
    tertiaryContainer = Color(0xFF00497F),
    onTertiaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF07110F),
    onBackground = Color(0xFFDDE5E2),
    surface = Color(0xFF0D1917),
    onSurface = Color(0xFFDDE5E2),
    surfaceVariant = Color(0xFF1B2A27),
    onSurfaceVariant = Color(0xFFB8CCC7),
    outline = Color(0xFF3F4A47),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val TealLight = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF74F8E2),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF3D5F90),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF001C39),
    background = Color(0xFFF4FBF8),
    onBackground = Color(0xFF161D1B),
    surface = Color(0xFFF4FBF8),
    onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val GraphiteDark = darkColorScheme(
    primary = Color(0xFFCFCDD6),
    onPrimary = Color(0xFF1A1A1F),
    primaryContainer = Color(0xFF3A3A42),
    onPrimaryContainer = Color(0xFFE5E1E9),
    secondary = Color(0xFFB8B7C0),
    onSecondary = Color(0xFF2B2B32),
    secondaryContainer = Color(0xFF43434B),
    onSecondaryContainer = Color(0xFFD4D2DB),
    tertiary = Color(0xFFC7C5CE),
    onTertiary = Color(0xFF1E1E24),
    tertiaryContainer = Color(0xFF47464E),
    onTertiaryContainer = Color(0xFFE3E1E9),
    background = Color(0xFF08090B),
    onBackground = Color(0xFFE6E5EA),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFE6E5EA),
    surfaceVariant = Color(0xFF1E1F24),
    onSurfaceVariant = Color(0xFFC6C5CE),
    outline = Color(0xFF4B4B54),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val GraphiteLight = lightColorScheme(
    primary = Color(0xFF5F5E66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E1E9),
    onPrimaryContainer = Color(0xFF1B1B21),
    secondary = Color(0xFF5E5D65),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E1E9),
    onSecondaryContainer = Color(0xFF1B1B21),
    tertiary = Color(0xFF5F5D71),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E0F7),
    onTertiaryContainer = Color(0xFF1C1A2B),
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF9FC),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE4E1E9),
    onSurfaceVariant = Color(0xFF47464E),
    outline = Color(0xFF787680),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun StemSepTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themePalette: ThemePalette = ThemePalette.MATERIAL_YOU,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        themePalette == ThemePalette.MATERIAL_YOU && dynamicSupported ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        themePalette == ThemePalette.TEAL ->
            if (darkTheme) TealDark else TealLight
        themePalette == ThemePalette.GRAPHITE ->
            if (darkTheme) GraphiteDark else GraphiteLight
        else ->
            if (darkTheme) VioletDark else VioletLight
    }

    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            val window = activity.window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
