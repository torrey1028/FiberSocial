package com.autom8ed.fibersocial.login

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.R

@Composable
fun LoginScreen(onLoginClick: () -> Unit, errorMessage: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.fibersocial_logo),
            contentDescription = stringResource(R.string.app_logo_content_description),
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
