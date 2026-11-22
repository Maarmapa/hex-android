package cl.maarmapa.hexandroid.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** DAO del historial de dictado: insertar, listar paginado, borrar todo y purga por retención. */
@Dao
interface DictationDao {

    @Insert
    suspend fun insertar(entry: DictationEntry): Long

    /** Listado paginado, la más nueva primero. */
    @Query(
        "SELECT * FROM dictation_entries " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limite OFFSET :desde"
    )
    suspend fun listar(limite: Int, desde: Int): List<DictationEntry>

    @Query("SELECT COUNT(*) FROM dictation_entries")
    suspend fun contar(): Int

    @Query("DELETE FROM dictation_entries")
    suspend fun borrarTodo(): Int

    /** Retención por días: elimina todo lo anterior a la marca indicada. */
    @Query("DELETE FROM dictation_entries WHERE timestamp < :marca")
    suspend fun borrarAnterioresA(marca: Long): Int

    /** Tope duro de entradas: conserva las :tope más nuevas y elimina el resto (la más vieja se va primero). */
    @Query(
        "DELETE FROM dictation_entries WHERE id NOT IN " +
            "(SELECT id FROM dictation_entries ORDER BY timestamp DESC, id DESC LIMIT :tope)"
    )
    suspend fun recortarA(tope: Int): Int
}
