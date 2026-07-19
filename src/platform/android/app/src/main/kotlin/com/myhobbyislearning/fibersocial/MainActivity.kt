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
import com.myhobbyislearning.fibersocial.auth.AuthState
import com.myhobbyislearning.fibersocial.feed.FeedAndroidViewModel
import androidx.compose.runtime.CompositionLocalProvider
import com.myhobbyislearning.fibersocial.feed.FeedScreen
import com.myhobbyislearning.fibersocial.feed.LocalProjectLinkOpener
import com.myhobbyislearning.fibersocial.feedback.deviceContext
import com.myhobbyislearning.fibersocial.profile.LocalProfileOpener
import com.myhobbyislearning.fibersocial.login.AuthAndroidViewModel
import com.myhobbyislearning.fibersocial.login.LoginScreen
import com.myhobbyislearning.fibersocial.login.WebViewLoginScreen
import com.myhobbyislearning.fibersocial.notifications.EXTRA_EVENT_PERMALINK
import com.myhobbyislearning.fibersocial.notifications.EXTRA_OPEN_MY_POSTS
import com.myhobbyislearning.fibersocial.notifications.EventNotifier
import com.myhobbyislearning.fibersocial.notifications.EventSyncWorker
import com.myhobbyislearning.fibersocial.notifications.KeyValueMutedTopicsStore
import com.myhobbyislearning.fibersocial.notifications.KeyValueNotificationSettingsStore
import com.myhobbyislearning.fibersocial.settings.KeyValueThemeSettingsStore
import com.myhobbyislearning.fibersocial.settings.ThemeMode
import com.myhobbyislearning.fibersocial.settings.ThemeSettings
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_SETTINGS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.THEME_SETTINGS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.plainKeyValueStore
import com.myhobbyislearning.fibersocial.ui.FiberSocialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()
    private val feedVm: FeedAndroidViewModel by viewModels()

    /** Event permalink from a tapped notification; consumed by FeedScreen's deep link. */
    private val deepLinkEvent = MutableStateFlow<String?>(null)

    /** Set by a tapped reply notification; FeedScreen opens the My Posts feed. */
    private val deepLinkMyPosts = MutableStateFlow(false)

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            deepLinkEvent.value = intent.getStringExtra(EXTRA_EVENT_PERMALINK)
            deepLinkMyPosts.value = intent.getBooleanExtra(EXTRA_OPEN_MY_POSTS, false)
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

                // Checked ahead of the AuthState when-branch below so a retry from
                // AuthState.Error (e.g. a rejected OAuth state, issue #149) re-opens the
                // WebView instead of being silently swallowed by the Error branch, which
                // has no showWebView check of its own.
                if (showWebView) {
                    val authUrl = remember { authVm.buildAuthUrl() }
                    WebViewLoginScreen(
                        authUrl = authUrl,
                        onAuthComplete = { code, state, cookie ->
                            showWebView = false
                            authVm.handleAuthCode(code, state, cookie)
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
                            LaunchedEffect(Unit) {
                                feedVm.load()
                                EventSyncWorker.schedulePeriodic(
                                    this@MainActivity,
                                    notificationSettingsStore.load().effectivePollCadence,
                                )
                            }
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
                            val deepLink by deepLinkEvent.collectAsState()
                            val deepLinkPosts by deepLinkMyPosts.collectAsState()
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
                                deepLinkEventPermalink = deepLink,
                                onDeepLinkConsumed = { deepLinkEvent.value = null },
                                deepLinkMyPosts = deepLinkPosts,
                                onDeepLinkMyPostsConsumed = { deepLinkMyPosts.value = false },
                                themeMode = themeMode,
                                onThemeModeSelected = { mode ->
                                    themeMode = mode
                                    themeScope.launch { themeStore.save(ThemeSettings(mode = mode)) }
                                },
                                notificationSettingsStore = notificationSettingsStore,
                                mutedTopicsStore = mutedTopicsStore,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Adopt the new intent so a later recreation doesn't resurrect the extras of
        // whatever intent originally launched the activity.
        setIntent(intent)
        deepLinkEvent.value = intent.getStringExtra(EXTRA_EVENT_PERMALINK)
        deepLinkMyPosts.value = intent.getBooleanExtra(EXTRA_OPEN_MY_POSTS, false)
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
