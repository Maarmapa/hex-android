package cl.maarmapa.hexandroid

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transcripción on-device. Contrato espejo del motor de Hex: PCM 16k mono -> texto.
 * El audio nunca sale del dispositivo: ninguna llamada de red transporta audio;
 * la única red usada es la descarga de los archivos del modelo (ver DescargadorModelo).
 */
interface Transcriber {
    fun transcribe(pcm: ShortArray, sampleRate: Int): String

    companion object {
        fun create(context: Context): Transcriber = ParakeetOnnxTranscriber(context)
    }
}

// ---------------------------------------------------------------------------
// Piezas puras de Kotlin (sin dependencias de Android): testeables en JVM.
// ---------------------------------------------------------------------------

/** Conversión PCM 16 bits -> float [-1, 1]. */
object NormalizadorPcm {
    /** Tasa de muestreo esperada por todo el pipeline de Hex. */
    const val MUESTREO_ESPERADO = 16_000

    /** Escala muestras int16 a float en [-1, 1] (división por 32768). */
    fun pcm16AFloat(pcm: ShortArray): FloatArray {
        val salida = FloatArray(pcm.size)
        for (i in salida.indices) salida[i] = pcm[i] / 32768f
        return salida
    }
}

/** FFT radix-2 iterativa de tamaño fijo 512 (Kotlin puro). */
internal object Fft512 {
    const val N = 512

    private val cosTable = FloatArray(N / 2) { cos(2.0 * Math.PI * it / N).toFloat() }
    private val sinTable = FloatArray(N / 2) { sin(2.0 * Math.PI * it / N).toFloat() }
    private val bitReverso = IntArray(N) { i ->
        var x = i
        var r = 0
        repeat(9) { r = (r shl 1) or (x and 1); x = x shr 1 }
        r
    }

    /** Transforma in-place los arreglos re e im (longitud 512). Convención e^{-i2πkt/N}. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) {
            val j = bitReverso[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val mitad = size / 2
            val paso = N / size
            var inicio = 0
            while (inicio < N) {
                var k = 0
                for (j in inicio until inicio + mitad) {
                    val l = j + mitad
                    val tre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tim = im[l] * cosTable[k] - re[l] * sinTable[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += paso
                }
                inicio += size
            }
            size = size shl 1
        }
    }
}

/**
 * Extracción de características log-mel para Parakeet TDT 0.6B v2, réplica en Kotlin
 * del preprocesador NeMo del export ONNX (verificado en el código de referencia
 * onnx-asr: preprocessors/nemo.py y preprocessors/fbanks.py; ver docs/TRANSCRIBER.md).
 *
 * IMPORTANTE: este modelo usa 128 bins mel con n_fft=512 (config.json del export
 * declara features_size=128). La ventana sí es de 25 ms (400 muestras) con hop de
 * 10 ms (160 muestras). No son los 80 bins de la especificación original de la
 * tarea; 128 es lo que exige el modelo real.
 */
object MelEspectrograma {
    const val MUESTREO = 16_000
    const val N_FFT = 512          // tamaño de transformada (el audio se rellena con 256 ceros a cada lado)
    const val VENTANA = 400        // 25 ms
    const val HOP = 160            // 10 ms
    const val N_MELS = 128         // features_size del modelo (verificado en config.json del export)
    const val PREENFASIS = 0.97f
    private val GUARDIA_LOG = 2.0f.pow(-24)   // log_zero_guard_value = 2**-24
    private const val EPS_NORMALIZACION = 1e-5f

    /** epsilon de float32 usado en la fórmula slaney de Hz->mel (np.finfo(np.float32).eps). */
    private const val EPS_SLANEY = 1.1920928955078125e-7

    /** Características en layout [bin][frame] (compatible con el tensor [1,128,T] de ONNX). */
    class Caracteristicas(
        val datos: FloatArray,
        val nBins: Int,
        val totalFrames: Int,
        val framesValidos: Int,
    )

