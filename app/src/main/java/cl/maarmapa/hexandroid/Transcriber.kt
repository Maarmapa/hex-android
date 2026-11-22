package cl.maarmapa.hexandroid

import android.content.Context

/**
 * Transcripción on-device. Modelos locales: Parakeet (ONNX, multilenguaje, es incluido)
 * y Whisper (TFLite/whisper.cpp) como alternativa. Nada va a la nube.
 * Contrato espejo del motor de Hex: PCM 16k mono -> texto.
 */
interface Transcriber { fun transcribe(pcm: ShortArray, sampleRate: Int): String

    companion object {
        fun create(context: Context): Transcriber = ParakeetOnnxTranscriber(context)
    }
}

/** Parakeet TDT/CTC v2 multilenguaje vía ONNX Runtime (equivalente al modelo local de Hex). */
class ParakeetOnnxTranscriber(private val context: Context) : Transcriber {
    private val modelFile: java.io.File by lazy {
        // MVP: el modelo se descarga la primera vez (igual que el onboarding de Hex)
        val f = java.io.File(context.filesDir, "parakeet-v2.onnx")
        if (!f.exists()) DescargadorModelo.descargar(context, MODELO_URL, f)
        f
    }

    override fun transcribe(pcm: ShortArray, sampleRate: Int): String {
        // TODO (sprint 1): normalizar PCM -> float, features de mel, sesión ONNX, decodificador CTC greedy
        return ""
    }

    companion object {
        // MVP: enlaza al modelo publicado por la comunidad (Parakeet v2 int8, ~120 MB)
        const val MODELO_URL = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2/resolve/main/onnx/model.onnx"
    }
}

object DescargadorModelo {
    fun descargar(context: Context, url: String, destino: java.io.File) {
        // MVP: HttpURLConnection con progreso y verificación de tamaño; reintentos como el onboarding de Hex
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connect()
        destino.outputStream.use { out -> conn.inputStream.copyTo(out) }
    }
}

/** Alternativa: whisper.cpp vía JNI (o TFLite). Reservado como opción de modelo. */
class WhisperTfliteTranscriber(private val context: Context) : Transcriber {
    override fun transcribe(pcm: ShortArray, sampleRate: Int): String = "" // stub sprint 2
}
