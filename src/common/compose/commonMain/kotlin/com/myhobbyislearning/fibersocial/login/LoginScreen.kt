package com.myhobbyislearning.fibersocial.login

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.debug.DebugFlags
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.rememberShareText
import com.myhobbyislearning.fibersocial.ui.AppBranding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(onLoginClick: () -> Unit, errorMessage: String? = null) {
    // The theme sets colors but no background (FiberSocialTheme has no Surface), and this
    // screen renders before the feed's Scaffold — so it needs its own themed surface, or
    // it falls through to the raw window background (wrong in both light and dark). Mirrors
    // LaunchLoadingScreen's fix for the identical trap (#233).
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Debug builds only: long-press the branding block to export the captured
            // debug log via the share sheet. This screen is where login failures land,
            // and (on an OTA-installed iPhone build) the share sheet is the only route
            // the captured log lines have off the device — see DebugLog.
            val shareText = rememberShareText()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = if (DebugFlags.debugToolsAvailable) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { shareText(DebugLog.dump()) },
                    )
                } else {
                    Modifier
                },
            ) {
                AppBranding()
            }
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = onLoginClick) {
                Text("Log in with Ravelry")
            }
        }
    }
}