    /** Ventana de Hann simétrica (np.hanning(400)) rellena con 56 ceros a cada lado hasta 512. */
    fun ventanaHann(): FloatArray {
        val v = FloatArray(N_FFT)
        val margen = (N_FFT - VENTANA) / 2
        for (i in 0 until VENTANA) {
            v[margen + i] = (0.5 * (1.0 - cos(2.0 * Math.PI * i / (VENTANA - 1)))).toFloat()
        }
        return v
    }

    /**
     * Banco de filtros mel: 128 x 257, escala y normalización "slaney" (fórmula idéntica
     * a preprocessors/fbanks.py de onnx-asr, variante librosa), f_min=0, f_max=8000.
     */
    fun filtroMel(): FloatArray {
        val nFreqs = N_FFT / 2 + 1
        val fftFreqs = DoubleArray(nFreqs) { it * (MUESTREO / 2).toDouble() / (nFreqs - 1) }
        val mMin = hzAMel(0.0)
        val mMax = hzAMel((MUESTREO / 2).toDouble())
        // puntos equidistantes en mel, convertidos a Hz
        val mPts = DoubleArray(N_MELS + 2) { i -> melAHz(mMin + (mMax - mMin) * i / (N_MELS + 1)) }
        val fb = FloatArray(N_MELS * nFreqs)
        for (i in 0 until N_MELS) {
            val base = mPts[i]
            val centro = mPts[i + 1]
            val tope = mPts[i + 2]
            val d1 = centro - base
            val d2 = tope - centro
            val factor = 2.0 / (tope - base) // normalización "slaney"
            for (k in 0 until nFreqs) {
                val subida = (fftFreqs[k] - base) / d1
                val bajada = (tope - fftFreqs[k]) / d2
                fb[i * nFreqs + k] = (kotlin.math.max(0.0, kotlin.math.min(subida, bajada)) * factor).toFloat()
            }
        }
        return fb
    }

    private fun hzAMel(f: Double): Double =
        if (f < 1000.0) 3.0 * f / 200.0 else 15.0 + 27.0 * ln(f / 1000.0 + EPS_SLANEY) / ln(6.4)

    private fun melAHz(m: Double): Double =
        if (m < 15.0) 200.0 * m / 3.0 else 1000.0 * 6.4.pow((m - 15.0) / 27.0)

