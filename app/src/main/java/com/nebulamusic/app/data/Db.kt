package com.nebulamusic.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A track saved for offline listening; [json] is the serialized [Track] so lists work without any network. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val serverId: String,
    val path: String,
    val bytes: Long,
    val completedAt: Long,
    val title: String,
    val artist: String,
    val album: String,
    val json: String,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE serverId = :serverId ORDER BY completedAt DESC")
    fun observe(serverId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(d: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun removeServer(serverId: String)

    @Query("SELECT * FROM downloads WHERE trackId = :id")
    suspend fun get(id: String): DownloadEntity?
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(ctx: Context): AppDb = Room.databaseBuilder(ctx, AppDb::class.java, "nebulamusic.db").build()
    }
}
