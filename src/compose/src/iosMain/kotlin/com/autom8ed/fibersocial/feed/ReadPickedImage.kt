@file:OptIn(ExperimentalForeignApi::class)

package com.autom8ed.fibersocial.feed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * Reads a PHPicker-copied image file into memory for upload, or `null` when the file
 * can't be read — the iOS analog of Android's `readImageForUpload`.
 *
 * jpeg/png/gif upload verbatim with their original type. Anything else (HEIC most of
 * all — the iPhone camera default) is transcoded to JPEG first: Ravelry's forum
 * uploads expect web formats, and Android never sends HEIC because its photo picker
 * flow hands over JPEG. The tmp file is deleted after reading either way.
 */
suspend fun readPickedImage(path: String): UploadableImage? = withContext(Dispatchers.Default) {
    try {
        val data = NSData.dataWithContentsOfFile(path) ?: run {
            println("FiberSocial: readPickedImage($path) — file unreadable")
            return@withContext null
        }
        val fileName = path.substringAfterLast('/').substringAfter('-')
        val extension = fileName.substringAfterLast('.', "").lowercase()
        when (extension) {
            "jpg", "jpeg" -> UploadableImage(fileName, "image/jpeg", data.toByteArray())
            "png" -> UploadableImage(fileName, "image/png", data.toByteArray())
            "gif" -> UploadableImage(fileName, "image/gif", data.toByteArray())
            else -> {
                val image = UIImage.imageWithData(data) ?: run {
                    println("FiberSocial: readPickedImage($path) — undecodable image ($extension)")
                    return@withContext null
                }
                val jpeg = UIImageJPEGRepresentation(image, 0.9) ?: return@withContext null
                val baseName = fileName.substringBeforeLast('.').ifEmpty { "image" }
                UploadableImage("$baseName.jpg", "image/jpeg", jpeg.toByteArray())
            }
        }
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
    return result
}