    /**
     * Pipeline completo sobre la señal float [-1,1]:
     * 1) pre-énfasis 0.97; 2) relleno con 256 ceros a cada lado;
     * 3) STFT con hop 160 y ventana de Hann 400 (poder: |X|^2);
     * 4) banco mel 128, log(mel + 2^-24);
     * 5) normalización por bin (media/varianza sobre los frames válidos,
     *    varianza muestral con denominador framesValidos-1, como en NeMo).
     * Los frames fuera de framesValidos se ponen a 0 (máscara de NeMo).
     */
    fun calcular(senal: FloatArray): Caracteristicas {
        val framesValidos = senal.size / HOP
        require(framesValidos >= 2) {
            "audio demasiado corto: ${senal.size} muestras (se necesitan al menos ${2 * HOP})"
        }
        val totalFrames = framesValidos + 1
        val nFreqs = N_FFT / 2 + 1

        // Pre-énfasis: x[0] queda igual; x[i] = x[i] - 0.97*x[i-1]
        val enf = FloatArray(senal.size)
        enf[0] = senal[0]
        for (i in 1 until senal.size) enf[i] = senal[i] - PREENFASIS * senal[i - 1]

        val ventana = ventanaHann()
        val filtro = filtroMel()
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val pot = FloatArray(nFreqs)
        val logMel = FloatArray(N_MELS * totalFrames)
        val margen = N_FFT / 2 // relleno de 256 ceros a cada lado

        for (f in 0 until totalFrames) {
            val offset = f * HOP - margen // índice en la señal (dominio rellenado)
            for (k in 0 until N_FFT) {
                val idx = offset + k
                val s = if (idx in enf.indices) enf[idx] else 0f
                re[k] = s * ventana[k]
                im[k] = 0f
            }
            Fft512.transform(re, im)
            for (b in pot.indices) {
                val r = re[b]
                val i2 = im[b]
                pot[b] = r * r + i2 * i2
            }
            for (m in 0 until N_MELS) {
                var acc = 0f
                val fila = m * nFreqs
                for (k in 0 until nFreqs) acc += filtro[fila + k] * pot[k]
                logMel[m * totalFrames + f] = ln(acc + GUARDIA_LOG)
            }
        }

        // Normalización por bin sobre frames válidos (media y varianza muestral).
        for (m in 0 until N_MELS) {
            val base = m * totalFrames
            var suma = 0.0
            for (t in 0 until framesValidos) suma += logMel[base + t]
            val media = suma / framesValidos
            var cuadrados = 0.0
            for (t in 0 until framesValidos) {
                val d = logMel[base + t] - media
                cuadrados += d * d
            }
            val denominador = (framesValidos - 1).coerceAtLeast(1)
            val desviacion = sqrt(cuadrados / denominador)
            val inverso = (1.0 / (desviacion + EPS_NORMALIZACION)).toFloat()
            for (t in 0 until framesValidos) {
                logMel[base + t] = ((logMel[base + t] - media).toFloat()) * inverso
            }
            for (t in framesValidos until totalFrames) logMel[base + t] = 0f
        }
        return Caracteristicas(logMel, N_MELS, totalFrames, framesValidos)
    }
}

/** Salida de un paso del decodificador transductor TDT. */
class SalidaPasoTransductor(
    val logits: FloatArray,
    val estado1: FloatArray,
    val estado2: FloatArray,
)

/**
 * Un paso del decodificador transductor TDT: recibe el token previo, los estados
 * LSTM y el frame del encoder; devuelve los logits (tokens | duraciones) y los
 * nuevos estados. Los arreglos devueltos son válidos hasta la siguiente llamada.
 */
fun interface PasoTransductor {
    fun paso(tokenPrevio: Int, estado1: FloatArray, estado2: FloatArray, frameEncoder: FloatArray): SalidaPasoTransductor
}

/**
 * Decodificador greedy transductor TDT (Token-and-Duration Transducer), réplica
 * exacta del loop de _AsrWithTransducerDecoding de onnx-asr (verificado en
 * src/onnx_asr/models/nemo.py). Parakeet v2 es un transductor TDT, no un modelo
 * CTC: el "greedy" aquí avanza frames según la cabeza de duración, no frame a frame.
 */
object DecodificadorTdt {
    /** Máximo de tokens por paso (config por defecto de NeMo/onnx-asr: 10). */
    const val MAX_TOKENS_POR_PASO_DEFECTO = 10

    class Resultado(
        val tokens: List<Int>,
        /** Índice del frame del encoder donde se emitió cada token. */
        val timestamps: List<Int>,
    )

