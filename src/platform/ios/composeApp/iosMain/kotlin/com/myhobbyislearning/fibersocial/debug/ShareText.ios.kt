package com.myhobbyislearning.fibersocial.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.interop.LocalUIViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

@Composable
actual fun rememberShareText(): (text: String) -> Unit {
    val hostController = LocalUIViewController.current
    return { text ->
        val activityController = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        // iPad presents this as a popover, which crashes without an anchor; a no-op on iPhone.
        activityController.popoverPresentationController?.sourceView = hostController.view
        hostController.presentViewController(activityController, animated = true, completion = null)
    }
}
