@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.profile

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.PostBody
import com.myhobbyislearning.fibersocial.feed.html.HtmlPostParser
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.projects.ProjectLink
import com.myhobbyislearning.fibersocial.projects.ProjectSummary
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.myhobbyislearning.fibersocial.ui.Avatar
import com.myhobbyislearning.fibersocial.ui.GroupBadge

/**
 * In-app user profile (issue #194), opened by tapping a username: the user's header,
 * the projects they've made, and the groups they're in. Tapping a project opens the
 * in-app project page via [onOpenProject] (issue #244); tapping a group selects it in
 * the feed via [onGroupClick]; "Send message" opens the composer addressed to them via
 * [onSendMessage] (issue #373).
 *
 * ## Why the own-profile gate lives HERE and not at the call site
 *
 * Ravelry rejects messaging yourself, so the action must not appear on your own profile.
 * That decision is made from [currentUsername] inside this screen rather than by the host
 * passing a null callback, because the profile is reachable from several places (post
 * bylines, event attendee lists, project pages) and a gate at the call site is one that a
 * later entry point can forget. Here it is structurally impossible to reach the action
 * without having supplied the comparison.
 *
 * @param currentUsername The signed-in user's handle. `null` (the feed hasn't resolved the
 *   user yet) HIDES the action rather than showing it: offering "message yourself" is a
 *   worse failure than briefly omitting an affordance, and the profile is only reachable
 *   from screens the loaded feed already backs.
 * @param onSendMessage Invoked with the profile owner's username.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    state: UserProfileState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenProject: (ProjectLink) -> Unit = {},
    onGroupClick: (Group) -> Unit = {},
    onSendMessage: (String) -> Unit = {},
    currentUsername: String? = null,
) {
    if (state is UserProfileState.Hidden) return
    val username = when (state) {
        is UserProfileState.Loading -> state.username
        is UserProfileState.Loaded -> state.profile.username
        is UserProfileState.Error -> state.username
        is UserProfileState.Hidden -> return
    }
    val uriHandler = LocalUriHandler.current

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("@$username") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is UserProfileState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UserProfileState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.message.ifBlank { "Couldn't load the profile." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = { uriHandler.openUri("https://www.ravelry.com/people/$username") }) {
                    Text("Open on Ravelry")
                }
            }

            is UserProfileState.Loaded -> ProfileContent(
                state = state,
                onOpenProject = { project ->
                    onOpenProject(ProjectLink(state.profile.username, project.permalink))
                },
                onGroupClick = onGroupClick,
                // Case-insensitively: Ravelry treats handles case-insensitively, and the
                // casing the feed resolved for the signed-in user need not match the casing
                // the profile fetch echoed back.
                onSendMessage = if (
                    currentUsername != null &&
                    !currentUsername.equals(state.profile.username, ignoreCase = true)
                ) {
                    { onSendMessage(state.profile.username) }
                } else {
                    null
                },
                modifier = Modifier.padding(padding),
            )

            is UserProfileState.Hidden -> Unit
        }
    }
}

/**
 * @param onSendMessage Null when the action must not be offered at all — see the own-profile
 *   gate on [UserProfileScreen]. Nullable rather than a boolean so there is no way to render
 *   an enabled button with nothing behind it.
 */
@Composable
private fun ProfileContent(
    state: UserProfileState.Loaded,
    onOpenProject: (ProjectSummary) -> Unit,
    onGroupClick: (Group) -> Unit,
    onSendMessage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(url = profile.avatarUrl, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                profile.firstName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge)
                }
                Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                profile.location?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // The header's action row (issue #373). Directly under the header and above the
        // "about" text on purpose: it acts on the person identified immediately above it,
        // and putting it below a bio of arbitrary length would push it off-screen for
        // exactly the users whose profile the reader spent the longest looking at.
        onSendMessage?.let { send ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().testTag("ProfileActions"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = send, modifier = Modifier.testTag("SendMessageAction")) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Send message")
                }
            }
        }

        profile.aboutHtml?.takeIf { it.isNotBlank() }?.let { about ->
            Spacer(Modifier.height(12.dp))
            val document = remember(profile.id) { HtmlPostParser.parse(about) }
            PostBody(document = document)
        }

        if (state.projects.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Projects", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // Bounded height so this grid doesn't fight the outer scroll; it scrolls
            // internally if the user has many projects.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.projects, key = { it.id }) { project ->
                    ProjectThumb(
                        project = project,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenProject(project) },
                    )
                }
            }
        }

        if (state.groups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Groups", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            state.groups.forEach { group ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGroupClick(group) }
                        .padding(vertical = 6.dp),
                ) {
                    GroupBadge(group = group, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(group.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.projects.isEmpty() && state.groups.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "No public projects or groups to show.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A project's thumbnail for the grid: the sharpest photo the list gives us (medium →
 * small → square), over a surfaceVariant background so a slow or failed load reads as a
 * neutral tile rather than black. A project with no photo at all shows its name (wrapped,
 * truncated to fit) on the grey tile instead of a black void.
 */
@Composable
private fun ProjectThumb(project: ProjectSummary, modifier: Modifier = Modifier) {
    // Skips blanks, not just nulls: Ravelry occasionally serves an empty string for a size
    // it hasn't generated, and a plain elvis chain would stop at "" and show a blank tile
    // instead of falling through to a real size (or the name placeholder).
    val url = listOf(
        project.firstPhoto?.mediumUrl,
        project.firstPhoto?.smallUrl,
        project.firstPhoto?.squareUrl,
    ).firstOrNull { !it.isNullOrBlank() }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = project.name.ifBlank { "Project" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = project.name.trim().ifBlank { "Untitled" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}
