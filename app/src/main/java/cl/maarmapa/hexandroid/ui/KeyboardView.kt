package cl.maarmapa.hexandroid.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Vista del teclado de Hex para Android: el equivalente del HUD de Hex en el móvil.
 *
 * Estados: idle (hold-to-talk), grabando (pulso + cronómetro + cancelar al deslizar
 * hacia abajo), transcribiendo (spinner fino), resultado (chip con texto y tiempo) y
 * error (mensaje + reintentar). Opcionalmente un panel QWERTY compacto ([QwertyPanel]).
 *
 * La vista es pasiva respecto al servicio: solo invoca [HexKeyboardController], que
 * reenvía a [KeyboardCallbacks]. Sin trabajo fuera del hilo principal: todo el estado
 * vive en un StateFlow observado por Compose y las animaciones son compose-driven.
 */
@Composable
fun KeyboardView(controller: HexKeyboardController) {
    val scheme = MaterialTheme.colorScheme
    val state by controller.state.collectAsState()
    var qwertyEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(bottom = 4.dp),
    ) {
        val busy = state is HexKeyboardState.Recording || state is HexKeyboardState.Transcribing
        HeaderRow(
            qwertyEnabled = qwertyEnabled,
            busy = busy,
            onToggle = { qwertyEnabled = !qwertyEnabled },
        )

        when (val s = state) {
            is HexKeyboardState.Idle, is HexKeyboardState.Recording -> {
                // Misma posición de composición en idle y grabando: el gesto de hold
                // sobrevive a la transición de estado sin reiniciarse.
                DictationButton(
                    recording = s is HexKeyboardState.Recording,
                    startedAtMs = (s as? HexKeyboardState.Recording)?.startedAtMs ?: 0L,
                    controller = controller,
                )
                if (s is HexKeyboardState.Recording) {
                    Text(
                        text = "desliza hacia abajo para cancelar",
                        style = HexMono.copy(fontSize = 11.sp, color = scheme.onSurfaceVariant),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
            is HexKeyboardState.Transcribing -> TranscribingPill()
            is HexKeyboardState.Result -> ResultChip(s, controller)
            is HexKeyboardState.Error -> ErrorPane(s, controller)
        }

        AnimatedVisibility(visible = qwertyEnabled) {
            QwertyPanel(controller = controller)
        }
    }
}

@Composable
private fun HeaderRow(qwertyEnabled: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Hex",
            style = HexMono.copy(fontSize = 12.sp, color = scheme.onSurfaceVariant),
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggle, enabled = !busy) {
            Text(
                text = if (qwertyEnabled) "dictar" else "ABC",
                style = HexMono.copy(fontSize = 13.sp),
            )
        }
    }
}

/** Distancia vertical de arrastre que arma la cancelación por deslizamiento. */
private val CancelDragDistance = 88.dp

/** Pill grande de hold-to-talk; sirve para idle y grabando (mismo nodo de gesto). */
@Composable
private fun DictationButton(
    recording: Boolean,
    startedAtMs: Long,
    controller: HexKeyboardController,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    val active = pressed || recording

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(
                if (active) {
                    scheme.primary.copy(alpha = if (cancelArmed) 0.45f else 0.92f)
                } else {
                    scheme.surfaceVariant
                }
            )
            .border(
                width = 1.dp,
                color = if (active) scheme.primary else scheme.outline,
                shape = shape,
            )
            .pointerInput(controller) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    cancelArmed = false
                    controller.startCapture()

                    val cancelPx = CancelDragDistance.toPx()
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Cancel) {
                            pressed = false
                            cancelArmed = false
                            controller.cancelCapture()
                            break
                        }
                        dy += event.changes.maxOfOrNull { it.positionChange().y } ?: 0f
                        if (!cancelArmed && dy > cancelPx) cancelArmed = true
                        if (event.changes.none { it.pressed }) {
                            pressed = false
                            if (cancelArmed) controller.cancelCapture() else controller.finishCapture()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            RecordingContent(startedAtMs = startedAtMs)
        } else {
            Text(
                text = "mantén para dictar",
                style = HexMono.copy(
                    fontSize = 15.sp,
                    color = if (pressed) scheme.onPrimary else scheme.onSurface,
                ),
            )
        }
    }
}

