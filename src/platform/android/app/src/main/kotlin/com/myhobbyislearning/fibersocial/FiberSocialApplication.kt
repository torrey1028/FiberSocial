package com.myhobbyislearning.fibersocial

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.storage.AUTH_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.encryptedKeyValueStore
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
class FiberSocialApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Deferred to first use: EncryptedSharedPreferences creation isn't free, and the
        // cookie lambda runs on OkHttp dispatcher threads, not the main thread.
        val tokenStorage by lazy { KeyValueTokenStorage(encryptedKeyValueStore(this, AUTH_PREFS_NAME)) }
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(callFactory = {
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
                    }),
                )
            }
            .build()
    }
}

/**
 * Adds the Ravelry session cookie to requests aimed at ravelry.com hosts.
 *
 * [sessionCookie] is cached for [CACHE_TTL_MS]: an image-heavy thread can fire dozens of
 * concurrent requests (each with its own redirect hop), and re-reading the encrypted
 * token store for every one of them is needless repeated decrypt/IO work. The short TTL
 * bounds how long a stale cookie can linger after a re-login, rather than requiring this
 * interceptor to hook into the login/logout flow to invalidate it.
 */
private class RavelrySessionCookieInterceptor(
    private val sessionCookie: () -> String?,
) : Interceptor {
    @Volatile private var cached: String? = null
    @Volatile private var cachedAtMs: Long = 0L

    private fun currentCookie(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs > CACHE_TTL_MS) {
            cached = sessionCookie()
            cachedAtMs = now
        }
        return cached
    }

    private companion object {
        const val CACHE_TTL_MS = 30_000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isRavelry = host == "ravelry.com" || host.endsWith(".ravelry.com")
        if (!isRavelry) return chain.proceed(request)
        val cookie = currentCookie() ?: return chain.proceed(request)
        // No CookieJar is configured on this client, so there's no other Cookie source
        // today — but merge rather than clobber if one's ever added later. Per RFC 6265,
        // a Cookie header is a single semicolon-separated line, not repeated headers.
        val existing = request.header("Cookie")
        val merged = if (existing == null) cookie else "$existing; $cookie"
        return chain.proceed(request.newBuilder().header("Cookie", merged).build())
    }
}
