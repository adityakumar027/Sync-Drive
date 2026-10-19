package com.example.drivesyncfix

import java.io.File

/**
 * Walks every folder and subfolder under a given local root directory and returns
 * all files to sync, with each file's path relative to that root (used to mirror
 * the exact folder hierarchy into the paired Drive destination folder).
 *
 * Ensures all files are discovered and uploaded without skipping valid user files.
 */
class FileScanner(
    private val excludedDirNames: Set<String> = DEFAULT_EXCLUDED_DIRS,
    private val excludedExtensions: Set<String> = DEFAULT_EXCLUDED_EXTENSIONS
) {
    companion object {
        /** System/OS restricted paths that cannot be accessed or contain system caches */
        val DEFAULT_EXCLUDED_DIRS = setOf(
            "Android/data",
            "Android/obb",
            ".thumbnails",
            ".trashed",
            ".Trash"
        )

        /** Incomplete download marker extensions (actively downloading browser parts) */
        val DEFAULT_EXCLUDED_EXTENSIONS = setOf(
            "crdownload", "part", "download"
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
            } else if (entry.isFile && entry.length() >= 0L) {
                // Allows all files (including 0-byte notes, docs, configs)
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

        // Skip trash and thumbnail folders
        if (name.equals(".trashed", ignoreCase = true) || name.equals(".thumbnails", ignoreCase = true)) {
            return true
        }

        // Skip OS protected Android/data and Android/obb if scanning root volume
        return excludedDirNames.any { excluded ->
            dir.path.endsWith("/$excluded") || dir.path.contains("/$excluded/")
        }
    }

    /** Check if a file should be skipped */
    private fun isFileExcluded(file: File): Boolean {
        // Skip unreadable files or actively downloading temporary parts
        if (!file.canRead()) return true
        val ext = file.extension.lowercase()
        if (ext in excludedExtensions) return true
        return false
    }
}
