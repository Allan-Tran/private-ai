package com.privateai.vault

import platform.posix.*
import kotlinx.cinterop.*

class NativePlatform : Platform {
    override val name: String = "Native (${getPlatformName()})"
    override val type: PlatformType = when {
        getPlatformName().contains("Windows") -> PlatformType.WINDOWS
        getPlatformName().contains("macOS") -> PlatformType.MACOS
        else -> PlatformType.LINUX
    }

    private fun getPlatformName(): String = memScoped {
        val info = alloc<utsname>()
        uname(info.ptr)
        info.sysname.toKString()
    }
}

actual fun getPlatform(): Platform = NativePlatform()

actual class FileSystemAccess {
    actual fun getPrivateDataDirectory(): String {
        return getenv("HOME")?.toKString() + "/.privateaivault"
    }

    actual fun getTempDirectory(): String {
        return "/tmp"
    }

    actual suspend fun fileExists(path: String): Boolean {
        return access(path, F_OK) == 0
    }

    actual suspend fun readBytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: throw IllegalStateException("Cannot open file: $path")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)

            val buffer = ByteArray(size)
            buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file)
            }
            return buffer
        } finally {
            fclose(file)
        }
    }

    actual suspend fun writeBytes(path: String, data: ByteArray) {
        val file = fopen(path, "wb") ?: throw IllegalStateException("Cannot open file: $path")
        try {
            data.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, data.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }
    }

    actual suspend fun deleteFile(path: String): Boolean {
        return remove(path) == 0
    }
}
