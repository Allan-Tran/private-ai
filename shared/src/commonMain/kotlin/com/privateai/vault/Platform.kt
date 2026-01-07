package com.privateai.vault

/**
 * Platform-specific functionality expectations.
 * Each platform implements these in their respective sourcesets.
 */
interface Platform {
    val name: String
    val type: PlatformType
}

enum class PlatformType {
    ANDROID,
    WINDOWS,
    MACOS,
    LINUX
}

expect fun getPlatform(): Platform

/**
 * Platform-specific file system access for sovereign data storage.
 */
expect class FileSystemAccess {
    /**
     * Get the application's private data directory where models and vectors are stored.
     */
    fun getPrivateDataDirectory(): String

    /**
     * Get temporary directory for Active Desk workspace.
     */
    fun getTempDirectory(): String

    /**
     * Check if a file exists at the given path.
     */
    suspend fun fileExists(path: String): Boolean

    /**
     * Read file contents as bytes.
     */
    suspend fun readBytes(path: String): ByteArray

    /**
     * Write bytes to a file.
     */
    suspend fun writeBytes(path: String, data: ByteArray)

    /**
     * Delete a file.
     */
    suspend fun deleteFile(path: String): Boolean
}
