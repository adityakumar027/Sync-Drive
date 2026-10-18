package com.example.drivesyncfix

import androidx.room.*

@Entity(tableName = "synced_files", primaryKeys = ["localPath"])
data class SyncedFile(
    val localPath: String,
    val driveFileId: String,
    val driveFolderId: String,
    val size: Long,
    val lastModified: Long,
    val syncedAt: Long
)

@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM synced_files WHERE localPath = :path LIMIT 1")
    fun findByPath(path: String): SyncedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: SyncedFile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(records: List<SyncedFile>)

    @Query("SELECT * FROM synced_files")
    fun getAll(): List<SyncedFile>

    @Query("DELETE FROM synced_files WHERE localPath = :path")
    fun delete(path: String)
}

/** One "sync this device folder into this Drive folder" mapping, like the folder pairs in the old app. */
@Entity(tableName = "sync_pairs")
data class SyncPair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localPath: String,
    val localDisplayName: String,
    val driveFolderId: String,
    val driveFolderName: String
)

@Dao
interface SyncPairDao {
    @Query("SELECT * FROM sync_pairs ORDER BY id")
    fun getAll(): List<SyncPair>

    @Insert
    fun insert(pair: SyncPair): Long

    @Query("DELETE FROM sync_pairs WHERE id = :id")
    fun delete(id: Long)
}

@Database(entities = [SyncedFile::class, SyncPair::class, SyncLog::class], version = 4, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun syncPairDao(): SyncPairDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: android.content.Context): SyncDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "drivesyncfix.db"
                )
                    .fallbackToDestructiveMigration() // fine pre-release; switch to a real Migration once you rely on this data
                    .build().also { INSTANCE = it }
            }
    }
}

