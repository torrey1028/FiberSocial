package com.autom8ed.fibersocial.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation dialog for deleting one of the user's own items (issue #198). Shared by
 * the topic detail screen (posts) and the project page (comments) so the destructive
 * wording, button order, and any future a11y/styling fix stay consistent across both.
 *
 * @param itemLabel The thing being deleted, e.g. "post" or "comment".
 * @param container Where it lives, e.g. "topic" or "project".
 * @param onConfirm Invoked when the user confirms; [onDismiss] is then also called.
 * @param onDismiss Invoked to close the dialog (cancel, scrim tap, or after confirm).
 */
@Composable
fun DeleteConfirmDialog(
    itemLabel: String,
    container: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this $itemLabel?") },
        text = { Text("This removes your $itemLabel from the $container for everyone. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
