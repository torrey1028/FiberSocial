package com.autom8ed.fibersocial.auth

/** SHA-256 digest of [bytes]. Used for the PKCE `code_challenge` (RFC 7636 `S256` method). */
expect fun sha256(bytes: ByteArray): ByteArray

/** [count] cryptographically-secure random bytes. Used for the PKCE verifier and OAuth state. */
expect fun secureRandomBytes(count: Int): ByteArray
