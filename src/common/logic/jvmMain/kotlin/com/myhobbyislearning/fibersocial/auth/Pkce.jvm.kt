package com.myhobbyislearning.fibersocial.auth

import java.security.MessageDigest
import java.security.SecureRandom

/** Exists only so the jvm() target (`:common:jvmTest`, never shipped) compiles. */
actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    SecureRandom().nextBytes(bytes)
    return bytes
}
