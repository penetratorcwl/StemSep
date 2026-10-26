package app.stemsep.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stemsep.R
import app.stemsep.ui.theme.AppSettings
import kotlinx.coroutines.delay

@Composable
fun StemSepRoot(
    viewModel: MainViewModel,
    settings: AppSettings,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLaunch by rememberSaveable { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = showSettings,
            animationSpec = tween(260),
            label = "screen",
        ) { settingsVisible ->
            if (settingsVisible) {
                SettingsScreen(
                    settings = settings,
                    onBack = { showSettings = false },
                    onThemeMode = viewModel::setThemeMode,
                    onThemePalette = viewModel::setThemePalette,
                )
            } else {
                StemSepScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
        if (showLaunch) {
            LaunchReveal(onFinished = { showLaunch = false })
        }
    }
}

@Composable
private fun LaunchReveal(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        started = true
        delay(900)
        onFinished()
    }
    val scale by animateFloatAsState(
        targetValue = if (started) 16f else 1f,
        animationSpec = tween(850, easing = FastOutSlowInEasing),
        label = "launchScale",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (started) 0f else 1f,
        animationSpec = tween(650, delayMillis = 150),
        label = "launchAlpha",
    )
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (started) 0f else 1f,
        animationSpec = tween(700, delayMillis = 120),
        label = "launchBackground",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = backgroundAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = iconAlpha
                },
        )
    }
}