    fun decodificar(
        framesValidos: Int,
        tamanoVocab: Int,
        indiceVacio: Int,
        estadoInicial1: FloatArray,
        estadoInicial2: FloatArray,
        frameEn: (Int) -> FloatArray,
        paso: PasoTransductor,
        maxTokensPorPaso: Int = MAX_TOKENS_POR_PASO_DEFECTO,
    ): Resultado {
        var e1 = estadoInicial1
        var e2 = estadoInicial2
        val tokens = ArrayList<Int>()
        val timestamps = ArrayList<Int>()
        var t = 0
        var emitidos = 0
        while (t < framesValidos) {
            val frame = frameEn(t)
            val tokenPrevio = if (tokens.isEmpty()) indiceVacio else tokens.last()
            val sal = paso.paso(tokenPrevio, e1, e2, frame)
            val logits = sal.logits            // logits = [tokens (tamanoVocab) | duraciones (resto)]
            var token = 0
            var mejor = Float.NEGATIVE_INFINITY
            for (i in 0 until tamanoVocab) {
                if (logits[i] > mejor) { mejor = logits[i]; token = i }
            }
            var duracion = 0
            mejor = Float.NEGATIVE_INFINITY
            for (i in tamanoVocab until logits.size) {
                if (logits[i] > mejor) { mejor = logits[i]; duracion = i - tamanoVocab }
            }
            if (token != indiceVacio) {
                // El estado solo se conserva cuando se emite un token (semántica de onnx-asr).
                e1 = sal.estado1.copyOf()
                e2 = sal.estado2.copyOf()
                tokens.add(token)
                timestamps.add(t)
                emitidos++
            }
            if (duracion > 0) {
                t += duracion
                emitidos = 0
            } else if (token == indiceVacio || emitidos == maxTokensPorPaso) {
                t++
                emitidos = 0
            }
        }
        return Resultado(tokens, timestamps)
    }
}

/**
 * Vocabulario de Parakeet v2: 1025 tokens en formato "<token> <id>" por línea
 * (resource crudo res/raw/parakeet_v2_vocab.txt, bajado de HuggingFace; ver docs).
 * El token "▁" (U+2581, marcador de inicio de palabra de SentencePiece) se
 * reemplaza por espacio, igual que en la referencia.
 */
class VocabularioParakeet private constructor(val tokens: List<String>) {
    val tamano: Int = tokens.size
    val indiceVacio: Int = tokens.indexOf("<blk>")

    init {
        require(indiceVacio >= 0) { "el vocabulario no contiene el token <blk>" }
    }

    /** Token en su forma lista para concatenar ("▁" ya convertido a espacio). */
    fun token(id: Int): String = tokens[id]

    companion object {
        fun leer(lineas: List<String>): VocabularioParakeet {
            val porId = HashMap<Int, String>()
            for (linea in lineas) {
                val limpia = linea.trimEnd('\n', '\r')
                if (limpia.isBlank()) continue
                val partes = limpia.split(" ")
                require(partes.size == 2) { "línea de vocabulario inválida: '$limpia'" }
                val token = partes[0]
                val id = partes[1].toInt()
                porId[id] = token.replace("▁", " ")
            }
            val n = (porId.keys.maxOrNull() ?: -1) + 1
            require(n > 0) { "vocabulario vacío" }
            val tokens = Array(n) { i -> requireNotNull(porId[i]) { "falta el id $i en el vocabulario" } }
            return VocabularioParakeet(tokens.toList())
        }

        fun leer(entrada: InputStream): VocabularioParakeet =
            leer(entrada.readBytes().toString(Charsets.UTF_8).lines())
    }
}

/**
 * Convierte tokens concatenados en texto final, replicando el patrón
 * DECODE_SPACE_PATTERN de onnx-asr: elimina espacios al inicio, espacios pegados
 * a no-palabra (" ," -> ","), y colapsa a un espacio simple los casos de
 * separación de palabra. Verificado contra src/onnx_asr/asr.py.
 */
object DecodificadorTexto {
    private val PATRON_ESPACIOS = Regex("""\A\s|\s\B|(\s)\b""")

    fun tokensATexto(tokens: List<String>): String =
        PATRON_ESPACIOS.replace(tokens.joinToString("")) { m ->
            if (m.groups[1] != null) " " else ""
        }
}

// ---------------------------------------------------------------------------
// Implementación con ONNX Runtime (Android).
// ---------------------------------------------------------------------------

/**
 * Parakeet TDT 0.6B v2 vía ONNX Runtime Android. Dos sesiones:
 *  - encoder-model.int8.onnx: entradas "audio_signal" [1,128,T] float32 y
 *    "length" [1] int64; salidas "outputs" [1,T',512] float32 y "encoded_lengths".
 *  - decoder_joint-model.int8.onnx: entradas "encoder_outputs" [1,512,1],
 *    "targets" [1,1] int64, "target_length" [1] int64, "input_states_1/2"
 *    [dim,1,dim] float32; salidas "outputs", "output_states_1/2".
 * (Nombres y formas verificados en src/onnx_asr/models/nemo.py de onnx-asr.)
 *
 * Nota: la primera llamada a transcribe() descarga ~622 MiB si el modelo no está;
 * eso debe ejecutarse fuera del hilo principal (ver docs/TRANSCRIBER.md).
 */
class ParakeetOnnxTranscriber(private val context: Context) : Transcriber {
    private val lock = Any()
    private var sesionEncoder: OrtSession? = null
    private var sesionDecoder: OrtSession? = null
    private var dimEstado1A = 0 // primera dim de input_states_1: [A, 1, C]
    private var dimEstado1C = 0
    private var dimEstado2A = 0
    private var dimEstado2C = 0
    private var dimEncoder = 0

