package com.myhobbyislearning.fibersocial.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(
    onForceSessionExpiry: () -> Unit,
    onRunEventSync: () -> Unit,
    onDismiss: () -> Unit,
    onForceFeedError: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Debug Panel",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onForceSessionExpiry(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Force Session Expiry")
            }
            TextButton(
                onClick = { onRunEventSync(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run Event Sync Now")
            }
            TextButton(
                onClick = { onForceFeedError(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Force Feed Error")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SessionCookieLoggingRow()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Opt-in for logging the raw Ravelry session cookie (issue #395).
 *
 * Off on every launch by design — see [DebugFlags]. The subtitle spells out what turning
 * it on actually publishes, because "session cookie" undersells it: the value is a live
 * credential that lets anyone holding it act as the signed-in user.
 *
 * The row is absent entirely outside a debug build. [DebugFlags] would refuse the write
 * anyway, so this is presentation rather than the security boundary — but an inert switch
 * that silently does nothing is worse than no switch.
 */
@Composable
private fun SessionCookieLoggingRow() {
    if (!DebugFlags.debugToolsAvailable) return
    var enabled by remember { mutableStateOf(DebugFlags.sessionCookieLoggingEnabled) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = {
                    enabled = it
                    DebugFlags.setSessionCookieLogging(it)
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Log session cookies", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Writes the raw Ravelry session cookie to the log on next sign-in. " +
                    "That value can impersonate your account — don't share a log with " +
                    "this on. Resets when the app restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
