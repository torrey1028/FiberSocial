package com.myhobbyislearning.fibersocial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared skeleton of a message composer (issue #198): any error lines above a row of
 * an optional [leading] control, a growing text field, and a send button that becomes a
 * spinner while sending. Used by the reply composer (topic detail) and the comment
 * composer (project page) so the send affordance, spinner, error styling, and IME
 * behavior stay identical.
 *
 * Deliberately column-less at the edges: the caller supplies [modifier] (imePadding,
 * padding, etc.) and owns the surrounding container (e.g. the reply composer's elevated
 * `Surface`), the text state, and the clear-on-success / keep-on-failure contract — which
 * differ between the two call sites. This composable only lays out the inner fragment.
 *
 * @param errorTexts Error lines rendered above the row, in order (the reply composer shows
 *   both a send error and an attachment error).
 * @param leading Optional control before the text field (the reply composer's attach button).
 */
@Composable
fun MessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    placeholder: String,
    sendContentDescription: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    errorTexts: List<String> = emptyList(),
    sendEnabled: Boolean = text.isNotBlank(),
    leading: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        errorTexts.forEach { error ->
            ErrorText(error, modifier = Modifier.padding(bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            leading?.invoke()
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(placeholder) },
                enabled = !sending,
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (sending) {
                SendingSpinner()
            } else {
                IconButton(onClick = onSend, enabled = sendEnabled) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = sendContentDescription)
                }
            }
        }
    }
}

/**
 * Standard styling for an inline form/composer error line (issue #270) — used above
 * [MessageComposer], [com.myhobbyislearning.fibersocial.feed.NewTopicScreen]'s error, and
 * the post edit bar's error, so failures read identically everywhere. Callers still supply
 * their own spacing via [modifier]; only the text style and color are shared.
 */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * The compact spinner shown in place of a send/confirm control while a request is in
 * flight (issue #270) — used by [MessageComposer], [com.myhobbyislearning.fibersocial.feed.NewTopicScreen]'s
 * post button, and the post edit bar's save button, so the "sending" affordance is sized
 * and spaced identically everywhere.
 */
@Composable
fun SendingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(32.dp).padding(4.dp))
}
