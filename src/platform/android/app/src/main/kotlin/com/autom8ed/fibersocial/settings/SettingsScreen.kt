package com.autom8ed.fibersocial.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.models.RavelryUser

/**
 * Settings page reached from the profile row at the bottom of the group
 * drawer (issue #9). Shows the signed-in account and lets the user sign out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: RavelryUser?,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(user, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = user?.username ?: "Signed in",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Ravelry account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSignOut)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Sign out",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun UserAvatar(user: RavelryUser?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val avatarUrl = user?.avatarUrl
    if (avatarUrl != null) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = "Avatar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size),
        )
    }
}
