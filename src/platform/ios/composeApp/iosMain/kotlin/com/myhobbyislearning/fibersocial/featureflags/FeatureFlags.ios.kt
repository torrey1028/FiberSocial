package com.myhobbyislearning.fibersocial.featureflags

import kotlin.experimental.ExperimentalNativeApi

actual object FeatureFlags {
    @OptIn(ExperimentalNativeApi::class)
    actual val messagesEnabled: Boolean = Platform.isDebugBinary

    @OptIn(ExperimentalNativeApi::class)
    actual val activityFeedEnabled: Boolean = Platform.isDebugBinary
}
