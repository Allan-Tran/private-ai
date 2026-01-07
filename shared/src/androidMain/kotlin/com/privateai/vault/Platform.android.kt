package com.privateai.vault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPlatform : Platform {
    override val name: String = "Android ${android.os.Build.VERSION.SDK_INT}"
    override val type: PlatformType = PlatformType.ANDROID
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual class FileSystemAccess(private val context: Context) {
    actual fun getPrivateDataDirectory(): String {
        return context.filesDir.absolutePath
    }

    actual fun getTempDirectory(): String {
        return context.cacheDir.absolutePath
    }

    actual suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).exists()
    }

    actual suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        File(path).readBytes()
    }

    actual suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        File(path).writeBytes(data)
    }

    actual suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).delete()
    }
}
