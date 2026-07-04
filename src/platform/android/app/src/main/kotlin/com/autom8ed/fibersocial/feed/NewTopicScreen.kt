package com.autom8ed.fibersocial.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Topic

/**
 * Full-screen composer for starting a new topic in one of the user's groups.
 *
 * The group picker defaults to [initialGroup] (the feed's current drawer filter);
 * with no filter active the user must pick a group before Post enables. Fields keep
 * their text on failure and the screen only navigates away once the topic is
 * confirmed created ([NewTopicState.Created]), mirroring the reply composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTopicScreen(
    groups: List<Group>,
    initialGroup: Group?,
    state: NewTopicState,
    onBack: () -> Unit,
    onPost: (Group, String, String) -> Unit,
    onCreated: (Topic, Group) -> Unit,
) {
    // Group is not Saveable; survives recomposition, not process death — acceptable
    // for a modal composer, and title/body (the real typing effort) do survive.
    var group by remember { mutableStateOf(initialGroup) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val sending = state is NewTopicState.Sending

    // Disabled while sending: leaving now would unmount this screen's collector on
    // NewTopicViewModel.state, so a Created/Error result that lands after navigating
    // away would never trigger onCreated or surface an error (reset() is a no-op
    // mid-send, but that only protects the ViewModel's state — not whether anyone's
    // still observing it).
    BackHandler(enabled = !sending, onBack = onBack)

    LaunchedEffect(state) {
        if (state is NewTopicState.Created) {
            // group is remembered, not saveable — an activity recreation between
            // posting and this effect firing can reset it to initialGroup (possibly
            // null). Recover it from the created topic's forumId in that case.
            val created = group ?: groups.firstOrNull { it.forumId == state.topic.forumId }
            created?.let { onCreated(state.topic, it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New topic") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !sending) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
                    } else {
                        TextButton(
                            onClick = { group?.let { onPost(it, title, body) } },
                            enabled = group != null && title.isNotBlank() && body.isNotBlank(),
                        ) { Text("Post") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state is NewTopicState.Error) {
                Text(
                    text = state.message.ifBlank { "Couldn't create the topic. Try again." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            var groupMenuExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = groupMenuExpanded,
                onExpandedChange = { if (!sending) groupMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = group?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Group") },
                    placeholder = { Text("Choose a group") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupMenuExpanded) },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = groupMenuExpanded,
                    onDismissRequest = { groupMenuExpanded = false },
                ) {
                    groups.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = {
                                group = candidate
                                groupMenuExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                // Hard clamp rather than error state: Ravelry rejects longer titles anyway.
                onValueChange = { title = it.take(NewTopicViewModel.MAX_TITLE_LENGTH) },
                label = { Text("Title") },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Your post") },
                placeholder = { Text("Write your post…") },
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 8.dp),
            )
        }
    }
}
