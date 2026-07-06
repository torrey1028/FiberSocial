@file:OptIn(ExperimentalForeignApi::class)

package com.autom8ed.fibersocial.feed

import kotlinx.cinterop.ExperimentalForeignApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemProvider
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * System photo picker via `PHPickerViewController` (no photo-library permission
 * needed — the picker runs out of process, like Android's PickVisualMedia).
 *
 * The picked image is copied into the app's tmp directory and its file path is the
 * opaque "uri" string handed to [onImagePicked]; `IosFeedModel` reads it back into an
 * `UploadableImage`. The copy is required: the URL `loadFileRepresentation` vends dies
 * with the completion handler.
 */
@Composable
actual fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit {
    val currentOnPicked = rememberUpdatedState(onImagePicked)
    // remember: PHPickerViewController.delegate is weak; the composition holds the ref.
    val delegate = remember {
        PickerDelegate { path ->
            currentOnPicked.value(path)
        }
    }
    return {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration = configuration)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
            ?: println("FiberSocial: image picker found no view controller to present from")
    }
}

private class PickerDelegate(
    private val onPicked: (String) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return
        val typeIdentifier = provider.registeredTypeIdentifiers.filterIsInstance<String>().firstOrNull()
        if (typeIdentifier == null) {
            println("FiberSocial: picked item has no type identifier")
            return
        }
        provider.loadFileRepresentationForTypeIdentifier(typeIdentifier) { url, error ->
            if (url == null) {
                println("FiberSocial: image picker load failed: ${error?.localizedDescription}")
                return@loadFileRepresentationForTypeIdentifier
            }
            // Unique subpath: picking the same photo twice must not collide with a
            // previous copy still being uploaded.
            val name = url.lastPathComponent ?: "image"
            val destination = NSTemporaryDirectory() + NSUUID().UUIDString + "-" + name
            NSFileManager.defaultManager.copyItemAtURL(
                url,
                toURL = NSURL.fileURLWithPath(destination),
                error = null,
            )
            dispatch_async(dispatch_get_main_queue()) {
                onPicked(destination)
            }
        }
    }
}

/** The view controller currently on top, for modal presentation from Compose. */
private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}
