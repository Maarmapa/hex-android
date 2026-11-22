package cl.maarmapa.hexandroid.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Fachada del historial local de dictado. Espejo del History de Hex:
 *  - Retención por defecto: 7 días.
 *  - Tope duro: 10.000 entradas; la más vieja se va primero.
 *  - El audio nunca se guarda: solo texto crudo, texto final y metadatos mínimos.
 *
 * Puntos de integración del servicio (una línea por punto, sin tocar su lógica):
 *   1) HexInputMethodService.onCreate()    -> HistoryStore.limpiarAlArrancar(this)
 *   2) HexInputMethodService.finishDictation() ->
 *        HistoryStore.registrar(this, textoCrudo = texto, textoFinal = textoFinal, appFondo = ..., duracionMs = ...)
 */
object HistoryStore {

    /** Días que viven las entradas (por defecto 7, como el History de Hex). */
    const val DIAS_RETENCION = 7
    /** Tope duro de entradas en la base. */
    const val TOPE_ENTRADAS = 10_000

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Limpiador para el arranque del servicio: purga retención y tope fuera del hilo principal. */
    fun limpiarAlArrancar(context: Context) {
        alcance.launch { limpiar(context) }
    }

    /**
     * Guarda una entrada y aplica la retención en el mismo paso (la más vieja se va primero).
     * Nunca recibe audio: solo textos y metadatos.
     */
    fun registrar(
        context: Context,
        textoCrudo: String,
        textoFinal: String,
        appFondo: String? = null,
        duracionMs: Long = 0,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        if (textoCrudo.isBlank() && textoFinal.isBlank()) return
        alcance.launch {
            val dao = HexHistoryDatabase.obtener(context).dictationDao()
            dao.insertar(
                DictationEntry(
                    timestamp = timestamp,
                    textoCrudo = textoCrudo,
                    textoFinal = textoFinal,
                    appFondo = appFondo,
                    duracionMs = duracionMs,
                )
            )
            limpiar(context)
        }
    }

    /** Aplica la retención (días) y el tope duro (entradas). Devuelve la cantidad borrada. */
    suspend fun limpiar(context: Context): Int {
        val dao = HexHistoryDatabase.obtener(context).dictationDao()
        val marca = System.currentTimeMillis() - DIAS_RETENCION * 24L * 60 * 60 * 1000
        var borradas = dao.borrarAnterioresA(marca)
        borradas += dao.recortarA(TOPE_ENTRADAS)
        return borradas
    }

    /** Listado paginado, la más nueva primero. */
    suspend fun listar(context: Context, limite: Int, desde: Int): List<DictationEntry> =
        HexHistoryDatabase.obtener(context).dictationDao().listar(limite, desde)

    suspend fun contar(context: Context): Int =
        HexHistoryDatabase.obtener(context).dictationDao().contar()

    /** Borra todo el historial. Bloquea hasta terminar (llamar fuera del hilo principal). */
    fun borrarTodoBloqueante(context: Context): Int =
        runBlocking { HexHistoryDatabase.obtener(context).dictationDao().borrarTodo() }
}
