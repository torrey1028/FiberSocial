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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.ui.Avatar
import com.autom8ed.fibersocial.feed.html.HtmlPostParser
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
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
) {
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
                    ReplyItem(post = post, onVote = { type -> onVote(post, type) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReplyItem(post: Post, onVote: (VoteType) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        AuthorRow(user = post.user, timestamp = post.createdAt)
        if (post.bodyHtml.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PostBody(document = remember(post.bodyHtml) { HtmlPostParser.parse(post.bodyHtml) })
        }
        Spacer(Modifier.height(8.dp))
        VoteRow(post = post, onVote = onVote)
    }
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
