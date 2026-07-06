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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.notifications.PollCadence
import com.autom8ed.fibersocial.notifications.pollCadenceLabel
import com.autom8ed.fibersocial.ui.UserAvatar

/**
 * Settings page reached from the profile row at the bottom of the group
 * drawer (issue #9). Shows the signed-in account, the app theme, the
 * event-notification poll cadence, and lets the user sign out.
 *
 * @param pollCadence Current background-sync cadence; null while loading (the row is
 *   hidden until known to avoid flashing a wrong value).
 * @param onPollCadenceSelected Invoked with the chosen cadence.
 * @param themeMode Current theme override; null while loading (row hidden, same
 *   convention as [pollCadence]).
 * @param onThemeModeSelected Invoked with the chosen theme mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: RavelryUser?,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    pollCadence: PollCadence? = null,
    onPollCadenceSelected: (PollCadence) -> Unit = {},
    themeMode: ThemeMode? = null,
    onThemeModeSelected: (ThemeMode) -> Unit = {},
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
            if (themeMode != null) {
                ThemeModeRow(
                    themeMode = themeMode,
                    onSelected = onThemeModeSelected,
                )
                HorizontalDivider()
            }
            if (pollCadence != null) {
                PollCadenceRow(
                    pollCadence = pollCadence,
                    onSelected = onPollCadenceSelected,
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


/**
 * "App theme" row: shows the current theme mode and opens a radio dialog offering
 * Follow system / Light / Dark (issue #153).
 */
@Composable
private fun ThemeModeRow(
    themeMode: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = { showDialog = true },
                onClickLabel = "Change app theme",
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DarkModeIcon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "App theme",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = themeModeLabel(themeMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("App theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        onSelected(mode)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == themeMode,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(themeModeLabel(mode))
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

/**
 * Material's "Dark Mode" (crescent moon) glyph, defined inline because the app only
 * ships material-icons-core, which doesn't include it.
 */
private val DarkModeIcon: ImageVector by lazy {
    materialIcon(name = "Outlined.DarkMode") {
        materialPath {
            moveTo(12.0f, 3.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            reflectiveCurveToRelative(4.03f, 9.0f, 9.0f, 9.0f)
            reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
            curveToRelative(0.0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
            curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f)
            curveToRelative(-2.98f, 0.0f, -5.4f, -2.42f, -5.4f, -5.4f)
            curveToRelative(0.0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f)
            curveTo(12.92f, 3.04f, 12.46f, 3.0f, 12.0f, 3.0f)
            close()
        }
    }
}

/**
 * "Check for new events" row: shows the current cadence and opens a radio dialog with
 * the supported choices.
 */
@Composable
private fun PollCadenceRow(
    pollCadence: PollCadence,
    onSelected: (PollCadence) -> Unit,
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
                text = pollCadenceLabel(pollCadence),
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
                    PollCadence.entries.forEach { cadence ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        onSelected(cadence)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = cadence == pollCadence,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(pollCadenceLabel(cadence))
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
