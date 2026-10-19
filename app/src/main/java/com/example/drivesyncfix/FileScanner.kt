package com.example.drivesyncfix

import java.io.File

/**
 * Walks folders under a given local root directory and returns the files
 * to sync, with each file's path relative to that root (used to mirror the
 * folder structure into the paired Drive destination folder).
 *
 * SMART FILTERING:
 * 1. Excludes system caches, thumbnail databases, and temp chunk files that clutter Drive.
 * 2. Smart Media Inclusion: If a file is a valid photo, audio, video, or user document,
 *    it IS included even if it starts with a dot (.) or is inside a hidden/vault folder.
 * 3. Strictly excludes actual system trash and gallery thumbnail caches (.thumbnails/)
 *    to prevent uploading thousands of tiny 800-byte preview caches.
 */
class FileScanner(
    private val excludedDirNames: Set<String> = DEFAULT_EXCLUDED_DIRS,
    private val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {
    companion object {
        /** Directories that must ALWAYS be skipped entirely (system caches, trash, gallery thumbnails) */
        val DEFAULT_EXCLUDED_DIRS = setOf(
            // Android system & app data
            "Android/data",
            "Android/obb",
            ".android",

            // Gallery thumbnail preview caches (contains thousands of 1KB-20KB preview thumbnails)
            ".thumbnails",
            ".trashed",
            ".trash",
            ".Trash",

            // System and app caches
            ".cache",
            "cache",
            ".tmp",
            "tmp",
            "temp",
            ".facebook",
            ".googlequicksearchbox",
            ".org.chromium.webview",
            ".com.google.firebase",
            "lost+found",

            // Common app internal caches
            "GBWhatsApp/Cache",
            "WhatsApp/Cache",
            "Telegram/Telegram Video/cache",

            // Version control & build artifacts
            ".git",
            ".svn",
            ".gradle",
            ".idea",
            "build",
            "node_modules"
        )

        /** File extensions that must ALWAYS be skipped (temporary files, partial downloads, chunks) */
        val DEFAULT_EXCLUDED_EXTENSIONS = setOf(
            "tmp", "temp", "bak", "log", "part",
            "crdownload", "download",  // partial downloads
            "nomedia",                 // .nomedia marker files themselves (media inside folder is kept)
            "thumb", "thumbdata",      // thumbnail index data
            "pending", "partial",
            "chunk"                    // temporary chunks
        )

        /** Recognized media and user document extensions (kept even if file is hidden/dot-prefixed) */
        val MEDIA_AND_DOC_EXTENSIONS = setOf(
            // Images
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "svg", "bmp", "raw", "dng", "tiff", "ico",
            // Audio
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "wma", "mid", "amr",
            // Video
            "mp4", "mkv", "mov", "avi", "3gp", "webm", "flv", "wmv", "m4v",
            // Documents & Archives
            "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "tar", "gz"
        )
    }

    data class ScannedFile(
        val file: File,
        val relativePath: String // path relative to the sync pair's local root
    )

    /** Scans everything under [root] (the local folder the user picked for this sync pair). */
    fun scan(root: File): List<ScannedFile> {
        val results = mutableListOf<ScannedFile>()
        walk(root, root, results)
        return results
    }

    private fun walk(root: File, dir: File, out: MutableList<ScannedFile>) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                if (!isDirExcluded(entry)) {
                    walk(root, entry, out)
                }
            } else if (entry.isFile && entry.length() > 0) {
                if (!isFileExcluded(entry)) {
                    val relative = entry.relativeTo(root).path
                    out.add(ScannedFile(entry, relative))
                }
            }
        }
    }

    /** Check if a directory should be skipped */
    private fun isDirExcluded(dir: File): Boolean {
        val name = dir.name

        // Check exact excluded directory names (e.g. .thumbnails, .trashed, .cache, Android/data, etc.)
        if (name in excludedDirNames || name.equals(".thumbnails", ignoreCase = true) || name.equals(".trashed", ignoreCase = true)) {
            return true
        }

        // Check path segment match (for nested patterns like "Android/data", "WhatsApp/Cache", etc.)
        if (excludedDirNames.any { excluded -> dir.path.endsWith("/$excluded") || dir.path.contains("/$excluded/") }) {
            return true
        }

        // For other directories starting with a dot (.vault, .secret, .Private), allow exploring them
        // so any real photos, audios, and documents inside are not lost!
        return false
    }

    /** Check if a file should be skipped */
    private fun isFileExcluded(file: File): Boolean {
        if (!file.canRead()) return true
        val name = file.name
        val ext = file.extension.lowercase()

        // Always skip blacklisted temporary and chunk extensions
        if (ext in excludedExtensions) return true

        // Always skip system thumbnail database files
        if (name.startsWith("thumbdata") || name == ".thumbdata" || name.contains(".chunk", ignoreCase = true)) {
            return true
        }

        // If file starts with a dot (hidden file):
        if (name.startsWith(".")) {
            // If it is a known media or document (e.g. .photo.jpg, .secret_audio.mp3, .notes.pdf), KEEP IT!
            if (ext in MEDIA_AND_DOC_EXTENSIONS) {
                return false
            }
            // Otherwise, it's an OS hidden file (.DS_Store, .nomedia, .bindata, etc.) -> skip it
            return true
        }

        return false
    }
}
