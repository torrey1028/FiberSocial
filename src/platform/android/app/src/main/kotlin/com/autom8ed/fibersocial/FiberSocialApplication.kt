package com.autom8ed.fibersocial

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.autom8ed.fibersocial.auth.AndroidTokenStorage
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Provides the app-wide Coil [ImageLoader].
 *
 * Ravelry-hosted post photos (`www.ravelry.com/attached/...`, `/forum-images/...`) are
 * login-gated: an unauthenticated request 302s to the login page instead of the image
 * (issue #102). The default loader has no Ravelry session, so those images never loaded.
 * This loader attaches the `www.ravelry.com` session cookie captured during WebView
 * OAuth login (the same cookie the group-membership scraper uses) to ravelry.com
 * requests, so gated post photos resolve like they do on the website.
 */
class FiberSocialApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        // Deferred to first use: EncryptedSharedPreferences creation isn't free, and the
        // cookie lambda runs on OkHttp dispatcher threads, not the main thread.
        val tokenStorage by lazy { AndroidTokenStorage(this) }
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    // Network (not application) interceptor: it runs per redirect hop,
                    // so the cookie goes to ravelry.com hosts only and is never carried
                    // onto the public image CDN a gated URL redirects to.
                    .addNetworkInterceptor(
                        RavelrySessionCookieInterceptor {
                            runBlocking { tokenStorage.load()?.sessionCookie }
                        },
                    )
                    .build()
            }
            .build()
    }
}

/** Adds the Ravelry session cookie to requests aimed at ravelry.com hosts. */
private class RavelrySessionCookieInterceptor(
    private val sessionCookie: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isRavelry = host == "ravelry.com" || host.endsWith(".ravelry.com")
        if (!isRavelry) return chain.proceed(request)
        val cookie = sessionCookie() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Cookie", cookie).build())
    }
}
