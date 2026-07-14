package com.myhobbyislearning.fibersocial.feedback

import android.os.Build
import com.myhobbyislearning.fibersocial.BuildConfig

/**
 * App version + device/OS line pre-filled into the feedback composer so reports are
 * reproducible — the reduced stand-in for #57's "grab logs". The user can edit or clear it.
 */
fun deviceContext(): String {
    val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    return "App: FiberSocial ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
        "Device: $device · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
