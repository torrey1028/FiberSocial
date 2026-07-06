package com.autom8ed.fibersocial

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.autom8ed.fibersocial.auth.AuthState
import com.autom8ed.fibersocial.feed.FeedAndroidViewModel
import androidx.compose.runtime.CompositionLocalProvider
import com.autom8ed.fibersocial.feed.FeedScreen
import com.autom8ed.fibersocial.feed.LocalProjectLinkOpener
import com.autom8ed.fibersocial.login.AuthAndroidViewModel
import com.autom8ed.fibersocial.login.LoginScreen
import com.autom8ed.fibersocial.login.WebViewLoginScreen
import com.autom8ed.fibersocial.notifications.EXTRA_EVENT_PERMALINK
import com.autom8ed.fibersocial.notifications.EventNotifier
import com.autom8ed.fibersocial.notifications.EventSyncWorker
import com.autom8ed.fibersocial.notifications.KeyValueNotificationSettingsStore
import com.autom8ed.fibersocial.settings.KeyValueThemeSettingsStore
import com.autom8ed.fibersocial.settings.ThemeMode
import com.autom8ed.fibersocial.settings.ThemeSettings
import com.autom8ed.fibersocial.storage.NOTIFICATION_SETTINGS_PREFS_NAME
import com.autom8ed.fibersocial.storage.THEME_SETTINGS_PREFS_NAME
import com.autom8ed.fibersocial.storage.plainKeyValueStore
import com.autom8ed.fibersocial.ui.FiberSocialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()
    private val feedVm: FeedAndroidViewModel by viewModels()

    /** Event permalink from a tapped notification; consumed by FeedScreen's deep link. */
    private val deepLinkEvent = MutableStateFlow<String?>(null)

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventNotifier(this).ensureChannels()
        requestNotificationPermissionIfNeeded()
        // Only a genuinely new launch carries a fresh notification tap: on recreation
        // (rotation, process restore) the retained launch intent would replay a deep
        // link the user already consumed and dismissed.
        if (savedInstanceState == null) {
            deepLinkEvent.value = intent.getStringExtra(EXTRA_EVENT_PERMALINK)
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
                            LaunchedEffect(Unit) {
                                feedVm.load()
                                EventSyncWorker.schedulePeriodic(
                                    this@MainActivity,
                                    KeyValueNotificationSettingsStore(
                                        plainKeyValueStore(this@MainActivity, NOTIFICATION_SETTINGS_PREFS_NAME),
                                    ).load().effectivePollCadence,
                                )
                            }
                            // On session expiry: show WebView login before clearing auth so there's no
                            // LoginScreen flash between the state change and the WebView appearing.
                            LaunchedEffect(feedVm) {
                                feedVm.sessionExpired.collect {
                                    showWebView = true
                                    authVm.auth.logout()
                                }
                            }
                            val deepLink by deepLinkEvent.collectAsState()
                            // Project links tapped anywhere in post content open the
                            // in-app project page instead of the browser (issue #103).
                            CompositionLocalProvider(
                                LocalProjectLinkOpener provides { link -> feedVm.projectPage.open(link) },
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
                                themeMode = themeMode,
                                onThemeModeSelected = { mode ->
                                    themeMode = mode
                                    themeScope.launch { themeStore.save(ThemeSettings(mode = mode)) }
                                },
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
