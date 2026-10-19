package com.example.drivesyncfix

import android.content.Context
import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves local folder paths to Drive folder IDs, creating folders as needed.
 * Uses the Google Drive client library for batch folder listing — one API call
 * per folder fetches up to 1000 files, cached in memory for instant lookups.
 *
 * Thread-safe for parallel operations.
 */
class DriveFolderResolver(
    private val context: Context,
    private val rootFolderId: String
) {
    companion object {
        private const val TAG = "DriveFolderResolver"
        private const val PAGE_SIZE = 1000
    }

    data class DriveFileInfo(
        val id: String,
        val size: Long
    )

    // Thread-safe caches
    private val folderCache = ConcurrentHashMap<String, String>().apply {
        put("", rootFolderId)
    }

    // folderId -> Map<fileName -> DriveFileInfo>
    private val folderContentsCache = ConcurrentHashMap<String, Map<String, DriveFileInfo>>()

    // Reuse a single Drive service instance with backoff and retry
    private val driveService: Drive by lazy {
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        Drive.Builder(transport, jsonFactory) { request ->
            request.headers.authorization = "Bearer ${AuthManager.getFreshAccessToken(context)}"
            request.numberOfRetries = 5

            val backOff = com.google.api.client.util.ExponentialBackOff.Builder()
                .setInitialIntervalMillis(1000)
                .setMultiplier(2.0)
                .setMaxIntervalMillis(15000)
                .setMaxElapsedTimeMillis(60000)
                .build()

            val backOffHandler = com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler(backOff).apply {
                setBackOffRequired { response ->
                    val code = response.statusCode
                    code == 403 || code == 429 || code in 500..599
                }
            }

            request.unsuccessfulResponseHandler = com.google.api.client.http.HttpUnsuccessfulResponseHandler { req, res, supportsRetry ->
                if (res.statusCode == 401) {
                    val freshToken = AuthManager.refreshAccessToken(context)
                    req.headers.authorization = "Bearer $freshToken"
                    true
                } else {
                    backOffHandler.handleResponse(req, res, supportsRetry)
                }
            }

            request.ioExceptionHandler = com.google.api.client.http.HttpBackOffIOExceptionHandler(backOff)
        }
            .setApplicationName("SyncDrive")
            .build()
    }

    fun resolveFolderFor(relativeFilePath: String): String {
        val parentPath = File(relativeFilePath).parent ?: return rootFolderId
        return resolvePath(parentPath)
    }

    @Synchronized
    fun resolvePath(path: String): String {
        folderCache[path]?.let { return it }

        val parentPath = File(path).parent ?: ""
        val parentId = if (parentPath.isEmpty()) rootFolderId else resolvePath(parentPath)
        val folderName = File(path).name

        val existingId = findChildFolder(parentId, folderName)
        val folderId = existingId ?: createFolder(parentId, folderName)

        folderCache[path] = folderId
        return folderId
    }

    /**
     * Checks if a file with this name exists on Drive.
     */
    fun findExistingFile(folderId: String, fileName: String): DriveFileInfo? {
        val contents = getFolderContents(folderId)
        return contents[fileName]
    }

    /**
     * Batch-fetch ALL files in a Drive folder using the client library.
     * Returns a map of fileName -> DriveFileInfo(id, size).
     */
    @Synchronized
    fun getFolderContents(folderId: String): Map<String, DriveFileInfo> {
        folderContentsCache[folderId]?.let { return it }

        val drive = driveService
        val fileMap = mutableMapOf<String, DriveFileInfo>()
        var pageToken: String? = null
        var totalFetched = 0

        try {
            do {
                val result = drive.files().list()
                    .setQ("'$folderId' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'")
                    .setFields("nextPageToken, files(id, name, size)")
                    .setPageSize(PAGE_SIZE)
                    .apply { if (pageToken != null) this.pageToken = pageToken }
                    .execute()

                for (file in result.files ?: emptyList()) {
                    val size = file.getSize() ?: 0L
                    if (file.name != null && file.id != null) {
                        fileMap[file.name] = DriveFileInfo(file.id, size)
                    }
                    totalFetched++
                }

                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list folder $folderId: ${e.message}", e)
        }

        Log.d(TAG, "Cached $totalFetched files from Drive folder $folderId")
        folderContentsCache[folderId] = fileMap
        return fileMap
    }

    @Synchronized
    fun addToCache(folderId: String, fileName: String, fileSize: Long, fileId: String) {
        val contents = (folderContentsCache[folderId] ?: emptyMap()).toMutableMap()
        contents[fileName] = DriveFileInfo(fileId, fileSize)
        folderContentsCache[folderId] = contents
    }

    private fun findChildFolder(parentId: String, name: String): String? {
        val drive = driveService
        val safeName = name.replace("'", "\\'")
        val result = drive.files().list()
            .setQ("'$parentId' in parents and name = '$safeName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .setFields("files(id)")
            .setPageSize(1)
            .execute()

        return result.files?.firstOrNull()?.id
    }

    private fun createFolder(parentId: String, name: String): String {
        val drive = driveService
        val folderMetadata = DriveFile().apply {
            this.name = name
            this.mimeType = "application/vnd.google-apps.folder"
            this.parents = listOf(parentId)
        }
        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        Log.d(TAG, "Created Drive folder '$name' -> ${folder.id}")
        return folder.id
    }
}
