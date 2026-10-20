package digital.hstech.jarvis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta inspirada no HUD do Homem de Ferro: azul "arc reactor" sobre fundo escuro.
val ArcBlue = Color(0xFF2FB4FF)
val ArcBlueSoft = Color(0xFF8FD8FF)
val NightBlue = Color(0xFF0A1A2F)
val NightBlueCard = Color(0xFF12263F)

private val JarvisDarkColors = darkColorScheme(
    primary = ArcBlue,
    onPrimary = NightBlue,
    secondary = ArcBlueSoft,
    background = NightBlue,
    onBackground = Color(0xFFE7F3FF),
    surface = NightBlueCard,
    onSurface = Color(0xFFE7F3FF),
    surfaceVariant = Color(0xFF1B3350),
    onSurfaceVariant = Color(0xFFBBD6F0),
)

private val JarvisLightColors = lightColorScheme(
    primary = Color(0xFF0E6BA8),
    secondary = Color(0xFF1C7FC2),
    background = Color(0xFFF4F8FC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) JarvisDarkColors else JarvisLightColors,
        typography = Typography(),
        content = content,
    )
}
