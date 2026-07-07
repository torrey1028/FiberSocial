package com.autom8ed.fibersocial.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun ravelryHttpClient(): HttpClient = HttpClient(Darwin) {
    installRavelryDefaults()
}
