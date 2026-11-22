package cl.maarmapa.hexandroid

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descriptor de una descarga verificable: URL pública, tamaño exacto publicado en el
 * repositorio y SHA-256 cuando está publicado (los repos de HuggingFace exponen el
 * SHA-256 de los archivos LFS en su API).
 *
 * Solo se descargan ARCHIVOS DE MODELOS. El audio nunca sale del dispositivo:
 * esta clase no recibe ni transmite audio, nunca.
 */
data class ModeloDescarga(
    val url: String,
    /** Tamaño exacto en bytes según el repositorio de origen. */
    val tamanoEsperado: Long,
    /** SHA-256 en hexadecimal (minúsculas), o null si no está publicado. */
    val sha256: String? = null,
)

/**
 * Modelo Parakeet TDT 0.6B v2 exportado a ONNX por la comunidad (istupakov), derivado
 * del modelo oficial de NVIDIA nvidia/parakeet-tdt-0.6b-v2 (licencia CC-BY-4.0).
 *
 * Tamaños y SHA-256 verificados contra la API de HuggingFace
 * (https://huggingface.co/api/models/istupakov/parakeet-tdt-0.6b-v2-onnx/tree/main),
 * donde los archivos LFS publican su sha256 como `lfs.oid`. Ver docs/TRANSCRIBER.md.
 */
object ModeloParakeetV2 {
    const val REPO = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx"

    /** Codificador Conformer (int8, ~622 MiB). */
    val ENCODER = ModeloDescarga(
        url = "$REPO/resolve/main/encoder-model.int8.onnx",
        tamanoEsperado = 652_184_014L,
        sha256 = "3e0581fda6ab843888b51e56d7ee78b6d5bc3237ec113af1f732d1d5286aa155",
    )

    /** Decodificador + joiner del transductor TDT (int8, ~8,6 MiB). */
    val DECODER_JOINT = ModeloDescarga(
        url = "$REPO/resolve/main/decoder_joint-model.int8.onnx",
        tamanoEsperado = 8_998_286L,
        sha256 = "a449f49acd68979d418651dd2dcb737cc0f1bf0225e009e29ee326354edbf7d3",
    )
}

/**
 * Descargador de modelos con verificación de tamaño, SHA-256 y reintentos con
 * retroceso exponencial. Kotlin puro (sin dependencias de Android): testeable en JVM.
 *
 * Escritura atómica: primero escribe en un archivo temporal `*.parte` junto al
 * destino y solo tras validar tamaño/hash lo renombra al destino final, de modo que
 * un archivo a medio descargar nunca parece completo.
 */
object DescargadorModelo {
    /** Número de intentos por descarga (1 intento inicial + reintentos). */
    const val REINTENTOS = 3

    /** Backoff base en ms entre intentos: 1 s, 2 s, 4 s, ... */
    private fun backoffMs(intento: Int): Long = 1000L shl (intento - 1)

    /**
     * Descarga [descarga] a [destino], con reintentos. Lanza IOException si agota
     * los intentos o si el archivo no pasa la verificación de tamaño/SHA-256.
     *
     * @param progreso callback opcional (bytesLeidos, bytesTotales); bytesTotales
     *   es el tamaño esperado del descriptor.
     */
    @Throws(IOException::class)
    fun descargar(
        descarga: ModeloDescarga,
        destino: File,
        progreso: (leidos: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        var ultimoError: Exception? = null
        for (intento in 1..REINTENTOS) {
            try {
                descargarUnaVez(descarga, destino, progreso)
                return
            } catch (e: Exception) {
                ultimoError = e
                if (intento < REINTENTOS) {
                    try {
                        Thread.sleep(backoffMs(intento))
                    } catch (interrumpido: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("descarga interrumpida", interrumpido)
                    }
                }
            }
        }
        throw IOException(
            "descarga fallida tras $REINTENTOS intentos de ${descarga.url}: ${ultimoError?.message}",
            ultimoError,
        )
    }

    /** Un intento de descarga, con validación de tamaño y SHA-256. */
    private fun descargarUnaVez(
        descarga: ModeloDescarga,
        destino: File,
        progreso: (Long, Long) -> Unit,
    ) {
        destino.parentFile?.mkdirs()
        val temporal = File(destino.parentFile, destino.name + ".parte")
        var ok = false
        val conn = URL(descarga.url).openConnection() as HttpURLConnection
        try {
            // HuggingFace sirve /resolve/ vía redirección HTTPS al CDN; la seguimos.
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val codigo = conn.responseCode
            if (codigo !in 200..299) {
                throw IOException("HTTP $codigo al descargar ${descarga.url}")
            }
            val md = descarga.sha256?.let { MessageDigest.getInstance("SHA-256") }
            var leidos = 0L
            conn.inputStream.use { entrada ->
                temporal.outputStream().use { salida ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        salida.write(buffer, 0, n)
                        md?.update(buffer, 0, n)
                        leidos += n
                        progreso(leidos, descarga.tamanoEsperado)
                    }
                }
            }
            if (leidos != descarga.tamanoEsperado) {
                throw IOException(
                    "tamaño inesperado: $leidos bytes, se esperaban ${descarga.tamanoEsperado}",
                )
            }
            descarga.sha256?.let { esperado ->
                val real = sha256Hex(md!!.digest())
                if (!real.equals(esperado, ignoreCase = true)) {
                    throw IOException("SHA-256 no coincide: esperado $esperado, obtenido $real")
                }
            }
            if (!temporal.renameTo(destino)) {
                destino.delete()
                if (!temporal.renameTo(destino)) {
                    throw IOException("no se pudo mover el archivo temporal a ${destino.absolutePath}")
                }
            }
            ok = true
        } finally {
            conn.disconnect()
            if (!ok) temporal.delete()
        }
    }

    /** true si [f] existe y tiene el tamaño esperado (no re-hashea archivos existentes). */
    fun archivoValido(f: File, descarga: ModeloDescarga): Boolean =
        f.exists() && f.length() == descarga.tamanoEsperado

    /** SHA-256 de un arreglo de bytes como cadena hexadecimal en minúsculas. */
    fun sha256Hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}
