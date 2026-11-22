package cl.maarmapa.hexandroid

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.Manifest
import androidx.core.content.ContextCompat
import cl.maarmapa.hexandroid.history.HistoryStore
import cl.maarmapa.hexandroid.settings.RewriteSettings
import cl.maarmapa.hexandroid.ui.KeyboardCallbacks
import cl.maarmapa.hexandroid.ui.createHexKeyboardView

/**
 * Hex for Android — IME de dictado on-device. Espejo de Hex (anomalyco/hex, MIT):
 * hold-to-talk -> captura local -> transcripción on-device -> commit en el campo con foco.
 * El audio nunca sale del dispositivo. La reescritura con IA es opcional (off por defecto).
 */
class HexInputMethodService : InputMethodService() {

    private lateinit var recorder: HexRecorder
    private lateinit var transcriber: Transcriber
    private lateinit var controller: cl.maarmapa.hexandroid.ui.HexKeyboardController
    private var input: InputConnection? = null
    private var pcmCapturado: ShortArray? = null

    override fun onCreate() {
        super.onCreate()
        recorder = HexRecorder(this)
        transcriber = Transcriber.create(this)
        controller = cl.maarmapa.hexandroid.ui.HexKeyboardController().apply {
            callbacks = object : KeyboardCallbacks {
                override fun onStartCapture() = startDictation()
                override fun onFinishCapture() = finishDictation()
                override fun onCancelCapture() = cancelar()
                override fun onCommit(text: String) { input?.commitText(text, 1) }
                override fun onDelete() { input?.deleteSurroundingText(1, 0) }
                override fun onEditorAction(action: Int) { input?.performEditorAction(action) }
            }
        }
        // el limpiador de historial corre al arrancar (retención 7 días, cap 10k)
        HistoryStore.limpiarAlArrancar(this)
    }

    override fun onCreateInputView(): View = createHexKeyboardView(applicationContext, controller)

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        input = currentInputConnection
    }

    private fun startDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            controller.showError("concede el micrófono en los ajustes de Hex")
            startActivity(android.content.Intent(this, cl.maarmapa.hexandroid.settings.SettingsActivity::class.java))
            return
        }
        controller.startedAt = System.currentTimeMillis()
        recorder.start()
        controller.startCapture()
    }

    private fun finishDictation() {
        val pcm = recorder.stop() ?: run { controller.showIdle(); return }
        controller.transcribing()
        thread {
            try {
                val crudo = transcriber.transcribe(pcm, recorder.sampleRate)
                val rewrite = cl.maarmapa.hexandroid.HexRewriteClient.fromPrefs(this)
                val final = if (rewrite.disponible && RewriteSettings.reescrituraActivada(this))
                                rewrite.rewrite(crudo, appDeFondo()) else crudo
                HistoryStore.registrar(this, textoCrudo = crudo, textoFinal = final,
                                       appFondo = appDeFondo(), duracionMs = duracion())
                input?.commitText(final, 1)
                controller.showResult(final)
            } catch (e: Exception) {
                controller.showError("fallo la transcripción: ${e.message?.take(60)}")
            }
        }
    }

    private fun cancelar() {
        recorder.stop() // descarta el PCM capturado
        controller.showIdle()
    }

    private fun appDeFondo(): String = "" // sprint 2: app de fondo vía UsageStats
    private fun duracion(): Long = System.currentTimeMillis() - (controller.startedAtMs ?: 0L)
}
