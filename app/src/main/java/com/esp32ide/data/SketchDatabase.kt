package com.esp32ide.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entity ────────────────────────────────────────────────────────────────────
@Entity(tableName = "sketches")
data class Sketch(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isModified: Boolean = false
)

// ── DAO ───────────────────────────────────────────────────────────────────────
@Dao
interface SketchDao {
    @Query("SELECT * FROM sketches ORDER BY updatedAt DESC")
    fun getAllSketches(): Flow<List<Sketch>>

    @Query("SELECT * FROM sketches WHERE id = :id")
    suspend fun getSketchById(id: Int): Sketch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSketch(sketch: Sketch): Long

    @Update
    suspend fun updateSketch(sketch: Sketch)

    @Delete
    suspend fun deleteSketch(sketch: Sketch)

    @Query("UPDATE sketches SET content = :content, updatedAt = :ts, isModified = 1 WHERE id = :id")
    suspend fun updateContent(id: Int, content: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE sketches SET name = :name WHERE id = :id")
    suspend fun renameSketch(id: Int, name: String)

    @Query("SELECT COUNT(*) FROM sketches")
    suspend fun getCount(): Int
}

// ── Database ──────────────────────────────────────────────────────────────────
@Database(entities = [Sketch::class], version = 1, exportSchema = false)
abstract class SketchDatabase : RoomDatabase() {

    abstract fun sketchDao(): SketchDao

    companion object {
        @Volatile
        private var INSTANCE: SketchDatabase? = null

        fun getInstance(context: Context): SketchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SketchDatabase::class.java,
                    "sketches.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
