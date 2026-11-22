package cl.maarmapa.hexandroid.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una entrada del historial de dictado. Espejo del History de Hex (anomalyco/hex, MIT).
 *
 * PRIVACIDAD: el audio nunca se guarda. Solo se persisten textos (crudo y final)
 * y metadatos mínimos, todo local en el dispositivo.
 */
@Entity(tableName = "dictation_entries")
data class DictationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Momento del dictado, época en milisegundos. */
    val timestamp: Long,
    /** Texto tal como salió de la transcripción on-device. */
    @ColumnInfo(name = "texto_crudo") val textoCrudo: String,
    /** Texto final tras la reescritura opcional; igual al crudo si la reescritura está apagada. */
    @ColumnInfo(name = "texto_final") val textoFinal: String,
    /** Etiqueta de la app en segundo plano que recibió el dictado, si se pudo determinar. */
    @ColumnInfo(name = "app_fondo") val appFondo: String? = null,
    /** Duración del dictado en milisegundos. */
    @ColumnInfo(name = "duracion_ms") val duracionMs: Long = 0,
)