    private val vocab: VocabularioParakeet by lazy {
        context.resources.openRawResource(cl.maarmapa.hexandroid.R.raw.parakeet_v2_vocab).use {
            VocabularioParakeet.leer(it)
        }
    }

    override fun transcribe(pcm: ShortArray, sampleRate: Int): String {
        require(sampleRate == NormalizadorPcm.MUESTREO_ESPERADO) {
            "tasa de muestreo no soportada: $sampleRate (se espera ${NormalizadorPcm.MUESTREO_ESPERADO})"
        }
        if (pcm.size < 2 * MelEspectrograma.HOP) return ""
        asegurarListo()

        val env = OrtEnvironment.getEnvironment()
        val senal = NormalizadorPcm.pcm16AFloat(pcm)
        val mel = MelEspectrograma.calcular(senal)

        // --- Encoder: mel -> codificación [T', dim] ---
        val (codificacion, framesCod) = correrEncoder(env, mel)

        // --- Decodificador TDT greedy ---
        val resultado = DecodificadorTdt.decodificar(
            framesValidos = framesCod,
            tamanoVocab = vocab.tamano,
            indiceVacio = vocab.indiceVacio,
            estadoInicial1 = FloatArray(dimEstado1A * dimEstado1C),
            estadoInicial2 = FloatArray(dimEstado2A * dimEstado2C),
            frameEn = { t -> codificacion.copyOfRange(t * dimEncoder, (t + 1) * dimEncoder) },
            paso = PasoTransductor { prev, e1, e2, frame -> pasoDecoder(env, prev, e1, e2, frame) },
        )

        // --- Tokens -> texto ---
        return DecodificadorTexto.tokensATexto(resultado.tokens.map { vocab.token(it) })
    }

