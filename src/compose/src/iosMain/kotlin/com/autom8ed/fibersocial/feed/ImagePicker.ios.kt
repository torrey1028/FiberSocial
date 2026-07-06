package com.autom8ed.fibersocial.feed

import androidx.compose.runtime.Composable

/**
 * Device photo picking is not wired up on iOS yet — it needs a PHPicker host plus an
 * image-reading bridge, which lands with the parity pass (#119). Attach-from-projects
 * (the picker dialog) works; this stub only disables the "Upload from device" path.
 */
@Composable
actual fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit = {
    println("FiberSocial: device image picker not yet implemented on iOS (#119)")
}
