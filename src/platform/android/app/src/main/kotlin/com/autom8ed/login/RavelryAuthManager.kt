package com.autom8ed.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.autom8ed.auth.RavelryOAuthClient
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues

class RavelryAuthManager(context: Context) {

    companion object {
        const val REDIRECT_URI = "fibersocial://auth/callback"
    }

    private val authService = AuthorizationService(context.applicationContext)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(RavelryOAuthClient.AUTH_URL),
        Uri.parse(RavelryOAuthClient.TOKEN_URL),
    )

    fun buildAuthIntent(clientId: String): Intent {
        val codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier()
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        ).setCodeVerifier(codeVerifier).build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /** Returns (authCode, codeVerifier) from the redirect intent, or null if not an auth response. */
    fun extractAuthResult(intent: Intent): Pair<String, String>? {
        val response = AuthorizationResponse.fromIntent(intent) ?: return null
        val code = response.authorizationCode ?: return null
        val verifier = response.request?.codeVerifier ?: return null
        return code to verifier
    }

    fun dispose() = authService.dispose()
}
