package com.autom8ed.fibersocial.feedback

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.BuildConfig

/**
 * App version + device/OS line pre-filled into the feedback composer so reports are
 * reproducible — the reduced stand-in for #57's "grab logs". The user can edit or clear it.
 */
fun deviceContext(): String {
    val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    return "App: FiberSocial ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
        "Device: $device · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}

/**
 * "Send feedback" screen (issue #57, reduced scope): posts the user's report as a topic in
 * the FiberSocial App Support group's forum. A single description box (the topic title is
 * derived from it) plus an editable app/device info block.
 *
 * @param state Current submission state from `FeedbackViewModel`.
 * @param deviceInfo Pre-filled app/device context (see [deviceContext]); editable.
 * @param onBack Dismiss without sending.
 * @param onSend Submit (description, details).
 * @param onSent Acknowledge a successful send and close.
 * @param onOpenSupportGroup Open the group on Ravelry — shown when posting needs membership
 *   (stopgap until in-app join lands; see PR A / issue #57).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    state: FeedbackState,
    deviceInfo: String,
    onBack: () -> Unit,
    onSend: (description: String, details: String) -> Unit,
    onSent: () -> Unit,
    onOpenSupportGroup: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var description by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(deviceInfo) }
    val sending = state is FeedbackState.Sending

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send feedback") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state is FeedbackState.Sent) {
            SentConfirmation(modifier = Modifier.padding(padding), onDone = onSent)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Feedback is posted as a topic in the FiberSocial App Support group on " +
                    "Ravelry. Describe the issue or idea — the more detail, the better.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Describe the issue or idea") },
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
            )

            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("App & device info") },
                supportingText = { Text("Included to help us reproduce it — edit or clear if you like.") },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )

            when (state) {
                is FeedbackState.NeedsMembership -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "You need to join the FiberSocial App Support group before you can " +
                            "post. Open it on Ravelry to join, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onOpenSupportGroup, contentPadding = PaddingValues(0.dp)) {
                        Text("Open the support group")
                    }
                }
                is FeedbackState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            Button(
                onClick = { onSend(description, details) },
                enabled = !sending && description.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sending…")
                } else {
                    Text("Send feedback")
                }
            }
        }
    }
}

@Composable
private fun SentConfirmation(modifier: Modifier = Modifier, onDone: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = "Thanks for the feedback!",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Your report was posted to the FiberSocial App Support group.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onDone) { Text("Done") }
    }
}
