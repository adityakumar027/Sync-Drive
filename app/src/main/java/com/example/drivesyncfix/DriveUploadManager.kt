package com.example.drivesyncfix

import android.content.Context
import android.util.Log
import com.google.api.client.http.*
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Robust upload manager using the official Google Drive Java client library.
 *
 * Resilience features:
 *   - Automatic exponential backoff retry on 403 (Rate Limits) & 429 (Too Many Requests)
 *   - Automatic retry on 5xx (Google backend errors)
 *   - Automatic token refresh & retry on 401 (Expired Token)
 *   - Automatic retry on IOExceptions (SocketTimeout, network dropouts)
 *   - Direct upload for small files (<5MB), chunked resumable upload for large files (no size limit)
 *   - In-place file update when file already exists on Drive to maintain true data consistency without duplicates
 */
class DriveUploadManager(
    private val context: Context,
    private val accessTokenProvider: () -> String
) {
    companion object {
        private const val TAG = "DriveUploadManager"
        private const val DIRECT_UPLOAD_LIMIT = 5L * 1024 * 1024  // 5 MB
        private const val CHUNK_SIZE = 8 * 1024 * 1024  // 8 MB chunks
        private const val MAX_RETRIES = 5
    }

    private val driveService: Drive by lazy {
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        Drive.Builder(transport, jsonFactory) { request ->
            request.headers.authorization = "Bearer ${accessTokenProvider()}"
            request.numberOfRetries = MAX_RETRIES

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s... up to 60s
            val backOff = ExponentialBackOff.Builder()
                .setInitialIntervalMillis(1000)
                .setMultiplier(2.0)
                .setMaxIntervalMillis(15000)
                .setMaxElapsedTimeMillis(60000)
                .build()

            val backOffHandler = HttpBackOffUnsuccessfulResponseHandler(backOff).apply {
                setBackOffRequired { response ->
                    val code = response.statusCode
                    code == 403 || code == 429 || code in 500..599
                }
            }

            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { req, res, supportsRetry ->
                if (res.statusCode == 401) {
                    Log.w(TAG, "Access token expired (401). Refreshing token…")
                    val freshToken = AuthManager.refreshAccessToken(context)
                    req.headers.authorization = "Bearer $freshToken"
                    true
                } else {
                    backOffHandler.handleResponse(req, res, supportsRetry)
                }
            }

            request.ioExceptionHandler = HttpBackOffIOExceptionHandler(backOff)
        }
            .setApplicationName("SyncDrive")
            .build()
    }

    /**
     * Uploads or updates a file on Drive. Handles files of any size with automatic retries.
     * If [existingDriveFileId] is provided, updates the existing file in-place on Drive.
     */
    fun uploadFile(
        localFile: File,
        driveFolderId: String,
        existingDriveFileId: String? = null,
        progressListener: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): String {
        if (!localFile.exists()) {
            throw FileNotFoundException("File no longer exists: ${localFile.absolutePath}")
        }
        if (!localFile.canRead()) {
            throw IOException("File cannot be read (permission or locked): ${localFile.name}")
        }

        var attempt = 0
        var lastException: Exception? = null

        while (attempt < 3) {
            try {
                return executeUpload(localFile, driveFolderId, existingDriveFileId, progressListener)
            } catch (e: Exception) {
                lastException = e
                attempt++
                Log.w(TAG, "Upload attempt $attempt failed for ${localFile.name}: ${e.message}")

                if (attempt < 3) {
                    try {
                        Thread.sleep(1000L * attempt)
                    } catch (_: InterruptedException) {}
                }
            }
        }

        throw lastException ?: IOException("Upload failed after multiple attempts for ${localFile.name}")
    }

    private fun executeUpload(
        localFile: File,
        driveFolderId: String,
        existingDriveFileId: String?,
        progressListener: ((bytesUploaded: Long, totalBytes: Long) -> Unit)?
    ): String {
        val mimeType = guessMimeType(localFile)
        val mediaContent = FileContent(mimeType, localFile)

        val uploader: com.google.api.client.googleapis.media.MediaHttpUploader
        val driveFileId: String

        if (existingDriveFileId != null) {
            // Update existing file on Drive in-place
            val fileMetadata = DriveFile().apply {
                name = localFile.name
            }
            val request = driveService.files().update(existingDriveFileId, fileMetadata, mediaContent)
                .setFields("id")

            uploader = request.mediaHttpUploader
            if (localFile.length() < DIRECT_UPLOAD_LIMIT) {
                uploader.isDirectUploadEnabled = true
            } else {
                uploader.isDirectUploadEnabled = false
                uploader.chunkSize = CHUNK_SIZE
            }

            if (progressListener != null) {
                uploader.setProgressListener { progress ->
                    progressListener(progress.numBytesUploaded, localFile.length())
                }
            }

            val driveFile = request.execute()
            driveFileId = driveFile.id
        } else {
            // Create new file under driveFolderId
            val fileMetadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(driveFolderId)
            }
            val request = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")

            uploader = request.mediaHttpUploader
            if (localFile.length() < DIRECT_UPLOAD_LIMIT) {
                uploader.isDirectUploadEnabled = true
            } else {
                uploader.isDirectUploadEnabled = false
                uploader.chunkSize = CHUNK_SIZE
            }

            if (progressListener != null) {
                uploader.setProgressListener { progress ->
                    progressListener(progress.numBytesUploaded, localFile.length())
                }
            }

            val driveFile = request.execute()
            driveFileId = driveFile.id
        }

        return driveFileId
    }

    private fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
