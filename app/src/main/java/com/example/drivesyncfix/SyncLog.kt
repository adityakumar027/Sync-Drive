package com.example.drivesyncfix

import androidx.room.*

/**
 * Records individual sync events (upload, skip, fail) for the sync history screen.
 */
@Entity(tableName = "sync_logs")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val filePath: String,
    val action: String,        // "uploaded", "skipped", "failed"
    val fileSize: Long = 0,
    val errorMessage: String? = null,
    val syncPairName: String = ""
)

@Dao
interface SyncLogDao {
    @Insert
    fun insert(log: SyncLog)

    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 200): List<SyncLog>

    @Query("SELECT * FROM sync_logs WHERE action = :action ORDER BY timestamp DESC LIMIT :limit")
    fun getByAction(action: String, limit: Int = 200): List<SyncLog>

    @Query("DELETE FROM sync_logs WHERE timestamp < :before")
    fun deleteOlderThan(before: Long)

    @Query("DELETE FROM sync_logs")
    fun clearAll()

    @Query("SELECT COUNT(*) FROM sync_logs WHERE action = :action AND timestamp > :since")
    fun countSince(action: String, since: Long): Int

    @Query("SELECT * FROM sync_logs WHERE action = 'failed' GROUP BY filePath ORDER BY timestamp DESC")
    fun getFailedFiles(): List<SyncLog>

    @Query("SELECT COUNT(DISTINCT filePath) FROM sync_logs WHERE action = 'failed'")
    fun countFailedFiles(): Int

    @Query("DELETE FROM sync_logs WHERE filePath = :path AND action = 'failed'")
    fun removeFailed(path: String)

    @Query("DELETE FROM sync_logs WHERE action = 'failed'")
    fun clearFailed()
}
