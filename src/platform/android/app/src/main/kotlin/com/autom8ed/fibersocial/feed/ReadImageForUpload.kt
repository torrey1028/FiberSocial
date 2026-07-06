package com.autom8ed.fibersocial.feed

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the image behind a photo-picker [uri] into memory for upload, or `null` if the
 * content provider can't serve it (revoked permission, deleted file, provider crash).
 */
suspend fun readImageForUpload(context: Context, uri: Uri): UploadableImage? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val contentType = resolver.getType(uri) ?: "image/jpeg"
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "image"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            // Stop reading just past the upload cap: the ViewModel rejects anything over
            // MAX_UPLOAD_BYTES with a proper "too large" message, and this avoids
            // materializing an arbitrarily large image (OOM) just to reject it.
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= ImageAttachmentViewModel.MAX_UPLOAD_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: return@withContext null
        UploadableImage(fileName, contentType, bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("FiberSocial: readImageForUpload($uri) failed: ${e.message}")
        null
    }
}
