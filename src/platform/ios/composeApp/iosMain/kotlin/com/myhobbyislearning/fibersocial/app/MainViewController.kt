package com.myhobbyislearning.fibersocial.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor2.KtorNetworkFetcherFactory
import com.myhobbyislearning.fibersocial.auth.AuthState
import com.myhobbyislearning.fibersocial.debug.DebugFlags
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.feed.FeedScreen
import com.myhobbyislearning.fibersocial.feed.LocalProjectLinkOpener
import com.myhobbyislearning.fibersocial.profile.LocalProfileOpener
import com.myhobbyislearning.fibersocial.login.LoginScreen
import com.myhobbyislearning.fibersocial.notifications.EventSync
import com.myhobbyislearning.fibersocial.notifications.IosEventNotifier
import com.myhobbyislearning.fibersocial.login.WebViewLoginScreen
import com.myhobbyislearning.fibersocial.notifications.KeyValueMutedTopicsStore
import com.myhobbyislearning.fibersocial.notifications.KeyValueNotificationSettingsStore
import com.myhobbyislearning.fibersocial.settings.KeyValueThemeSettingsStore
import com.myhobbyislearning.fibersocial.settings.ThemeMode
import com.myhobbyislearning.fibersocial.settings.ThemeSettings
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_SETTINGS_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.THEME_SETTINGS_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.NsUserDefaultsKeyValueStore
import com.myhobbyislearning.fibersocial.ui.FiberSocialTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.api.createClientPlugin
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * The app's Compose root, embedded by the SwiftUI host (src/platform/ios). The iOS
 * counterpart of `MainActivity.onCreate/setContent`.
 */
@OptIn(ExperimentalNativeApi::class)
fun MainViewController(): UIViewController {
    // Before anything can log: DebugFlags defaults to "not a debug build", so a missed
    // call here fails closed (nothing sensitive logged) rather than open (issue #395).
    // Same signal that gates the debug panel below.
    DebugFlags.initDebugBuild(Platform.isDebugBinary)
    // App-lifetime scope: a single-window iOS app has no Activity recreation, so the
    // models simply live as long as the process (the ViewModel-shaped equivalent of
    // `by viewModels()` retention).
    val scope = MainScope()
    val authModel = IosAuthModel(scope)
    val feedModel = IosFeedModel(scope)
    configureImageLoader(feedModel.tokenStorage)
    return ComposeUIViewController {
        IosApp(authModel, feedModel)
    }
}

/**
 * Coil image loading over the same Ktor Darwin engine the API client uses. The plugin
 * mirrors Android's OkHttp network interceptor: login-gated Ravelry images (issue #102)
 * need the `_ravelry_session` cookie, sent to ravelry.com hosts only — never onto the
 * public image CDN a gated URL redirects to (Ktor re-runs the request pipeline per
 * redirect hop, so the host check applies to every hop like a network interceptor).
 */
private fun configureImageLoader(tokenStorage: KeyValueTokenStorage) {
    // Short-lived cache: an image-heavy thread fires dozens of concurrent requests, and
    // a Keychain read per request is needless repeated IO. The TTL bounds how long a
    // stale cookie lingers after a re-login (same trade-off as Android's interceptor).
    var cached: String? = null
    var cachedAt = TimeSource.Monotonic.markNow() - 1.seconds
    val cookiePlugin = createClientPlugin("RavelrySessionCookie") {
        onRequest { request, _ ->
            val host = request.url.host
            if (host == "ravelry.com" || host.endsWith(".ravelry.com")) {
                if (cachedAt.elapsedNow() > CACHE_TTL) {
                    cached = tokenStorage.load()?.sessionCookie
                    cachedAt = TimeSource.Monotonic.markNow()
                }
                cached?.takeIf { it.isNotEmpty() }?.let { cookie ->
                    val existing = request.headers["Cookie"]
                    request.headers["Cookie"] = if (existing == null) cookie else "$existing; $cookie"
                }
            }
        }
    }
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(
                    KtorNetworkFetcherFactory(httpClient = {
                        HttpClient(Darwin) { install(cookiePlugin) }
                    }),
                )
            }
            .build()
    }
}

private val CACHE_TTL = 30.seconds

