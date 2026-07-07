package com.autom8ed.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Exists only so the jvm() target (used by `:common:jvmTest`, never shipped) satisfies
 * the `expect`. The Android app always gets the Android-engine actual.
 */
actual fun ravelryHttpClient(): HttpClient = HttpClient(CIO) {
    installRavelryDefaults()
}
