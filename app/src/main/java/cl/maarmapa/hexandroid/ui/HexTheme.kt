package cl.maarmapa.hexandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Acento único de Hex: naranja sobre negro carbón. */
val HexAccent = Color(0xFFE4572E)

private val HexDarkColors = darkColorScheme(
    primary = HexAccent,
    onPrimary = Color(0xFF1A0D07),
    secondary = Color(0xFF8A8A8F),
    onSecondary = Color(0xFF141416),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFE6E4E1),
    surface = Color(0xFF111113),
    onSurface = Color(0xFFE6E4E1),
    surfaceVariant = Color(0xFF1B1B1E),
    onSurfaceVariant = Color(0xFFA9A7A3),
    outline = Color(0xFF3A3A3E),
    error = Color(0xFFCF6679),
    onError = Color(0xFF2B0B10),
)

/** Tipografía mono para todo lo que es estado (estilo HUD de Hex). */
val HexMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

/** Tema oscuro sobrio del teclado de Hex: carbón, texto claro, un solo acento naranja. */
@Composable
fun HexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HexDarkColors,
        content = content,
    )
}
