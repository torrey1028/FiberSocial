@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.moderation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Manage/unblock list, reached from Settings (issue #410's "manage blocked users" step).
 * Every entry is a Ravelry username the local [com.myhobbyislearning.fibersocial.moderation.BlockedUsersStore]
 * is currently hiding content from; tapping "Unblock" reverses it immediately — mirroring
 * how blocking itself takes effect immediately, unblocking needs no confirmation dialog of
 * its own, since it only ever restores content, never removes it.
 *
 * @param blockedUsernames Currently blocked usernames, in the order the caller supplies
 *   (typically insertion order isn't preserved by a `Set`, so callers that care about a
 *   stable order should sort before passing this in).
 * @param onUnblock Invoked with the username to unblock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(
    blockedUsernames: List<String>,
    onBack: () -> Unit,
    onUnblock: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (blockedUsernames.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("BlockedUsersEmpty"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "You haven't blocked anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("BlockedUsersList"),
            ) {
                items(blockedUsernames, key = { it }) { username ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BlockGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onUnblock(username) },
                            modifier = Modifier.testTag("Unblock-$username"),
                        ) { Text("Unblock") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
