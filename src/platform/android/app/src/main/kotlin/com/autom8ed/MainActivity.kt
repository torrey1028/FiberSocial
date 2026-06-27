package com.autom8ed

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
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.autom8ed.auth.AuthState
import com.autom8ed.login.AuthAndroidViewModel
import com.autom8ed.login.LoginScreen

class MainActivity : ComponentActivity() {

    private val authVm: AuthAndroidViewModel by viewModels()

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
                    is AuthState.Unauthenticated, is AuthState.Error ->
                        LoginScreen(onLoginClick = {
                            authLauncher.launch(authVm.buildAuthIntent())
                        })
                    AuthState.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    is AuthState.Authenticated ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Welcome! (Home screen coming soon)")
                        }
                }
            }
        }
    }
}
