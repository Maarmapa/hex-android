package cl.maarmapa.hexandroid.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Base de datos local del historial de dictado (solo texto; el audio nunca se guarda). */
@Database(entities = [DictationEntry::class], version = 1, exportSchema = false)
abstract class HexHistoryDatabase : RoomDatabase() {

    abstract fun dictationDao(): DictationDao

    companion object {
        @Volatile
        private var instancia: HexHistoryDatabase? = null

        fun obtener(context: Context): HexHistoryDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext,
                    HexHistoryDatabase::class.java,
                    "hex_historial.db"
                ).build().also { instancia = it }
            }
    }
}
