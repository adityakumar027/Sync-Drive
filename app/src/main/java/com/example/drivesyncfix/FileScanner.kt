package com.example.drivesyncfix

import java.io.File

/**
 * Walks folders under a given local root directory and returns the files
 * to sync, with each file's path relative to that root (used to mirror the
 * folder structure into the paired Drive destination folder).
 *
 * FILTERING RULES:
 * 1. Excludes system caches, thumbnail preview caches (.thumbnails/), OS trash, and temp chunk files.
 * 2. Allows important files (images, audio, video, documents, archives) starting with a dot (.)
 *    if [includeHidden] is enabled (Default: true).
 * 3. Skips non-media hidden files (e.g. .nomedia, .DS_Store, extensionless dotfiles, chunk files).
 */
class FileScanner(
    val includeAllChunks: Boolean = false,
    private val excludedDirNames: Set<String> = DEFAULT_EXCLUDED_DIRS,
    private val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {
    companion object {
        /** Directories to skip in default safe mode */
        val DEFAULT_EXCLUDED_DIRS = setOf(
            // Android system & app data (NOT Android/media — that has user content!)
            "Android/data",
            "Android/obb",
            ".android",

            // Gallery thumbnail preview caches (contains thousands of 800B-20KB preview thumbnails)
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

        /** File extensions to skip in default safe mode */
        val DEFAULT_EXCLUDED_EXTENSIONS = setOf(
            "tmp", "temp", "bak", "log", "part",
            "crdownload", "download",  // partial downloads
            "nomedia",                 // .nomedia marker files
            "thumb", "thumbdata",      // thumbnail data
            "pending", "partial",
            "chunk", "chunks", "chk", "seg"
        )

        /** Important user file extensions (strictly allowed regardless of starting with dot or not) */
        val ALLOWED_IMPORTANT_EXTENSIONS = setOf(
            // Images
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "svg", "bmp", "raw", "dng", "tiff", "ico",
            // Audio
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "wma", "mid", "amr",
            // Video
            "mp4", "mkv", "mov", "avi", "3gp", "webm", "flv", "wmv", "m4v", "ts",
            // Documents & Archives
            "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv", "rtf", "epub", "md",
            "zip", "rar", "7z", "tar", "gz"
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
        // When includeAllChunks is ON, include all directories
        if (includeAllChunks) return false

        val name = dir.name
        val lowerName = name.lowercase()

        // 1. Skip exact excluded names
        if (name in excludedDirNames) return true

        // 2. Skip any directory that is a thumbnail, cache, or trash folder
        if (lowerName == ".thumbnails" || lowerName == ".trashed" || lowerName == ".cache" ||
            lowerName.contains("cache") || lowerName.contains("thumb") || lowerName.contains("trash")
        ) {
            return true
        }

        // 3. Skip by path segment match (for nested patterns like "Android/data", "WhatsApp/Cache", etc.)
        if (excludedDirNames.any { excluded -> dir.path.endsWith("/$excluded") || dir.path.contains("/$excluded/") }) {
            return true
        }

        return false
    }

    /** Check if a file should be skipped */
    private fun isFileExcluded(file: File): Boolean {
        if (!file.canRead()) return true

        // When includeAllChunks is ON, include ALL files irrespective of hidden, chunk, or cache
        if (includeAllChunks) return false

        val name = file.name
        val ext = file.extension.lowercase()
        val lowerName = name.lowercase()

        // 1. Skip blacklisted temporary and chunk extensions
        if (ext in excludedExtensions) return true

        // 2. Skip files with "chunk", ".part", or "thumbdata" in their name
        if (lowerName.contains("chunk") || lowerName.contains(".part") || lowerName.contains("thumbdata")) {
            return true
        }

        // 3. Skip thumbnail database files
        if (lowerName.startsWith("thumbdata") || lowerName == ".thumbdata") {
            return true
        }

        // 4. If file has an allowed important media/document extension, strictly ALLOW IT regardless of starting with dot
        if (ext in ALLOWED_IMPORTANT_EXTENSIONS) {
            return false
        }

        // 5. If file starts with dot (.) and is NOT an allowed extension, skip it (.nomedia, .DS_Store, etc.)
        if (name.startsWith(".")) {
            return true
        }

        return false
    }
}
