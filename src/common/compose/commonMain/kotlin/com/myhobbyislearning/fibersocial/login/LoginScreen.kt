package com.myhobbyislearning.fibersocial.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.composeapp.resources.Res
import com.myhobbyislearning.fibersocial.composeapp.resources.app_logo_content_description
import com.myhobbyislearning.fibersocial.ui.appLogoResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
            Image(
                painter = painterResource(appLogoResource()),
                contentDescription = stringResource(Res.string.app_logo_content_description),
                modifier = Modifier.size(120.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "FiberSocial",
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A community companion for Ravelry",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
