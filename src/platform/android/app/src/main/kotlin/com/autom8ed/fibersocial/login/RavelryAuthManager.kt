package com.autom8ed.fibersocial.login

import android.net.Uri
import android.util.Base64
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import java.security.MessageDigest
import java.security.SecureRandom

class RavelryAuthManager {

    companion object {
        const val REDIRECT_URI = "fibersocial://auth/callback"
    }

    private var codeVerifier: String? = null
    private var state: String? = null

    fun buildAuthUrl(clientId: String): String {
        val verifier = generateCodeVerifier().also { codeVerifier = it }
        val challenge = generateCodeChallenge(verifier)
        val stateVal = generateState().also { state = it }
        return Uri.Builder()
            .scheme("https").authority("www.ravelry.com").path("/oauth2/auth")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", stateVal)
            .build().toString()
    }

    fun consumeCodeVerifier(): String =
        codeVerifier?.also { codeVerifier = null } ?: error("No code verifier — call buildAuthUrl first")

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
