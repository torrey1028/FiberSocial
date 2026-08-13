package com.myhobbyislearning.fibersocial.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.interop.LocalUIViewController
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication

@Composable
actual fun rememberOpenWebPage(): (url: String) -> Boolean {
    val hostController = LocalUIViewController.current
    return { url ->
        val nsUrl = NSURL(string = url)
        val scheme = nsUrl?.scheme?.lowercase()
        when {
            nsUrl == null -> {
                DebugLog.log("openWebPage could not parse ${describeUrlForLog(url)}")
                false
            }
            // SFSafariViewController raises an ObjC exception on a non-http(s) URL, and
            // an ObjC exception is not catchable from Kotlin/Native — it terminates the
            // app. Every URL routed here today is https, so this guard should never
            // fire; it exists because the cost of being wrong is a crash, not an error.
            scheme != "http" && scheme != "https" -> {
                DebugLog.log("openWebPage: $scheme is not a web scheme, handing to the system")
                UIApplication.sharedApplication.openURL(nsUrl)
                true
            }
            else -> {
                DebugLog.log("openWebPage presenting ${describeUrlForLog(url)}")
                // Presented modally over the current screen rather than replacing it, so
                // dismissing lands the user exactly where they left — the login form, or
                // Settings. This is what makes the sign-up detour a round trip instead of
                // the one-way door that sending them to Safari created.
                hostController.presentViewController(
                    SFSafariViewController(uRL = nsUrl),
                    animated = true,
                    completion = null,
                )
                true
            }
        }
    }
}
