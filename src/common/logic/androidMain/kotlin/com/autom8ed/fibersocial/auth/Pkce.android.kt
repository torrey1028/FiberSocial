package com.autom8ed.fibersocial.auth

import java.security.MessageDigest
import java.security.SecureRandom

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    SecureRandom().nextBytes(bytes)
    return bytes
}
