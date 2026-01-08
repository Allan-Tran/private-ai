package com.privateai.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DesktopPlatform : Platform {
    override val name: String = "Desktop (${System.getProperty("os.name")})"
    override val type: PlatformType = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> PlatformType.WINDOWS
        System.getProperty("os.name").contains("Mac", ignoreCase = true) -> PlatformType.MACOS
        else -> PlatformType.LINUX
    }
}

actual fun getPlatform(): Platform = DesktopPlatform()

actual class FileSystemAccess {
    private val appDataDir: String by lazy {
        val userHome = System.getProperty("user.home")
        when (getPlatform().type) {
            PlatformType.WINDOWS -> "$userHome\\AppData\\Local\\PrivateAIVault"
            PlatformType.MACOS -> "$userHome/Library/Application Support/PrivateAIVault"
            else -> "$userHome/.privateaivault"
        }.also { File(it).mkdirs() }
    }

    actual fun getPrivateDataDirectory(): String = appDataDir

    actual fun getTempDirectory(): String {
        return System.getProperty("java.io.tmpdir")
    }

    actual suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).exists()
    }

    actual suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    actual suspend fun writeBytes(path: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        File(path).apply {
            parentFile?.mkdirs()
            writeBytes(data)
        }
        Unit
    }

    actual suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).delete()
    }
}