    private fun correrEncoder(env: OrtEnvironment, mel: MelEspectrograma.Caracteristicas): Pair<FloatArray, Int> {
        val tensorMel = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(mel.datos),
            longArrayOf(1, mel.nBins.toLong(), mel.totalFrames.toLong()),
        )
        val tensorLen = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(mel.framesValidos.toLong())),
            longArrayOf(1),
        )
        val sesion = sesionEncoder ?: error("sesión del encoder no inicializada")
        sesion.run(mapOf("audio_signal" to tensorMel, "length" to tensorLen)).use { res ->
            val salidas = res.get(0) as OnnxTensor // "outputs": [1, T', dim], con T' dinámico
            val forma = (salidas.info as TensorInfo).shape
            // La dim temporal es simbólica (ORT la reporta como -1), así que el nº de
            // frames se deduce del tamaño del buffer; la dim del canal sí es estática.
            val dim = if (forma.size > 2 && forma[2] > 0) forma[2].toInt() else 512
            val buffer = salidas.floatBuffer
            val total = buffer.remaining()
            require(total % dim == 0) { "salida del encoder de tamaño inesperado: $total / dim $dim" }
            val frames = total / dim
            dimEncoder = dim
            val datos = FloatArray(total)
            buffer.get(datos, 0, total)
            val encodedLen = ((res.get(1) as OnnxTensor).longBuffer.get(0)).toInt()
            return datos to encodedLen.coerceAtMost(frames)
        }
    }

    private fun pasoDecoder(
        env: OrtEnvironment,
        tokenPrevio: Int,
        e1: FloatArray,
        e2: FloatArray,
        frame: FloatArray,
    ): SalidaPasoTransductor {
        val sesion = sesionDecoder ?: error("sesión del decoder no inicializada")
        val tFrame = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, dimEncoder.toLong(), 1))
        val tPrev = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(tokenPrevio.toLong())), longArrayOf(1, 1),
        )
        val tLen = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1))
        val tE1 = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(e1), longArrayOf(dimEstado1A.toLong(), 1L, dimEstado1C.toLong()),
        )
        val tE2 = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(e2), longArrayOf(dimEstado2A.toLong(), 1L, dimEstado2C.toLong()),
        )
        sesion.run(
            mapOf(
                "encoder_outputs" to tFrame,
                "targets" to tPrev,
                "target_length" to tLen,
                "input_states_1" to tE1,
                "input_states_2" to tE2,
            ),
        ).use { res ->
            val logits = (res.get(0) as OnnxTensor).floatBuffer // "outputs" (tokens | duraciones)
            val arrLogits = FloatArray(logits.remaining())
            logits.get(arrLogits)
            val s1 = (res.get(1) as OnnxTensor).floatBuffer
            val a1 = FloatArray(s1.remaining())
            s1.get(a1)
            val s2 = (res.get(2) as OnnxTensor).floatBuffer
            val a2 = FloatArray(s2.remaining())
            s2.get(a2)
            return SalidaPasoTransductor(arrLogits, a1, a2)
        }
    }

    /** Garantiza modelos descargados y sesiones creadas. Bloqueante: ver docs. */
    private fun asegurarListo() {
        synchronized(lock) {
            if (sesionEncoder != null) return
            val dir = context.filesDir
            val fEncoder = File(dir, NOMBRE_ENCODER)
            val fDecoder = File(dir, NOMBRE_DECODER)
            if (!DescargadorModelo.archivoValido(fEncoder, ModeloParakeetV2.ENCODER)) {
                DescargadorModelo.descargar(ModeloParakeetV2.ENCODER, fEncoder)
            }
            if (!DescargadorModelo.archivoValido(fDecoder, ModeloParakeetV2.DECODER_JOINT)) {
                DescargadorModelo.descargar(ModeloParakeetV2.DECODER_JOINT, fDecoder)
            }
            val opciones = OrtSession.SessionOptions()
            val env = OrtEnvironment.getEnvironment()
            sesionEncoder = env.createSession(fEncoder.absolutePath, opciones)
            val sesionD = env.createSession(fDecoder.absolutePath, OrtSession.SessionOptions())
            // Tamaños de los estados LSTM [dim, 1, dim] leídos de la propia sesión.
            val forma1 = (sesionD.getInputInfo("input_states_1").info as TensorInfo).shape
            val forma2 = (sesionD.getInputInfo("input_states_2").info as TensorInfo).shape
            require(forma1[0] > 0 && forma1[2] > 0 && forma2[0] > 0 && forma2[2] > 0) {
                "no se pudo inferir el tamaño de estado del decoder_joint"
            }
            dimEstado1A = forma1[0].toInt()
            dimEstado1C = forma1[2].toInt()
            dimEstado2A = forma2[0].toInt()
            dimEstado2C = forma2[2].toInt()
            sesionDecoder = sesionD
        }
    }

    /** Libera las sesiones ONNX. No cierra ni borra los archivos del modelo. */
    fun cerrar() {
        synchronized(lock) {
            sesionEncoder?.close(); sesionEncoder = null
            sesionDecoder?.close(); sesionDecoder = null
        }
    }

    companion object {
        const val NOMBRE_ENCODER = "parakeet-v2-encoder-model.int8.onnx"
        const val NOMBRE_DECODER = "parakeet-v2-decoder-joint-model.int8.onnx"
    }
}

/** Alternativa: whisper.cpp vía JNI (o TFLite). Reservado como opción de modelo. */
class WhisperTfliteTranscriber(private val context: Context) : Transcriber {
    override fun transcribe(pcm: ShortArray, sampleRate: Int): String = "" // stub sprint 2
}
