package cl.maarmapa.hexandroid.ui

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estados visuales de la vista del teclado de Hex. */
sealed interface HexKeyboardState {
    data object Idle : HexKeyboardState
    data class Recording(val startedAtMs: Long) : HexKeyboardState
    data object Transcribing : HexKeyboardState
    data class Result(val text: String, val durationMs: Long) : HexKeyboardState
    data class Error(val message: String) : HexKeyboardState
}

/**
 * Único dueño del estado visual de [KeyboardView], y fachada hacia [KeyboardCallbacks].
 *
 * Hilo-seguro: el servicio puede llamar desde cualquier hilo (los cambios de estado
 * viajan por [MutableStateFlow] y Compose los observa); los callbacks se invocan de
 * forma sincrónica en el hilo del llamador.
 *
 * Flujo esperado con el servicio conectado:
 * ```
 * controller.callbacks = object : KeyboardCallbacks { ... recorder/transcriber ... }
 * // en la respuesta de la transcripción:
 * controller.showResult(texto)   // o controller.showError(mensaje)
 * ```
 * Las transiciones de gesto (idle -> grabando -> transcribiendo / cancelar) las hace
 * la vista a través de este controlador; el servicio solo reporta resultado o error.
 */
class HexKeyboardController {

    /** Conexión con el servicio; `null` mientras no esté integrado. */
    var callbacks: KeyboardCallbacks? = null

    private val _state = MutableStateFlow<HexKeyboardState>(HexKeyboardState.Idle)
    val state: StateFlow<HexKeyboardState> = _state.asStateFlow()

    private var captureStartedAtMs: Long = 0L
    private var lastCaptureDurationMs: Long = 0L

    /** Pulsación del hold-to-talk (o "reintentar" tras un error). */
    fun startCapture() {
        val current = _state.value
        if (current !is HexKeyboardState.Idle && current !is HexKeyboardState.Error) return
        captureStartedAtMs = SystemClock.elapsedRealtime()
        _state.value = HexKeyboardState.Recording(captureStartedAtMs)
        callbacks?.onStartCapture()
    }

    /** Soltar el hold-to-talk: pasa a transcribiendo y avisa al servicio. */
    fun finishCapture() {
        if (_state.value !is HexKeyboardState.Recording) return
        lastCaptureDurationMs = (SystemClock.elapsedRealtime() - captureStartedAtMs).coerceAtLeast(0L)
        _state.value = HexKeyboardState.Transcribing
        callbacks?.onFinishCapture()
    }

    /** Deslizar hacia abajo durante la grabación: descarta. */
    fun cancelCapture() {
        if (_state.value !is HexKeyboardState.Recording) return
        _state.value = HexKeyboardState.Idle
        callbacks?.onCancelCapture()
    }

    /** Insertar texto en el campo con foco (chip de resultado o teclas del panel). */
    fun commit(text: String) {
        if (text.isEmpty()) {
            _state.value = HexKeyboardState.Idle
            return
        }
        callbacks?.onCommit(text)
        _state.value = HexKeyboardState.Idle
    }

    fun delete() {
        callbacks?.onDelete()
    }

    fun editorAction() {
        callbacks?.onEditorAction()
    }

    /** El servicio reporta la transcripción lista; muestra el chip con texto y duración. */
    fun showResult(text: String) {
        _state.value = HexKeyboardState.Result(text, lastCaptureDurationMs)
    }

    /** El servicio reporta un fallo (sin permiso, modelo, grabadora...). */
    fun showError(message: String) {
        _state.value = HexKeyboardState.Error(message)
    }

    fun showIdle() {
        _state.value = HexKeyboardState.Idle
    }
}

/** "0:07" con formato estable e independiente del locale del dispositivo. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(
        java.util.Locale.US,
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60,
    )
}
