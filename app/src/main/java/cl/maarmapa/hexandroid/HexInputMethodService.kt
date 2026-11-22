package cl.maarmapa.hexandroid

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Hex for Android — IME de dictado on-device.
 * Arquitectura espejo de Hex (anomalyco/hex, MIT):
 *   hold-to-talk -> captura local -> transcripción on-device -> commit en el campo con foco.
 * El audio nunca sale del dispositivo. La reescritura con OpenCode es opcional (off por defecto).
 */
class HexInputMethodService : InputMethodService() {

    private lateinit var recorder: HexRecorder
    private lateinit var transcriber: Transcriber
    private var input: InputConnection? = null

    override fun onCreate() {
        super.onCreate()
        recorder = HexRecorder(this)
        transcriber = Transcriber.create(this) // Parakeet ONNX por defecto; Whisper TFLite como alternativa
    }

    override fun onCreateInputView(): View {
        // MVP: vista mínima con un botón hold-to-talk (hold = grabar, release = transcribir + commit)
        val view = android.widget.Button(this).apply {
            text = "🎙 mantén para dictar"
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> startDictation()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> finishDictation()
                }
                true
            }
        }
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        input = currentInputConnection
    }

    private fun startDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pedirMicro()
            return
        }
        recorder.start()
    }

    private fun finishDictation() {
        val pcm = recorder.stop() ?: return
        // 1) transcripción local (el audio no sale del teléfono)
        val texto = transcriber.transcribe(pcm, recorder.sampleRate)
        // 2) commit en el campo con foco (el equivalente del paste de Hex)
        input?.commitText(texto, 1)
        // 3) opcional: HexRewriteClient.rewrite(texto, contexto) — off por defecto
    }

    private fun pedirMicro() {
        // MVP: el permiso se concede desde la app principal (SettingsActivity); en producción, flujo propio
        ContextCompat.startForegroundService(this, android.content.Intent(this, SettingsActivity::class.java))
    }
}
