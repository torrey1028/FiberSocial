package com.myhobbyislearning.fibersocial.featureflags

import com.myhobbyislearning.fibersocial.composeapp.BuildConfig

actual object FeatureFlags {
    actual val messagesEnabled: Boolean = BuildConfig.DEBUG
}
