package com.autom8ed.fibersocial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.autom8ed.fibersocial.auth.AuthState
import com.autom8ed.fibersocial.feed.FeedAndroidViewModel
import com.autom8ed.fibersocial.feed.FeedScreen
import com.autom8ed.fibersocial.login.AuthAndroidViewModel
import com.autom8ed.fibersocial.login.LoginScreen

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()
    private val feedVm: FeedAndroidViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val authState by authVm.auth.state.collectAsState()
                val authLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    result.data?.let { authVm.handleAuthRedirect(it) }
                }

                when (authState) {
                    is AuthState.Unauthenticated ->
                        LoginScreen(onLoginClick = {
                            authLauncher.launch(authVm.buildAuthIntent())
                        })
                    is AuthState.Error ->
                        LoginScreen(
                            errorMessage = (authState as AuthState.Error).message,
                            onLoginClick = { authLauncher.launch(authVm.buildAuthIntent()) },
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
