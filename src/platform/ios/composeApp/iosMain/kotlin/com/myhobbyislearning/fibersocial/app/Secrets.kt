package com.myhobbyislearning.fibersocial.app

import platform.Foundation.NSBundle

/**
 * Ravelry OAuth credentials, injected at build time: the gitignored
 * `Config.local.xcconfig` sets `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET`, which
 * Info.plist maps to these keys — the iOS analog of Android's `local.properties` →
 * `BuildConfig` pipeline (see src/platform/ios/README.md).
 */
internal fun ravelryClientId(): String = infoPlistString("RavelryClientId")

internal fun ravelryClientSecret(): String = infoPlistString("RavelryClientSecret")

internal fun infoPlistString(key: String): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""
