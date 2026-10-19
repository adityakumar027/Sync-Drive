package com.example.drivesyncfix

import java.io.File

/**
 * Walks every folder under a given local root directory and returns the files
 * to sync, with each file's path relative to that root (used to mirror the
 * folder structure into the paired Drive destination folder).
 *
 * Excludes system/app/cache directories and hidden junk files that don't
 * belong in a Drive backup. Matches original DriveSync behavior.
 */
class FileScanner(
    private val excludedDirNames: Set<String> = DEFAULT_EXCLUDED_DIRS,
    private val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {
    companion object {
        /** Directories to skip entirely (matched against folder name or path segment) */
        val DEFAULT_EXCLUDED_DIRS = setOf(
            // Android system & app data (NOT Android/media — that has user content!)
            "Android/data",     // app-private data/caches
            "Android/obb",      // app expansion files
            ".android",

            // Cache & temp
            ".cache",
            "cache",
            ".tmp",
            "tmp",
            "temp",

            // Thumbnails & trash
            ".thumbnails",
            ".trashed",
            ".trash",
            ".Trash",

            // App-specific junk & caches
            ".facebook",
            ".googlequicksearchbox",
            ".org.chromium.webview",
            ".com.google.firebase",
            "lost+found",

            // Common app caches
            "GBWhatsApp/Cache",
            "WhatsApp/Cache",
            "Telegram/Telegram Video/cache",

            // Version control (if any)
            ".git",
            ".svn",

            // IDE / build artifacts
            ".gradle",
            ".idea",
            "build",
            "node_modules"
        )

        /** File extensions to skip (logs, temp files, partial downloads, chunks, etc.) */
        val DEFAULT_EXCLUDED_EXTENSIONS = setOf(
            "tmp", "temp", "bak", "log", "part",
            "crdownload", "download",  // partial downloads
            "nomedia",                 // .nomedia marker files
            "thumb", "thumbdata",      // thumbnail data
            "pending", "partial",
            "chunk"                    // temporary chunks
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

        // Skip hidden directories (starting with .)
        if (name.startsWith(".")) return true

        // Skip by exact name match
        if (name in excludedDirNames) return true

        // Skip by path segment match (for nested patterns like "Android/data")
        return excludedDirNames.any { excluded ->
            excluded.contains("/") && dir.path.contains("/$excluded")
        }
    }

    /** Check if a file should be skipped */
    private fun isFileExcluded(file: File): Boolean {
        if (!file.canRead()) return true
        val name = file.name
        val ext = file.extension.lowercase()

        // Skip hidden files (starting with .)
        if (name.startsWith(".")) return true

        // Skip excluded extensions
        if (ext in excludedExtensions) return true

        // Skip thumbnail database, cache and chunk files
        if (name.startsWith("thumbdata") || name == ".thumbdata" || name.contains(".chunk", ignoreCase = true)) {
            return true
        }

        return false
    }
}
