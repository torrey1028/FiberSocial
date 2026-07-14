@file:OptIn(ExperimentalComposeUiApi::class)

package com.autom8ed.fibersocial.settings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
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
 * @param onOpenAbout Opens the "About FiberSocial" disclosure screen (issue #289).
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
    // Non-null on debug builds only: shows a "Debug panel" entry (issue #207).
    onOpenDebugPanel: (() -> Unit)? = null,
    onOpenAbout: () -> Unit = {},
) {
    // Sign out is the only other tappable row on this screen and fires on a single tap
    // with no undo (issue #262) — a fat-finger tap wipes the OAuth session and, via the
    // login WebView's cookie clear, the Ravelry web session too. Confirm first, matching
    // the leave-group dialog's pattern in FeedScreen.kt.
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    // Disabled while the dialog is up (matching NewTopicScreen/FeedbackScreen's
    // enabled = !sending idiom): a back press otherwise still reaches this screen-level
    // handler and navigates out of Settings with the dialog left dangling open, instead
    // of the dialog's own dismiss-on-back consuming it.
    BackHandler(enabled = !confirmingSignOut, onBack = onBack)
    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out of FiberSocial?") },
            text = { Text("You'll need to log in again to use the app.") },
            confirmButton = {
                // Tagged (rather than matched by its "Sign out" text alone) since the row
                // that opened this dialog shares the same label and stays in the semantics
                // tree behind the dialog, which would otherwise make the two ambiguous.
                TextButton(
                    onClick = {
                        confirmingSignOut = false
                        onSignOut()
                    },
                    modifier = Modifier.testTag("ConfirmSignOut"),
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") }
            },
        )
    }
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
                ChoiceSettingRow(
                    icon = DarkModeIcon,
                    title = "App theme",
                    clickLabel = "Change app theme",
                    options = ThemeMode.entries,
                    selected = themeMode,
                    optionLabel = ::themeModeLabel,
                    onSelected = onThemeModeSelected,
                )
                HorizontalDivider()
            }
            if (pollCadence != null) {
                ChoiceSettingRow(
                    icon = Icons.Default.Notifications,
                    title = "Check for new events",
                    clickLabel = "Change event check frequency",
                    options = PollCadence.entries,
                    selected = pollCadence,
                    optionLabel = ::pollCadenceLabel,
                    onSelected = onPollCadenceSelected,
                )
                Text(
                    text = pollCadencePlatformNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Aligns with the row title (icon 24 + spacing 16 + padding 16).
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                )
                HorizontalDivider()
            }
            onOpenDebugPanel?.let { openDebug ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = openDebug, onClickLabel = "Open debug panel", role = Role.Button)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text(text = "Debug panel", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout, onClickLabel = "About FiberSocial", role = Role.Button)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(text = "About FiberSocial", style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = { confirmingSignOut = true },
                        onClickLabel = "Sign out",
                        role = Role.Button,
                    )
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
 * A settings row that shows the current choice and opens a radio dialog to change it.
 * Shared by the "App theme" and "Check for new events" rows so their layout, click
 * semantics, and dialog behavior stay identical and can't drift apart in a later edit.
 * Selection is applied on radio-row tap; the dialog's only button dismisses.
 */
@Composable
private fun <T> ChoiceSettingRow(
    icon: ImageVector,
    title: String,
    clickLabel: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = { showDialog = true },
                onClickLabel = clickLabel,
                role = Role.Button,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = optionLabel(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        onSelected(option)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == selected,
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(optionLabel(option))
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

