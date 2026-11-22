package cl.maarmapa.hexandroid

/**
 * Reescritura opcional del texto (el "Modes > Rewrite with OpenCode" de Hex, apagado por defecto).
 * MVP: POST al proveedor BYOK configurado (OpenAI/Anthropic/xAI). Sin key configurada, desactivado.
 * Privacidad: solo se envía el texto; nunca el audio. Espíritu de la regla de Hex: apagado por defecto.
 */
class HexRewriteClient(private val provider: String = "", private val apiKey: String = "") {
    val disponible = provider.isNotEmpty() && apiKey.isNotEmpty()

    suspend fun rewrite(texto: String, contexto: String = ""): String {
        if (!disponible) return texto // byte por byte, como el toggle off de Hex
        // TODO (sprint 2): llamada al proveedor, timeout, fallback al texto original
        return texto
    }
}
