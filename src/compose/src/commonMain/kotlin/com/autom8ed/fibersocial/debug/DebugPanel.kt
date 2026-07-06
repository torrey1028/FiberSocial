package com.autom8ed.fibersocial.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            Spacer(Modifier.height(16.dp))
        }
    }
}
