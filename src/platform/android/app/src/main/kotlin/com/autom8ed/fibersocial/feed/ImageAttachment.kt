package com.autom8ed.fibersocial.feed

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An image picked from the device, read into memory for upload.
 *
 * @property fileName Display name reported by the content provider.
 * @property contentType MIME type reported by the content provider.
 */
class PickedImage(val fileName: String, val contentType: String, val bytes: ByteArray)

/**
 * Reads the image behind a photo-picker [uri] into memory for upload, or `null` if the
 * content provider can't serve it (revoked permission, deleted file, provider crash).
 */
suspend fun readImageForUpload(context: Context, uri: Uri): PickedImage? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val contentType = resolver.getType(uri) ?: "image/jpeg"
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "image"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        PickedImage(fileName, contentType, bytes)
    } catch (e: Exception) {
        println("FiberSocial: readImageForUpload($uri) failed: ${e.message}")
        null
    }
}

/**
 * Attach-image control shared by the two composers: launches the system photo picker
 * (no storage permission needed) and hands the picked URI to [onImagePicked]. Swaps to
 * a progress spinner while the upload is in flight.
 */
@Composable
internal fun AttachImageButton(
    attachment: ImageAttachmentState,
    enabled: Boolean,
    onImagePicked: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImagePicked)
    }
    if (attachment is ImageAttachmentState.Uploading) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
    } else {
        IconButton(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = enabled,
        ) {
            Icon(AttachImageIcon, contentDescription = "Attach image")
        }
    }
}

/**
 * Material's "Image" glyph, defined inline because the app only ships
 * material-icons-core, which has no photo/image icon.
 */
internal val AttachImageIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Image") {
        materialPath {
            moveTo(21.0f, 19.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineTo(14.5f, 12.0f)
            lineToRelative(4.5f, 6.0f)
            horizontalLineTo(5.0f)
            close()
        }
    }
}