/** Contenido visual durante la grabación: punto pulsante + cronómetro discreto. */
@Composable
private fun RecordingContent(startedAtMs: Long) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulso")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "alphaPunto",
    )
    val ringScale by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "escalaPunto",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                }
                .background(scheme.onPrimary.copy(alpha = dotAlpha), CircleShape),
        )
        Spacer(modifier = Modifier.width(14.dp))
        ElapsedClock(
            startedAtMs = startedAtMs,
            style = HexMono.copy(fontSize = 15.sp, color = scheme.onPrimary),
        )
    }
}

/** Cronómetro: actualiza solo el texto, cada 200 ms, sin tocar nada más del árbol. */
@Composable
private fun ElapsedClock(startedAtMs: Long, style: androidx.compose.ui.text.TextStyle) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            delay(200)
            now = SystemClock.elapsedRealtime()
        }
    }
    Text(text = formatDuration(now - startedAtMs), style = style)
}

/** Pill de transcripción: spinner fino + texto mono, no interactivo. */
@Composable
private fun TranscribingPill() {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(width = 1.dp, color = scheme.outline, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = scheme.primary,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "transcribiendo…",
                style = HexMono.copy(fontSize = 14.sp, color = scheme.onSurfaceVariant),
            )
        }
    }
}

/** Chip de resultado: texto mono + duración; "insertar" hace commit, "descartar" vuelve a idle. */
@Composable
private fun ResultChip(
    result: HexKeyboardState.Result,
    controller: HexKeyboardController,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { controller.commit(result.text) },
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = result.text,
                style = HexMono.copy(fontSize = 15.sp, color = scheme.onSurface),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(result.durationMs) + " de grabación",
                    style = HexMono.copy(fontSize = 11.sp, color = scheme.onSurfaceVariant),
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.showIdle() }) {
                    Text("descartar", style = HexMono.copy(fontSize = 13.sp))
                }
                TextButton(onClick = { controller.commit(result.text) }) {
                    Text("insertar", style = HexMono.copy(fontSize = 13.sp, color = scheme.primary))
                }
            }
        }
    }
}

/** Panel de error: mensaje mono en color de error + "reintentar" (regraba) o "descartar". */
@Composable
private fun ErrorPane(
    error: HexKeyboardState.Error,
    controller: HexKeyboardController,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = "error",
                style = HexMono.copy(fontSize = 11.sp, color = scheme.error),
            )
            Text(
                text = error.message,
                style = HexMono.copy(fontSize = 14.sp, color = scheme.onSurface),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller.startCapture() }) {
                    Text("reintentar", style = HexMono.copy(fontSize = 13.sp, color = scheme.primary))
                }
                TextButton(onClick = { controller.showIdle() }) {
                    Text("descartar", style = HexMono.copy(fontSize = 13.sp))
                }
            }
        }
    }
}

/**
 * Crea la vista de entrada que HexInputMethodService devolverá desde `onCreateInputView()`.
 * No toca el servicio: este conecta `controller.callbacks = ...` y reporta
 * `showResult()` / `showError()` cuando la transcripción termina.
 *
 * El contexto de un IME no trae LifecycleOwner, así que se instala uno propio para
 * que Compose pueda componer y disponer correctamente en cada attach/detach.
 */
fun createHexKeyboardView(context: Context, controller: HexKeyboardController): View {
    val composeView = ComposeView(context)
    val owner = KeyboardLifecycleOwner()
    ViewTreeLifecycleOwner.set(composeView, owner)
    composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
    )
    composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            owner.goResumed()
        }

        override fun onViewDetachedFromWindow(v: View) {
            // La estrategia de composición libera los recursos al desacoplarse;
            // el owner nunca pasa a DESTROYED para poder re-adjuntar (el teclado
            // se muestra y oculta muchas veces por sesión).
        }
    })
    composeView.setContent {
        HexTheme {
            KeyboardView(controller)
        }
    }
    return composeView
}

/** LifecycleOwner mínimo para vistas dentro de un InputMethodService. */
private class KeyboardLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = registry

    fun goResumed() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (registry.currentState != Lifecycle.State.RESUMED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
}
