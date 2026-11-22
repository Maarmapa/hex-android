package cl.maarmapa.hexandroid.settings

import android.content.Context

/**
 * Preferencias de la reescritura opcional (el "Modes > Rewrite with OpenCode" de Hex).
 *
 * - La reescritura está APAGADA por defecto; solo se activa si el usuario lo decide.
 * - La clave (BYOK) se guarda únicamente en las SharedPreferences privadas de la app,
 *   dentro del dispositivo. No hay claves ni tokens en el código fuente.
 * - Regla de privacidad: la clave nunca se loguea ni se imprime. Este objeto no la
 *   registra jamás; la única forma segura de mostrarla en la interfaz es [resumenClave].
 */
object RewriteSettings {

    private const val PREFS = "hex_ajustes"
    private const val CLAVE_REESCRITURA_ACTIVADA = "reescritura_activada"
    private const val CLAVE_API = "clave_api"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La reescritura está apagada por defecto. */
    fun reescrituraActivada(context: Context): Boolean =
        prefs(context).getBoolean(CLAVE_REESCRITURA_ACTIVADA, false)

    fun setReescrituraActivada(context: Context, activada: Boolean) {
        prefs(context).edit().putBoolean(CLAVE_REESCRITURA_ACTIVADA, activada).apply()
    }

    /**
     * Devuelve la clave guardada o null si no hay ninguna.
     * Uso exclusivo: construir el cliente de reescritura. Nunca se loguea ni se imprime.
     */
    fun claveApi(context: Context): String? =
        prefs(context).getString(CLAVE_API, null)?.takeIf { it.isNotBlank() }

    fun setClaveApi(context: Context, clave: String) {
        prefs(context).edit().putString(CLAVE_API, clave).apply()
    }

    fun borrarClaveApi(context: Context) {
        prefs(context).edit().remove(CLAVE_API).apply()
    }

    /** Resumen seguro para la interfaz: nunca expone la clave real. */
    fun resumenClave(context: Context): String {
        val largo = claveApi(context)?.length ?: 0
        return if (largo == 0) "sin clave guardada" else "clave guardada ($largo caracteres)"
    }
}
