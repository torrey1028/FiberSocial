package com.myhobbyislearning.fibersocial.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Launches the platform photo picker; [onImagePicked] receives an opaque platform URI
 * string for the chosen image (Android `content://` URI today). A string rather than a
 * platform URI type so picker-driven screens stay in commonMain.
 */
@Composable
expect fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit

/**
 * Inserts a [ImageAttachmentState.Ready] attachment's markdown into the host composer's
 * draft and acknowledges it. Shared by both composers so the insert-then-acknowledge
 * handshake can't drift between them. [onInsert] is re-read on every recomposition
 * (not captured at effect launch), so it always sees the draft's latest text even if a
 * keystroke lands between the Ready state arriving and this effect running.
 */
@Composable
internal fun InsertAttachmentEffect(
    attachment: ImageAttachmentState,
    onInsert: (markdown: String) -> Unit,
    onInserted: () -> Unit,
) {
    val currentOnInsert by rememberUpdatedState(onInsert)
    val currentOnInserted by rememberUpdatedState(onInserted)
    LaunchedEffect(attachment) {
        if (attachment is ImageAttachmentState.Ready) {
            currentOnInsert(attachment.markdown)
            currentOnInserted()
        }
    }
}

/**
 * Attach-image control shared by the two composers. Swaps to a progress spinner while
 * an upload is in flight. With [onPickFromProjects] provided, tapping opens a menu
 * offering a device upload (needs Ravelry Extras to post) or a photo from the user's
 * projects (free); without it, tapping launches the system photo picker directly.
 * The system picker needs no storage permission.
 */
@Composable
internal fun AttachImageButton(
    attachment: ImageAttachmentState,
    enabled: Boolean,
    onImagePicked: (String) -> Unit,
    onPickFromProjects: (() -> Unit)? = null,
) {
    val launchPicker = rememberImagePicker(onImagePicked)
    if (attachment is ImageAttachmentState.Uploading) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
        return
    }
    // Box: the DropdownMenu anchors to its parent layout node.
    Box {
        var menuOpen by remember { mutableStateOf(false) }
        IconButton(
            onClick = { if (onPickFromProjects == null) launchPicker() else menuOpen = true },
            enabled = enabled,
        ) {
            Icon(AttachImageIcon, contentDescription = "Attach image")
        }
        if (onPickFromProjects != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("From your projects") },
                    onClick = {
                        menuOpen = false
                        onPickFromProjects()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Upload from device") },
                    onClick = {
                        menuOpen = false
                        launchPicker()
                    },
                )
            }
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
