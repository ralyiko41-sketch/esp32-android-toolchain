package com.esp32ide.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Board config per project
    val boardName: String = "ESP32 Dev Module",
    val boardFQBN: String = "esp32:esp32:esp32",
    val cpuFreq: String = "240",
    val flashFreq: String = "80",
    val flashMode: String = "qio",
    val partitionScheme: String = "default",
    val coreDebugLevel: String = "none"
)

@Entity(
    tableName = "project_files",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ProjectFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val projectId: Int,
    val name: String, // e.g. "main.ino", "wifi_helper.h"
    val content: String,
    val isMain: Boolean = false, // The primary .ino file
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface ProjectDao {
    // Project operations
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Int): Project?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: Project): Long

    @Update
    suspend fun updateProject(project: Project)

    @Delete
    suspend fun deleteProject(project: Project)

    // File operations
    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY isMain DESC, name ASC")
    fun getFilesForProject(projectId: Int): Flow<List<ProjectFile>>

    @Query("SELECT * FROM project_files WHERE id = :id")
    suspend fun getFileById(id: Int): ProjectFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: ProjectFile): Long

    @Update
    suspend fun updateFile(file: ProjectFile)

    @Delete
    suspend fun deleteFile(file: ProjectFile)

    @Query("UPDATE project_files SET content = :content, updatedAt = :ts WHERE id = :id")
    suspend fun updateFileContent(id: Int, content: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun getProjectCount(): Int
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [Project::class, ProjectFile::class], version = 2, exportSchema = false)
abstract class SketchDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: SketchDatabase? = null

        fun getInstance(context: Context): SketchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SketchDatabase::class.java,
                    "sketches.db"
                )
                .fallbackToDestructiveMigration() // Simple for now, can add proper migration if needed
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