@OptIn(ExperimentalNativeApi::class)
@Composable
private fun IosApp(authModel: IosAuthModel, feedModel: IosFeedModel) {
    // Hoisted above the theme so the Settings screen's override applies instantly,
    // app-wide; null until the store loads, rendering the SYSTEM default in the gap
    // (same reasoning as MainActivity).
    val themeStore = remember {
        KeyValueThemeSettingsStore(NsUserDefaultsKeyValueStore(THEME_SETTINGS_STORE_NAME))
    }
    var themeMode by remember { mutableStateOf<ThemeMode?>(null) }
    LaunchedEffect(Unit) { if (themeMode == null) themeMode = themeStore.load().mode }
    val themeScope = rememberCoroutineScope()

    FiberSocialTheme(mode = themeMode ?: ThemeMode.SYSTEM) {
        val authState by authModel.auth.state.collectAsState()
        var showWebView by remember { mutableStateOf(false) }

        // Checked ahead of the AuthState when-branch below so a retry from
        // AuthState.Error re-opens the WebView (issue #149) — same as MainActivity.
        if (showWebView) {
            val authUrl = remember { authModel.buildAuthUrl() }
            WebViewLoginScreen(
                authUrl = authUrl,
                onAuthComplete = { code, state, cookie ->
                    showWebView = false
                    authModel.handleAuthCode(code, state, cookie)
                },
                onBack = { showWebView = false },
            )
        } else {
            when (authState) {
                is AuthState.Unauthenticated ->
                    LoginScreen(onLoginClick = { showWebView = true })
                is AuthState.Error ->
                    LoginScreen(
                        errorMessage = (authState as AuthState.Error).message,
                        onLoginClick = { showWebView = true },
                    )
                AuthState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is AuthState.Authenticated -> {
                    val notificationSettingsStore = remember {
                        KeyValueNotificationSettingsStore(
                            NsUserDefaultsKeyValueStore(NOTIFICATION_SETTINGS_STORE_NAME),
                        )
                    }
                    val mutedTopicsStore = remember {
                        KeyValueMutedTopicsStore(
                            NsUserDefaultsKeyValueStore(NOTIFICATION_STATE_STORE_NAME),
                        )
                    }
                    LaunchedEffect(Unit) {
                        feedModel.load()
                        // Same point in the flow where Android prompts (MainActivity
                        // requests POST_NOTIFICATIONS at launch).
                        IosEventNotifier().requestAuthorization()
                    }
                    // On session expiry: show WebView login before clearing auth so
                    // there's no LoginScreen flash (same as MainActivity).
                    LaunchedEffect(feedModel) {
                        feedModel.sessionExpired.collect {
                            // Dismiss the ViewModel-held overlays so they can't
                            // survive re-login into a different account's session.
                            feedModel.projectPage.dismiss()
                            feedModel.userProfile.dismiss()
                            showWebView = true
                            authModel.auth.logout()
                        }
                    }
                    val pendingDeepLink by deepLink.collectAsState()
                    // Project links tapped in post content open the in-app project page
                    // (issue #103); tapping a username opens the profile (issue #194).
                    CompositionLocalProvider(
                        LocalProjectLinkOpener provides { link -> feedModel.projectPage.open(link) },
                        LocalProfileOpener provides { username -> feedModel.userProfile.open(username) },
                    ) {
                        FeedScreen(
                            viewModel = feedModel,
                            // Reset first: the models outlive the session, and a
                            // different account logging in next must not see this
                            // one's feed.
                            onLogout = {
                                feedModel.reset()
                                authModel.auth.logout()
                            },
                            deepLink = pendingDeepLink,
                            onDeepLinkConsumed = { deepLink.value = null },
                            themeMode = themeMode,
                            onThemeModeSelected = { mode ->
                                themeMode = mode
                                themeScope.launch { themeStore.save(ThemeSettings(mode = mode)) }
                            },
                            notificationSettingsStore = notificationSettingsStore,
                            mutedTopicsStore = mutedTopicsStore,
                            // A cadence change re-baselines the next background-refresh
                            // request (iOS treats it as a floor, not a schedule).
                            onPollCadenceChanged = { EventSync.scheduleBackgroundRefresh(it) },
                            debugPanelEnabled = Platform.isDebugBinary,
                            onRunEventSync = { EventSync.runOnce() },
                            deviceInfo = deviceContext(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * App version + device/OS line pre-filled into the feedback composer — the iOS analog
 * of the Android `deviceContext()`.
 */
private fun deviceContext(): String {
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "dev"
    val build = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "0"
    val device = UIDevice.currentDevice
    return "App: FiberSocial $version (build $build)\n" +
        "Device: ${device.model} · ${device.systemName} ${device.systemVersion}"
}
