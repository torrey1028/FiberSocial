package com.myhobbyislearning.fibersocial

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.myhobbyislearning.fibersocial.app.ForegroundActivations
import com.myhobbyislearning.fibersocial.auth.AuthState
import com.myhobbyislearning.fibersocial.debug.DebugFlags
import com.myhobbyislearning.fibersocial.feed.FeedAndroidViewModel
import androidx.compose.runtime.CompositionLocalProvider
import com.myhobbyislearning.fibersocial.feed.FeedScreen
import com.myhobbyislearning.fibersocial.feed.LocalProjectLinkOpener
import com.myhobbyislearning.fibersocial.feedback.deviceContext
import com.myhobbyislearning.fibersocial.profile.LocalProfileOpener
import com.myhobbyislearning.fibersocial.login.AuthAndroidViewModel
import com.myhobbyislearning.fibersocial.login.LoginScreen
import com.myhobbyislearning.fibersocial.login.WebViewLoginScreen
import com.myhobbyislearning.fibersocial.notifications.DeepLink
import com.myhobbyislearning.fibersocial.notifications.EventNotifier
import com.myhobbyislearning.fibersocial.notifications.toDeepLink
import com.myhobbyislearning.fibersocial.notifications.EventSyncWorker
import com.myhobbyislearning.fibersocial.moderation.KeyValueBlockedUsersStore
import com.myhobbyislearning.fibersocial.notifications.KeyValueMutedTopicsStore
import com.myhobbyislearning.fibersocial.notifications.KeyValueNotificationSettingsStore
import com.myhobbyislearning.fibersocial.settings.CURRENT_TERMS_VERSION
import com.myhobbyislearning.fibersocial.settings.KeyValueTermsAcceptanceStore
import com.myhobbyislearning.fibersocial.settings.KeyValueThemeSettingsStore
import com.myhobbyislearning.fibersocial.settings.TermsAcceptance
import com.myhobbyislearning.fibersocial.settings.ThemeMode
import com.myhobbyislearning.fibersocial.settings.ThemeSettings
import com.myhobbyislearning.fibersocial.settings.shouldShowTermsGate
import com.myhobbyislearning.fibersocial.storage.BLOCKED_USERS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_SETTINGS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.TERMS_ACCEPTANCE_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.THEME_SETTINGS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.plainKeyValueStore
import com.myhobbyislearning.fibersocial.terms.TermsGateScreen
import com.myhobbyislearning.fibersocial.ui.FiberSocialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()
    private val feedVm: FeedAndroidViewModel by viewModels()

    /** Destination from a tapped notification; consumed once by FeedScreen (issue #351). */
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything can log: DebugFlags defaults to "not a debug build", so a
        // missed call here fails closed (nothing sensitive logged) rather than open
        // (issue #395). Same signal that gates the debug panel below.
        DebugFlags.initDebugBuild(BuildConfig.DEBUG)
        // targetSdk 35+ enforces edge-to-edge with no opt-out; this call makes that
        // consistent from minSdk 26 up, instead of only on 35+ devices. Per-screen
        // system-bar icon contrast is still handled dynamically in SystemBarStyle,
        // since it depends on the in-app theme choice, not just device config.
        enableEdgeToEdge()
        EventNotifier(this).ensureChannels()
        requestNotificationPermissionIfNeeded()
        // Only a genuinely new launch carries a fresh notification tap: on recreation
        // (rotation, process restore) the retained launch intent would replay a deep
        // link the user already consumed and dismissed.
        if (savedInstanceState == null) {
            deepLink.value = intent.toDeepLink()
        }
        setContent {
            // Hoisted above the theme so the Settings screen's override applies
            // instantly, app-wide. null until the store loads; rendering with the
            // SYSTEM default in that gap matches the launch window theme, so there's
            // no visible flash for SYSTEM/matching users (and at worst one recompose
            // for override users on first cold start).
            //
            // rememberSaveable, not remember: on a config change (rotation, system
            // day/night toggle) the resolved mode is restored from the instance state,
            // so an override user doesn't re-flash the SYSTEM default every rotation
            // while the async reload runs. ThemeMode is a serializable enum, so the
            // default saver handles the nullable value.
            val themeStore = remember {
                KeyValueThemeSettingsStore(plainKeyValueStore(this, THEME_SETTINGS_PREFS_NAME))
            }
            var themeMode by rememberSaveable { mutableStateOf<ThemeMode?>(null) }
            // Only load when we don't already have a restored value, so a config change
            // keeps rendering the restored mode instead of blinking through null.
            LaunchedEffect(Unit) { if (themeMode == null) themeMode = themeStore.load().mode }
            val themeScope = rememberCoroutineScope()

            FiberSocialTheme(mode = themeMode ?: ThemeMode.SYSTEM) {
                val authState by authVm.auth.state.collectAsState()
                var showWebView by remember { mutableStateOf(false) }

                // Terms-of-use gate (issue #408, Apple Guideline 1.2): must appear before
                // "Log in with Ravelry" can be used, and — since issue #424 — before an
                // authenticated user's feed when their acceptance is wiped or stale.
                // null while loading; the null-hold branch below keeps EVERYTHING back
                // for that gap.
                //
                // rememberSaveable, not remember (same reasoning as themeMode above): on a
                // config change the accepted version is restored from instance state rather
                // than resetting to null and re-reading SharedPreferences. Stores just the
                // Int version — TermsAcceptance's only field — since the default Bundle
                // Saver doesn't handle a Kotlin data class.
                val termsStore = remember {
                    KeyValueTermsAcceptanceStore(plainKeyValueStore(this, TERMS_ACCEPTANCE_PREFS_NAME))
                }
                var termsVersion by rememberSaveable { mutableStateOf<Int?>(null) }
                val termsAcceptance = termsVersion?.let { TermsAcceptance(version = it) }
                LaunchedEffect(Unit) {
                    if (termsVersion == null) {
                        // Fail CLOSED: an unreadable store gates (and the gate re-saves on
                        // Agree) rather than leaving termsVersion null forever, which the
                        // hold branch below would render as a permanent blank screen.
                        termsVersion =
                            runCatching { termsStore.load() }.getOrElse { TermsAcceptance() }.version
                    }
                }
                val termsScope = rememberCoroutineScope()

                // Branch order is load-bearing (issue #424):
                // 1. While the acceptance store is still loading (null), hold everything —
                //    a brief blank frame. Rendering the normal branches in that gap showed
                //    feed content, fired its network load, and (on iOS) popped the
                //    notification-permission prompt for the first frames of a launch that
                //    then turned out to need the gate — content before agreement, the exact
                //    thing Guideline 1.2 forbids — then tore it down and re-loaded after
                //    Agree. It also let a login WebView start mid-gap only to be replaced.
                // 2. The gate outranks showWebView: agreeing leaves showWebView untouched,
                //    so a login WebView pending behind the gate opens right after. (With
                //    the hold branch, the only way that state arises is a session expiry
                //    racing the store read.)
                // 3. The Error-retry path (issue #149) is unaffected: shouldShowTermsGate
                //    deliberately never gates AuthState.Error, so a retry still re-opens
                //    the WebView instead of being swallowed by an unrelated terms prompt.
                if (termsAcceptance == null) {
                    Box(Modifier.fillMaxSize())
                } else if (shouldShowTermsGate(authState, termsAcceptance)) {
                    val uriHandler = LocalUriHandler.current
                    TermsGateScreen(
                        onOpenFullTerms = {
                            uriHandler.openUri("https://torrey1028.github.io/FiberSocial/terms-of-use.html")
                        },
                        onAgree = {
                            val updated = TermsAcceptance(version = CURRENT_TERMS_VERSION)
                            termsVersion = updated.version
                            termsScope.launch { termsStore.save(updated) }
                        },
                    )
                } else if (showWebView) {
                    WebViewLoginScreen(
                        // A supplier, not a pre-built URL: the WebView mints a fresh
                        // authorize URL when the server derails the flow (stale
                        // challenge) and it needs to restart.
                        buildAuthUrl = { authVm.buildAuthUrl() },
                        onAuthComplete = { code, state, cookie ->
                            showWebView = false
                            authVm.handleAuthCode(code, state, cookie)
                        },
                        // Leave the web view and report it, rather than sitting on a
                        // dead authorize page (issue #394). Routed through failLogin so
                        // an authorization-server refusal lands in the same place as the
                        // state-mismatch rejection: AuthState.Error on the login screen,
                        // which already offers a retry.
                        onAuthError = { message ->
                            showWebView = false
                            authVm.auth.failLogin(message)
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
                                    plainKeyValueStore(this@MainActivity, NOTIFICATION_SETTINGS_PREFS_NAME),
                                )
                            }
                            val mutedTopicsStore = remember {
                                KeyValueMutedTopicsStore(
                                    plainKeyValueStore(this@MainActivity, NOTIFICATION_STATE_PREFS_NAME),
                                )
                            }
                            // Local blocked-users list (issue #410). remember, not
                            // rememberSaveable — the store is a plain object wrapping a
                            // SharedPreferences handle, not serializable state; its own
                            // backing prefs file is what survives process death, and load()
                            // below repopulates blockedUsernames from it on the fresh instance
                            // a recreation produces.
                            val blockedUsersStore = remember {
                                KeyValueBlockedUsersStore(
                                    plainKeyValueStore(this@MainActivity, BLOCKED_USERS_PREFS_NAME),
                                )
                            }
                            LaunchedEffect(Unit) {
                                feedVm.load()
                                EventSyncWorker.schedulePeriodic(
                                    this@MainActivity,
                                    notificationSettingsStore.load().effectivePollCadence,
                                )
                            }
                            LaunchedEffect(blockedUsersStore) { blockedUsersStore.load() }
                            // On session expiry: show WebView login before clearing auth so there's no
                            // LoginScreen flash between the state change and the WebView appearing.
                            LaunchedEffect(feedVm) {
                                feedVm.sessionExpired.collect {
                                    // Dismiss the ViewModel-held overlays so they can't
                                    // survive re-login into a different account's session.
                                    feedVm.projectPage.dismiss()
                                    feedVm.userProfile.dismiss()
                                    showWebView = true
                                    authVm.auth.logout()
                                }
                            }
                            val pendingDeepLink by deepLink.collectAsState()
                            // Project links tapped in post content open the in-app project
                            // page (issue #103); tapping a username opens the profile (#194).
                            CompositionLocalProvider(
                                LocalProjectLinkOpener provides { link -> feedVm.projectPage.open(link) },
                                LocalProfileOpener provides { username -> feedVm.userProfile.open(username) },
                            ) {
                            FeedScreen(
                                viewModel = feedVm,
                                // Reset first: the ViewModel outlives the session, and a
                                // different account logging in next must not see this one's feed.
                                onLogout = {
                                    feedVm.reset()
                                    authVm.auth.logout()
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
                                blockedUsersStore = blockedUsersStore,
                                // UPDATE policy re-registers the periodic sync at the new cadence.
                                onPollCadenceChanged = { cadence ->
                                    EventSyncWorker.schedulePeriodic(this@MainActivity, cadence)
                                },
                                debugPanelEnabled = BuildConfig.DEBUG,
                                onRunEventSync = { EventSyncWorker.runOnce(this@MainActivity) },
                                deviceInfo = deviceContext(),
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // FiberSocial is a single-Activity app, so this Activity resuming IS the process
        // coming to the foreground — enough to drive the shared activation signal without
        // adding androidx.lifecycle-process for ProcessLifecycleOwner (issue #350 part 1).
        // Also fires on the cold-start resume and on config-change recreation; both are
        // harmless, since the consumer (drawer unread dots) cancels any in-flight refresh.
        ForegroundActivations.notifyForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Adopt the new intent so a later recreation doesn't resurrect the extras of
        // whatever intent originally launched the activity.
        setIntent(intent)
        deepLink.value = intent.toDeepLink()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
