package com.autom8ed.fibersocial.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.notifications.NotificationSettings
import com.autom8ed.fibersocial.ui.UserAvatar

/**
 * Settings page reached from the profile row at the bottom of the group
 * drawer (issue #9). Shows the signed-in account, the event-notification
 * poll cadence, and lets the user sign out.
 *
 * @param pollIntervalHours Current background-sync interval; null while loading
 *   (the row is hidden until known to avoid flashing a wrong value).
 * @param onPollIntervalSelected Invoked with the chosen interval, one of
 *   [NotificationSettings.POLL_INTERVAL_CHOICES].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: RavelryUser?,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    pollIntervalHours: Int? = null,
    onPollIntervalSelected: (Int) -> Unit = {},
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
            if (pollIntervalHours != null) {
                PollIntervalRow(
                    pollIntervalHours = pollIntervalHours,
                    onSelected = onPollIntervalSelected,
                )
                HorizontalDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSignOut, onClickLabel = "Sign out", role = Role.Button)
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


/** Human label for a poll interval choice. */
private fun intervalLabel(hours: Int): String =
    if (hours == 1) "Every hour" else "Every $hours hours"

/**
 * "Check for new events" row: shows the current cadence and opens a radio dialog with
 * the supported choices.
 */
@Composable
private fun PollIntervalRow(
    pollIntervalHours: Int,
    onSelected: (Int) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = { showDialog = true },
                onClickLabel = "Change event check frequency",
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Notifications, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "Check for new events",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = intervalLabel(pollIntervalHours),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Check for new events") },
            text = {
                Column {
                    NotificationSettings.POLL_INTERVAL_CHOICES.forEach { hours ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        onSelected(hours)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = hours == pollIntervalHours,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(intervalLabel(hours))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}
