package cl.maarmapa.hexandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

private val Row1 = "qwertyuiop"
private val Row2 = "asdfghjklñ"
private val Row3 = "zxcvbnm"

/**
 * Panel QWERTY compacto opcional (español latino con ñ). Las teclas no tocan
 * InputConnection: emiten intenciones por [HexKeyboardController], que reenvía a
 * [KeyboardCallbacks] (commitText / deleteSurroundingText / performEditorAction).
 *
 * Touch targets grandes: filas de 44 dp con separación, teclas laterales más anchas.
 * El backspace repite al mantener presionado.
 */
@Composable
fun QwertyPanel(
    controller: HexKeyboardController,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Fila 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row1.forEach { c ->
                LetterKey(
                    label = c,
                    shift = shift,
                    onShiftDone = { shift = false },
                    onClick = { controller.commit(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Fila 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row2.forEach { c ->
                LetterKey(
                    label = c,
                    shift = shift,
                    onShiftDone = { shift = false },
                    onClick = { controller.commit(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Fila 3: shift + letras + backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ActionKey(
                label = "⇧",
                active = shift,
                onClick = { shift = !shift },
                modifier = Modifier.weight(1.4f),
            )
            Row3.forEach { c ->
                LetterKey(
                    label = c,
                    shift = shift,
                    onShiftDone = { shift = false },
                    onClick = { controller.commit(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            BackspaceKey(
                controller = controller,
                modifier = Modifier.weight(1.4f),
            )
        }
        // Fila 4: coma, espacio, punto, enter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ActionKey(
                label = ",",
                onClick = { controller.commit(",") },
                modifier = Modifier.weight(1f),
            )
            ActionKey(
                label = "",
                onClick = { controller.commit(" ") },
                modifier = Modifier.weight(4f),
            )
            ActionKey(
                label = ".",
                onClick = { controller.commit(".") },
                modifier = Modifier.weight(1f),
            )
            ActionKey(
                label = "⏎",
                onClick = { controller.editorAction() },
                modifier = Modifier.weight(1.6f),
                active = true,
            )
        }
    }
}

/** Tecla de letra: respeta el estado de shift y lo apaga tras escribir una letra. */
@Composable
private fun LetterKey(
    label: Char,
    shift: Boolean,
    onShiftDone: () -> Unit,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (shift) label.uppercaseChar().toString() else label.toString()
    KeyBox(
        label = text,
        onClick = {
            onClick(text)
            if (shift) onShiftDone()
        },
        modifier = modifier,
    )
}

/** Tecla de acción (shift, espacio, puntuación, enter). */
@Composable
private fun ActionKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyBox(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Backspace con repetición al mantener presionado (350 ms inicial, 55 ms luego). */
@Composable
private fun BackspaceKey(
    controller: HexKeyboardController,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) scheme.outline else scheme.surface)
            .pointerInput(controller) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    controller.delete()
                    var intervalMs = 350L
                    while (true) {
                        val event = withTimeoutOrNull(intervalMs) { awaitPointerEvent() }
                        if (event == null) {
                            controller.delete()
                            intervalMs = 55L
                            continue
                        }
                        if (event.type == PointerEventType.Cancel ||
                            event.changes.none { it.pressed }
                        ) {
                            break
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⌫",
            style = HexMono.copy(fontSize = 16.sp, color = scheme.onSurface),
        )
    }
}

/** Cuerpo común de las teclas: fondo, esquina redondeada y feedback de presión. */
@Composable
private fun KeyBox(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        active -> scheme.primary.copy(alpha = 0.25f)
        pressed -> scheme.outline
        else -> scheme.surface
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HexMono.copy(
                fontSize = 16.sp,
                color = if (active) scheme.primary else scheme.onSurface,
            ),
            textAlign = TextAlign.Center,
        )
    }
}
