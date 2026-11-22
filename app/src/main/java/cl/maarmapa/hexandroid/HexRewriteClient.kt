package cl.maarmapa.hexandroid

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException

/**
 * Reescritura opcional del texto (el "Modes > Rewrite with OpenCode" de Hex, apagado por defecto).
 *
 * Proveedores BYOK vía HTTP puro (HttpURLConnection, sin dependencias nuevas):
 *  - "openai"    → https://api.openai.com/v1/chat/completions
 *  - "anthropic" → https://api.anthropic.com/v1/messages
 *  - "xai"       → https://api.x.ai/v1/chat/completions
 *
 * Reglas:
 *  - Apagado por defecto: sin provider + key configurados, [rewrite] devuelve el texto intacto.
 *  - Fallback byte por byte: ante cualquier fallo (timeout, red, HTTP != 2xx, JSON inválido)
 *    se devuelve el texto original exacto, sin recortes ni normalizaciones.
 *  - La key se guarda solo en SharedPreferences y NUNCA se loguea ni imprime.
 *  - En logs el texto se trunca a 40 caracteres, para no volcar contenido completo.
 */
class HexRewriteClient(
    private val provider: String = "",
    private val apiKey: String = "",
    private val model: String = "",
) {
    /** true solo si hay proveedor y key; es el equivalente al toggle off por defecto de Hex. */
    val disponible = provider in PROVEEDORES && apiKey.isNotBlank()

    /**
     * Reescribe el texto con el proveedor configurado. Ante cualquier fallo devuelve
     * [texto] byte por byte, sin alterarlo.
     */
    suspend fun rewrite(texto: String, contexto: String = ""): String {
        if (!disponible) return texto
        if (texto.isBlank()) return texto
        return withContext(Dispatchers.IO) { llamarProveedor(texto, contexto) }
    }

    // ─────────────────────────── HTTP ───────────────────────────

    private fun llamarProveedor(texto: String, contexto: String): String {
        return try {
            val url = URL(PROVEEDORES.getValue(provider).url)
            val conexion = url.openConnection() as HttpURLConnection
            conexion.requestMethod = "POST"
            conexion.connectTimeout = TIMEOUT_MS
            conexion.readTimeout = TIMEOUT_MS
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Accept", "application/json")
            PROVEEDORES.getValue(provider).cabeceraKey(apiKey).forEach { (nombre, valor) ->
                conexion.setRequestProperty(nombre, valor)
            }

            val cuerpo = cuerpoRequest(texto, contexto)
            conexion.outputStream.use { salida ->
                salida.write(cuerpo.toByteArray(Charsets.UTF_8))
                salida.flush()
            }

            val codigo = conexion.responseCode
            if (codigo !in 200..299) {
                Log.w(TAG, "HTTP $codigo del proveedor (texto: ${truncar(texto)})")
                conexion.errorStream?.close()
                return texto // fallback byte por byte
            }

            val respuesta = conexion.inputStream.use { entrada -> entrada.readBytes().toString(Charsets.UTF_8) }
            extraerTexto(respuesta) ?: texto // sin texto parseable → fallback
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout del proveedor (texto: ${truncar(texto)})")
            texto
        } catch (e: IOException) {
            Log.w(TAG, "Error de red (texto: ${truncar(texto)})")
            texto
        } catch (e: Exception) {
            Log.w(TAG, "Error inesperado: ${e.javaClass.simpleName}")
            texto
        }
    }

    /** Body JSON del request, según proveedor. */
    private fun cuerpoRequest(texto: String, contexto: String): String {
        val modeloElegido = model.ifBlank { PROVEEDORES.getValue(provider).modeloPorDefecto }
        return when (provider) {
            "anthropic" -> JSONObject().apply {
                put("model", modeloElegido)
                put("max_tokens", MAX_TOKENS)
                if (contexto.isNotBlank()) put("system", contexto)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", texto)
                ))
            }.toString()
            else -> JSONObject().apply { // openai y xai comparten formato chat completions
                put("model", modeloElegido)
                put("max_tokens", MAX_TOKENS)
                val mensajes = JSONArray()
                if (contexto.isNotBlank()) mensajes.put(
                    JSONObject().put("role", "system").put("content", contexto)
                )
                mensajes.put(JSONObject().put("role", "user").put("content", texto))
                put("messages", mensajes)
            }.toString()
        }
    }

    /** Extrae el texto reescrito del JSON de respuesta; null si no puede parsearse. */
    private fun extraerTexto(respuesta: String): String? = try {
        val json = JSONObject(respuesta)
        when (provider) {
            "anthropic" -> json.optJSONArray("content")
                ?.let { contenido -> (0 until contenido.length()).firstNotNullOfOrNull { indice ->
                    contenido.optJSONObject(indice)?.takeIf { it.optString("type") == "text" }?.optString("text")
                } }
            else -> json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Respuesta no parseable: ${truncar(respuesta)}")
        null
    }

    // ─────────────────────── Preferencias ───────────────────────

    companion object {
        private const val TAG = "HexRewrite"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_TOKENS = 1024
        private const val MAX_LOG = 40

        const val PREFS = "hex_rewrite"
        const val PREF_PROVIDER = "provider"
        const val PREF_KEY = "api_key"
        const val PREF_MODEL = "model"

        /** Proveedores soportados: endpoint, cabeceras de autenticación y modelo por defecto. */
        private val PROVEEDORES = mapOf(
            "openai" to Proveedor(
                url = "https://api.openai.com/v1/chat/completions",
                modeloPorDefecto = "gpt-4o-mini",
                cabeceraKey = { key -> listOf("Authorization" to "Bearer $key") },
            ),
            "xai" to Proveedor(
                url = "https://api.x.ai/v1/chat/completions",
                modeloPorDefecto = "grok-3-mini",
                cabeceraKey = { key -> listOf("Authorization" to "Bearer $key") },
            ),
            "anthropic" to Proveedor(
                url = "https://api.anthropic.com/v1/messages",
                modeloPorDefecto = "claude-3-5-haiku-latest",
                cabeceraKey = { key -> listOf(
                    "x-api-key" to key,
                    "anthropic-version" to "2023-06-01",
                ) },
            ),
        )

        private class Proveedor(
            val url: String,
            val modeloPorDefecto: String,
            val cabeceraKey: (String) -> List<Pair<String, String>>,
        )

        /** Trunca a 40 caracteres para logs: nunca se voltea texto completo ni keys. */
        private fun truncar(valor: String?): String {
            if (valor.isNullOrBlank()) return "(vacío)"
            val unaLinea = valor.replace("\n", " ").trim()
            return if (unaLinea.length <= MAX_LOG) unaLinea else unaLinea.take(MAX_LOG) + "…"
        }

        // Helpers para guardar/recuperar la configuración en SharedPreferences.
        // La key vive solo aquí, en el dispositivo; nunca en el repo ni en logs.

        fun guardarConfig(contexto: Context, provider: String, apiKey: String, model: String = "") {
            contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_PROVIDER, provider)
                .putString(PREF_KEY, apiKey)
                .putString(PREF_MODEL, model)
                .apply()
        }

        fun borrarConfig(contexto: Context) {
            contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** Lee la config guardada; null si no hay provider o key → cliente apagado. */
        fun desdePrefs(contexto: Context): HexRewriteClient? {
            val prefs: SharedPreferences = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val provider = prefs.getString(PREF_PROVIDER, "").orEmpty()
            val key = prefs.getString(PREF_KEY, "").orEmpty()
            if (provider.isBlank() || key.isBlank()) return null
            return HexRewriteClient(provider, key, prefs.getString(PREF_MODEL, "").orEmpty())
        }
    }
}
