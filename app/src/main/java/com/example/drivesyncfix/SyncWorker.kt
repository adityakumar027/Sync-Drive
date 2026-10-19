package com.example.drivesyncfix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sync engine modelled on the reference DriveSync app (com.ttxapps.drivesync):
 *
 *   1. SCAN local files
 *   2. BATCH-VERIFY Drive folder contents (folder-by-folder in memory, takes seconds for 84K files)
 *   3. SIMULTANEOUS UPLOADS — 4 concurrent upload workers
 *       - Live active slots (slot 0..3) with real-time byte progress visible on UI
 *       - Automatic exponential backoff on 403 (Rate Limits) and 429 (Too Many Requests)
 *       - Automatic token refresh on 401 Unauthorized
 *       - Direct upload for small files (<5MB), chunked resumable upload for large files (no size limit)
 *   4. FAST RE-SYNC & RETRY:
 *       - Dedicated retry mode (KEY_RETRY_FAILED_ONLY) to retry only failed files in seconds
 *       - Completed files saved immediately to SQLite so re-sync skips them instantly
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME_MANUAL_SYNC = "manual_sync"
        private const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1001
        const val PARALLEL_UPLOADS = 4

        // Progress data keys
        const val KEY_STATUS = "status"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_CURRENT_PAIR = "current_pair"
        const val KEY_FILES_DONE = "files_done"
        const val KEY_FILES_TOTAL = "files_total"
        const val KEY_FILES_UPLOADED = "files_uploaded"
        const val KEY_FILES_SKIPPED = "files_skipped"
        const val KEY_FILES_FAILED = "files_failed"
        const val KEY_FORCE_SYNC = "force_sync"
        const val KEY_RETRY_FAILED_ONLY = "retry_failed_only"

        // Active simultaneous transfer slots
        const val KEY_ACTIVE_COUNT = "active_count"
        const val KEY_SLOT_0 = "slot_0"
        const val KEY_SLOT_1 = "slot_1"
        const val KEY_SLOT_2 = "slot_2"
        const val KEY_SLOT_3 = "slot_3"

        const val STATUS_SCANNING = "scanning"
        const val STATUS_SYNCING = "syncing"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
    }

    data class ActiveTransfer(
        val fileName: String,
        @Volatile var bytesUploaded: Long,
        val totalBytes: Long
    )

    private val atomicUploaded = AtomicInteger(0)
    private val atomicSkipped = AtomicInteger(0)
    private val atomicFailed = AtomicInteger(0)
    private val activeSlots = ConcurrentHashMap<Int, ActiveTransfer>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            doSyncWork()
        } catch (e: Exception) {
            android.util.Log.e("SyncWorker", "Sync failed", e)
            reportProgress(
                status = STATUS_FAILED,
                currentFile = "Error: ${e.message}",
                currentPair = "",
                done = 0,
                total = 0,
                uploaded = 0,
                skipped = 0,
                failed = 0
            )
            Result.failure(
                Data.Builder()
                    .putString(KEY_STATUS, STATUS_FAILED)
                    .putString("error", e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    private data class FileItem(
        val scanned: FileScanner.ScannedFile,
        val pair: SyncPair
    )

    private data class UploadTask(
        val scanned: FileScanner.ScannedFile,
        val pair: SyncPair,
        val driveFolderId: String,
        val existingDriveId: String? = null
    )

    private suspend fun doSyncWork(): Result {
        safeSetForeground("Starting sync…")

        val db = SyncDatabase.getInstance(applicationContext)
        val syncedDao = db.syncedFileDao()
        val logDao = db.syncLogDao()
        val pairs = db.syncPairDao().getAll()

        if (pairs.isEmpty()) {
            reportProgress(STATUS_FAILED, "No sync folders configured", "", 0, 0, 0, 0, 0)
            return Result.failure(
                Data.Builder()
                    .putString(KEY_STATUS, STATUS_FAILED)
                    .putString("error", "No sync folders configured")
                    .build()
            )
        }

        val filterPrefs = applicationContext.getSharedPreferences("syncdrive_filter_prefs", Context.MODE_PRIVATE)
        val includeHidden = filterPrefs.getBoolean("pref_include_hidden_files", true)
        val scanner = FileScanner(includeHidden = includeHidden)
        val uploadManager = DriveUploadManager(applicationContext) {
            AuthManager.getFreshAccessToken(applicationContext)
        }

        val pairResolvers = mutableMapOf<Long, DriveFolderResolver>()
        for (pair in pairs) {
            pairResolvers[pair.id] = DriveFolderResolver(applicationContext, pair.driveFolderId)
        }

        val retryFailedOnly = inputData.getBoolean(KEY_RETRY_FAILED_ONLY, false)
        val filesToUpload = mutableListOf<UploadTask>()
        val totalFiles: Int

        if (retryFailedOnly) {
            // ═══════ Retry Failed Files Mode ═══════
            reportProgress(STATUS_SCANNING, "Loading failed files to retry…", "", 0, 0, 0, 0, 0)
            val failedLogs = logDao.getFailedFiles()

            if (failedLogs.isEmpty()) {
                reportProgress(STATUS_COMPLETE, "No failed files to retry", "", 0, 0, 0, 0, 0)
                return Result.success(buildOutputData(0))
            }

            for (log in failedLogs) {
                val file = File(log.filePath)
                if (!file.exists() || !file.canRead()) continue
                val matchingPair = pairs.find { file.path.startsWith(it.localPath) } ?: pairs.first()
                val relativePath = file.toRelativeString(File(matchingPair.localPath))
                val targetFolderId = pairResolvers[matchingPair.id]!!.resolveFolderFor(relativePath)
                filesToUpload.add(
                    UploadTask(
                        scanned = FileScanner.ScannedFile(file, relativePath),
                        pair = matchingPair,
                        driveFolderId = targetFolderId
                    )
                )
            }

            totalFiles = filesToUpload.size
            if (totalFiles == 0) {
                reportProgress(STATUS_COMPLETE, "Failed files no longer exist on device", "", 0, 0, 0, 0, 0)
                return Result.success(buildOutputData(0))
            }
        } else {
            // ═══════ Normal Mode: Fast local scan ═══════
            reportProgress(STATUS_SCANNING, "Scanning device for files…", "", 0, 0, 0, 0, 0)
            safeSetForeground("Scanning device for files…")

            val allFiles = mutableListOf<FileItem>()
            for (pair in pairs) {
                val localRoot = File(pair.localPath)
                if (!localRoot.exists()) continue
                val files = scanner.scan(localRoot)
                for (scanned in files) {
                    allFiles.add(FileItem(scanned, pair))
                }
            }

            totalFiles = allFiles.size
            if (totalFiles == 0) {
                reportProgress(STATUS_COMPLETE, "No files to sync", "", 0, 0, 0, 0, 0)
                return Result.success(buildOutputData(0))
            }

            // ═══════ Batch Drive check folder-by-folder ═══════
            val forceSync = inputData.getBoolean(KEY_FORCE_SYNC, false)

            val groupedByFolder = allFiles.groupBy { item ->
                val parentRel = File(item.scanned.relativePath).parent ?: ""
                Pair(item.pair.id, parentRel)
            }

            val totalFolders = groupedByFolder.size
            var foldersProcessed = 0

            reportProgress(
                STATUS_SCANNING,
                "Verifying $totalFiles files with Drive across $totalFolders folders…",
                "", 0, totalFiles, 0, 0, 0
            )
            safeSetForeground("Verifying with Google Drive…")

            for ((key, itemsInFolder) in groupedByFolder) {
                if (isStopped) break

                val pairId = key.first
                val relFolder = key.second
                val pair = itemsInFolder.first().pair
                val resolver = pairResolvers[pairId]!!

                val targetFolderId = if (relFolder.isEmpty()) {
                    pair.driveFolderId
                } else {
                    resolver.resolvePath(relFolder)
                }

                val driveFolderFiles = if (forceSync) {
                    emptyMap()
                } else {
                    resolver.getFolderContents(targetFolderId)
                }

                val verifiedInFolder = mutableListOf<SyncedFile>()
                val now = System.currentTimeMillis()

                for (item in itemsInFolder) {
                    val file = item.scanned.file
                    val driveFileInfo = driveFolderFiles[file.name]
                    val syncedRecord = syncedDao.findByPath(file.path)

                    val isUpToDate = if (forceSync) {
                        false
                    } else if (driveFileInfo != null) {
                        val sizeMatches = (driveFileInfo.size == file.length())
                        val timestampMatches = (syncedRecord != null && syncedRecord.size == file.length() && syncedRecord.lastModified == file.lastModified())
                        sizeMatches && (timestampMatches || syncedRecord == null)
                    } else {
                        false
                    }

                    if (isUpToDate && driveFileInfo != null) {
                        atomicSkipped.incrementAndGet()
                        verifiedInFolder.add(
                            SyncedFile(
                                localPath = file.path,
                                driveFileId = driveFileInfo.id,
                                driveFolderId = targetFolderId,
                                size = file.length(),
                                lastModified = file.lastModified(),
                                syncedAt = now
                            )
                        )
                    } else {
                        filesToUpload.add(
                            UploadTask(
                                scanned = item.scanned,
                                pair = pair,
                                driveFolderId = targetFolderId,
                                existingDriveId = driveFileInfo?.id
                            )
                        )
                    }
                }

                if (verifiedInFolder.isNotEmpty()) {
                    syncedDao.upsertAll(verifiedInFolder)
                }

                foldersProcessed++
                val skippedSoFar = atomicSkipped.get()
                reportProgress(
                    STATUS_SCANNING,
                    "Checked $foldersProcessed/$totalFolders folders ($skippedSoFar on Drive, ${filesToUpload.size} to upload)",
                    pair.localDisplayName,
                    skippedSoFar,
                    totalFiles,
                    0,
                    skippedSoFar,
                    0
                )
            }

            val totalSkipped = atomicSkipped.get()
            android.util.Log.d("SyncWorker", "Pre-check done: $totalSkipped already on Drive, ${filesToUpload.size} to upload")

            if (filesToUpload.isEmpty()) {
                reportProgress(STATUS_COMPLETE, "All $totalFiles files up to date on Drive", "", totalFiles, totalFiles, 0, totalSkipped, 0)
                safeSetForeground("All $totalFiles files up to date")
                notifySyncComplete("SyncDrive", "All $totalFiles files are up to date on Drive ✓")
                return Result.success(buildOutputData(totalFiles))
            }
        }

        // ═══════ Phase 3: Simultaneous 4-thread parallel uploads ═══════
        val toUploadCount = filesToUpload.size
        val alreadySkipped = atomicSkipped.get()
        reportProgress(
            STATUS_SYNCING,
            "Uploading $toUploadCount files (4 simultaneous)…",
            "",
            alreadySkipped,
            totalFiles,
            0,
            alreadySkipped,
            0
        )
        safeSetForeground("Uploading $toUploadCount files (4 parallel)…")

        val uploadChannel = Channel<UploadTask>(Channel.UNLIMITED)
        for (task in filesToUpload) {
            uploadChannel.send(task)
        }
        uploadChannel.close()

        val lastProgressBroadcast = AtomicInteger(0)

        suspend fun broadcastUploadProgress(force: Boolean = false) {
            val uploaded = atomicUploaded.get()
            val skipped = atomicSkipped.get()
            val failed = atomicFailed.get()
            val done = skipped + uploaded + failed

            val currentCounter = uploaded + failed
            if (!force && currentCounter == lastProgressBroadcast.get() && activeSlots.isEmpty()) {
                return
            }
            lastProgressBroadcast.set(currentCounter)

            reportProgressWithSlots(
                status = STATUS_SYNCING,
                currentFile = "Uploading $uploaded of $toUploadCount files (${activeSlots.size} active)",
                currentPair = "",
                done = done,
                total = totalFiles,
                uploaded = uploaded,
                skipped = skipped,
                failed = failed
            )
        }

        coroutineScope {
            val workers = List(PARALLEL_UPLOADS) { slotIndex ->
                launch(Dispatchers.IO) {
                    for (task in uploadChannel) {
                        if (isStopped) break

                        val file = task.scanned.file
                        val transfer = ActiveTransfer(
                            fileName = file.name,
                            bytesUploaded = 0L,
                            totalBytes = file.length()
                        )
                        activeSlots[slotIndex] = transfer
                        broadcastUploadProgress(force = true)

                        try {
                            var lastChunkTime = 0L
                            val fileId = uploadManager.uploadFile(
                                localFile = file,
                                driveFolderId = task.driveFolderId,
                                existingDriveFileId = task.existingDriveId
                            ) { bytesUploaded, totalBytes ->
                                transfer.bytesUploaded = bytesUploaded
                                val now = System.currentTimeMillis()
                                if (now - lastChunkTime > 250) {
                                    lastChunkTime = now
                                    runBlocking { broadcastUploadProgress(force = true) }
                                }
                            }

                            // Upload succeeded
                            syncedDao.upsert(
                                SyncedFile(
                                    localPath = file.path,
                                    driveFileId = fileId,
                                    driveFolderId = task.driveFolderId,
                                    size = file.length(),
                                    lastModified = file.lastModified(),
                                    syncedAt = System.currentTimeMillis()
                                )
                            )
                            pairResolvers[task.pair.id]?.addToCache(task.driveFolderId, file.name, file.length(), fileId)
                            atomicUploaded.incrementAndGet()

                            // Remove from failed list if this was a retry
                            logDao.removeFailed(file.path)

                            logDao.insert(
                                SyncLog(
                                    fileName = file.name,
                                    filePath = file.path,
                                    action = "uploaded",
                                    fileSize = file.length(),
                                    syncPairName = task.pair.localDisplayName
                                )
                            )
                        } catch (e: Exception) {
                            atomicFailed.incrementAndGet()
                            android.util.Log.e("SyncWorker", "Upload failed for ${file.path}", e)
                            logDao.insert(
                                SyncLog(
                                    fileName = file.name,
                                    filePath = file.path,
                                    action = "failed",
                                    fileSize = file.length(),
                                    errorMessage = e.message,
                                    syncPairName = task.pair.localDisplayName
                                )
                            )
                        } finally {
                            activeSlots.remove(slotIndex)
                            broadcastUploadProgress(force = true)
                        }
                    }
                }
            }
            workers.joinAll()
        }

        // ═══════ Done ═══════
        val finalUploaded = atomicUploaded.get()
        val finalSkipped = atomicSkipped.get()
        val finalFailed = atomicFailed.get()

        reportProgress(
            STATUS_COMPLETE,
            "Sync complete",
            "",
            totalFiles,
            totalFiles,
            finalUploaded,
            finalSkipped,
            finalFailed
        )
        safeSetForeground("Done: $finalUploaded ↑  $finalSkipped ⊘  $finalFailed ✕")
        notifySyncComplete(
            "SyncDrive",
            "Sync complete: $finalUploaded uploaded, $finalSkipped skipped, $finalFailed failed"
        )

        // Clean old logs
        logDao.deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

        return Result.success(buildOutputData(totalFiles))
    }

    private fun buildOutputData(totalFiles: Int) = Data.Builder()
        .putString(KEY_STATUS, STATUS_COMPLETE)
        .putInt(KEY_FILES_UPLOADED, atomicUploaded.get())
        .putInt(KEY_FILES_SKIPPED, atomicSkipped.get())
        .putInt(KEY_FILES_FAILED, atomicFailed.get())
        .putInt(KEY_FILES_TOTAL, totalFiles)
        .build()

    private suspend fun reportProgress(
        status: String, currentFile: String, currentPair: String,
        done: Int, total: Int, uploaded: Int, skipped: Int, failed: Int
    ) {
        runCatching {
            setProgress(
                Data.Builder()
                    .putString(KEY_STATUS, status)
                    .putString(KEY_CURRENT_FILE, currentFile)
                    .putString(KEY_CURRENT_PAIR, currentPair)
                    .putInt(KEY_FILES_DONE, done)
                    .putInt(KEY_FILES_TOTAL, total)
                    .putInt(KEY_FILES_UPLOADED, uploaded)
                    .putInt(KEY_FILES_SKIPPED, skipped)
                    .putInt(KEY_FILES_FAILED, failed)
                    .putInt(KEY_ACTIVE_COUNT, 0)
                    .build()
            )
        }
    }

    private suspend fun reportProgressWithSlots(
        status: String, currentFile: String, currentPair: String,
        done: Int, total: Int, uploaded: Int, skipped: Int, failed: Int
    ) {
        runCatching {
            val builder = Data.Builder()
                .putString(KEY_STATUS, status)
                .putString(KEY_CURRENT_FILE, currentFile)
                .putString(KEY_CURRENT_PAIR, currentPair)
                .putInt(KEY_FILES_DONE, done)
                .putInt(KEY_FILES_TOTAL, total)
                .putInt(KEY_FILES_UPLOADED, uploaded)
                .putInt(KEY_FILES_SKIPPED, skipped)
                .putInt(KEY_FILES_FAILED, failed)
                .putInt(KEY_ACTIVE_COUNT, activeSlots.size)

            for (i in 0 until PARALLEL_UPLOADS) {
                val slot = activeSlots[i]
                val key = when (i) {
                    0 -> KEY_SLOT_0
                    1 -> KEY_SLOT_1
                    2 -> KEY_SLOT_2
                    else -> KEY_SLOT_3
                }
                if (slot != null) {
                    builder.putString(key, "${slot.fileName}|${slot.bytesUploaded}|${slot.totalBytes}")
                } else {
                    builder.putString(key, "")
                }
            }

            setProgress(builder.build())
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "SyncDrive Sync", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SyncDrive")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun safeSetForeground(message: String) {
        runCatching {
            setForeground(createForegroundInfo(message))
        }.onFailure { e ->
            android.util.Log.d("SyncWorker", "safeSetForeground skipped (background restrictions): ${e.message}")
        }
    }

    private fun notifySyncComplete(title: String, message: String) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "SyncDrive Sync", NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID + 1, notification)
        }
    }
}
