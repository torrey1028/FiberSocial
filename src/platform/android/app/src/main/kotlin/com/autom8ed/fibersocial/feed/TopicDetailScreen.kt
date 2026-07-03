package com.autom8ed.fibersocial.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.html.HtmlPostParser
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.ui.Avatar
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.VoteType
import com.autom8ed.fibersocial.feed.models.hasVoted
import com.autom8ed.fibersocial.feed.models.voteCount
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailScreen(
    topic: FeedItem.DiscussionTopic,
    postsState: TopicDetailState,
    onBack: () -> Unit,
    onVote: (Post, VoteType) -> Unit,
    currentUsername: String? = null,
    deleteState: DeleteState = DeleteState.Idle,
    onDeletePost: (Post) -> Unit = {},
    onDeleteErrorShown: () -> Unit = {},
    editState: EditState = EditState.Idle,
    onEditPost: (Post, String) -> Unit = { _, _ -> },
    onEditErrorShown: () -> Unit = {},
    replyState: ReplyState = ReplyState.Idle,
    onSendReply: (String) -> Unit = {},
    onReplySent: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Post?>(null) }
    pendingDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this post?") },
            text = { Text("This removes your post from the topic for everyone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePost(post)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
    if (deleteState is DeleteState.Error) {
        PostActionErrorDialog(
            title = "Couldn't delete the post",
            message = deleteState.message,
            onDismiss = onDeleteErrorShown,
        )
    }
    if (editState is EditState.Error) {
        PostActionErrorDialog(
            title = "Couldn't save your edit",
            message = editState.message,
            onDismiss = onEditErrorShown,
        )
    }
    // The system back button must mirror the top-bar back arrow instead of
    // finishing the activity (issue #38).
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic.groupName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            ReplyComposer(
                replyState = replyState,
                onSend = onSendReply,
                onSent = onReplySent,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "header") {
                Text(topic.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                AuthorRow(user = topic.author, timestamp = topic.lastPostAt)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                if (topic.bodySummary.isNotBlank()) {
                    PostBody(document = remember(topic.bodySummary) { HtmlPostParser.parse(topic.bodySummary) })
                    Spacer(Modifier.height(16.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "💬 ${topic.replyCount} ${if (topic.replyCount == 1) "reply" else "replies"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            when (postsState) {
                is TopicDetailState.Loading -> item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                is TopicDetailState.Error -> item(key = "error") {
                    Text(
                        text = "Couldn't load replies. Check your connection and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                is TopicDetailState.Loaded -> items(
                    postsState.posts,
                    key = { it.id },
                ) { post ->
                    val mine = currentUsername != null && post.user?.username == currentUsername
                    ReplyItem(
                        post = post,
                        onVote = { type -> onVote(post, type) },
                        canDelete = mine,
                        deleting = (deleteState as? DeleteState.Deleting)?.postId == post.id,
                        onDelete = { pendingDelete = post },
                        canEdit = mine && post.editable,
                        saving = (editState as? EditState.Saving)?.postId == post.id,
                        saveFailed = editState is EditState.Error,
                        // One edit/delete at a time (the ViewModel enforces it): while
                        // any post's operation is in flight, other posts' actions are
                        // disabled instead of silently dropping taps.
                        actionsEnabled = deleteState !is DeleteState.Deleting &&
                            editState !is EditState.Saving,
                        onEdit = { newBody -> onEditPost(post, newBody) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun ReplyItem(
    post: Post,
    onVote: (VoteType) -> Unit,
    canDelete: Boolean = false,
    deleting: Boolean = false,
    onDelete: () -> Unit = {},
    canEdit: Boolean = false,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    actionsEnabled: Boolean = true,
    onEdit: (String) -> Unit = {},
) {
    // Saveable: scrolling the item out of a LazyColumn (or rotating) disposes plain
    // remember state, which would silently discard an in-progress edit.
    var editing by rememberSaveable(post.id) { mutableStateOf(false) }
    // Leave edit mode only when a save SUCCEEDS (saving -> Idle). A failed save
    // (saving -> Error) keeps the editor open so the edited text isn't lost —
    // same contract as the reply composer.
    val wasSaving = remember { mutableStateOf(false) }
    if (wasSaving.value && !saving && !saveFailed) editing = false
    wasSaving.value = saving

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                AuthorRow(user = post.user, timestamp = post.createdAt)
            }
            if (deleting || saving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                if (canEdit) {
                    IconButton(onClick = { editing = !editing }, enabled = actionsEnabled) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, enabled = actionsEnabled) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (editing) {
            PostEditor(
                initial = post.body,
                onCancel = { editing = false },
                onSave = onEdit,
            )
        } else {
            if (post.bodyHtml.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PostBody(document = remember(post.bodyHtml) { HtmlPostParser.parse(post.bodyHtml) })
            }
            Spacer(Modifier.height(8.dp))
            VoteRow(post = post, onVote = onVote)
        }
    }
}

@Composable
private fun PostEditor(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    // Saveable for the same reason as `editing`: the draft must survive the item
    // scrolling out of composition and configuration changes.
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    Column {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank() && text.trim() != initial.trim(),
            ) { Text("Save") }
        }
    }
}

/** One-shot modal for a failed post operation; shows the real failure reason. */
@Composable
private fun PostActionErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message.ifBlank { "Check your connection and try again." }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private val VOTE_TYPE_EMOJI: Map<VoteType, String> = mapOf(
    VoteType.INTERESTING to "🤔",
    VoteType.EDUCATIONAL to "📚",
    VoteType.FUNNY to "😂",
    VoteType.AGREE to "👍",
    VoteType.DISAGREE to "👎",
    VoteType.LOVE to "❤️",
)

@Composable
private fun VoteRow(post: Post, onVote: (VoteType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VoteType.entries.forEach { type ->
            VoteButton(
                emoji = VOTE_TYPE_EMOJI.getValue(type),
                count = post.voteCount(type),
                voted = post.hasVoted(type),
                onClick = { onVote(type) },
            )
        }
    }
}

@Composable
private fun VoteButton(emoji: String, count: Int, voted: Boolean, onClick: () -> Unit) {
    val background = if (voted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = emoji, style = MaterialTheme.typography.labelMedium)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(text = count.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AuthorRow(user: RavelryUser?, timestamp: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(url = user?.avatarUrl, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = "@${user?.username ?: "unknown"}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (timestamp != null) {
                Text(
                    text = relativeTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Text field + send button pinned below the reply thread. Keeps its text on failure
 * so nothing is lost; clears it only once the reply is confirmed [ReplyState.Sent].
 */
@Composable
internal fun ReplyComposer(
    replyState: ReplyState,
    onSend: (String) -> Unit,
    onSent: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val sending = replyState is ReplyState.Sending

    LaunchedEffect(replyState) {
        if (replyState is ReplyState.Sent) {
            text = ""
            onSent()
        }
    }

    Surface(tonalElevation = 3.dp) {
        // imePadding keeps the composer above the on-screen keyboard while typing.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (replyState is ReplyState.Error) {
                Text(
                    text = replyState.message.ifBlank { "Couldn't post your reply. Try again." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Write a reply…") },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
                } else {
                    IconButton(
                        onClick = { onSend(text) },
                        enabled = text.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply")
                    }
                }
            }
        }
    }
}
