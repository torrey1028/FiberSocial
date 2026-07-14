package com.myhobbyislearning.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android

actual fun ravelryHttpClient(): HttpClient = HttpClient(Android) {
    installRavelryDefaults()
}
