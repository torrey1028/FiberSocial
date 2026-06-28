package com.autom8ed.fibersocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.autom8ed.fibersocial.auth.AuthState
import com.autom8ed.fibersocial.feed.FeedAndroidViewModel
import com.autom8ed.fibersocial.feed.FeedScreen
import com.autom8ed.fibersocial.login.AuthAndroidViewModel
import com.autom8ed.fibersocial.login.LoginScreen
import com.autom8ed.fibersocial.login.WebViewLoginScreen

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()
    private val feedVm: FeedAndroidViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val authState by authVm.auth.state.collectAsState()
                var showWebView by remember { mutableStateOf(false) }

                when (authState) {
                    is AuthState.Unauthenticated -> {
                        if (showWebView) {
                            val authUrl = remember { authVm.buildAuthUrl() }
                            WebViewLoginScreen(
                                authUrl = authUrl,
                                onAuthComplete = { code, cookie ->
                                    showWebView = false
                                    authVm.handleAuthCode(code, cookie)
                                },
                            )
                        } else {
                            LoginScreen(onLoginClick = { showWebView = true })
                        }
                    }
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
                        LaunchedEffect(Unit) { feedVm.load() }
                        FeedScreen(viewModel = feedVm)
                    }
                }
            }
        }
    }
}
