package com.autom8ed.fibersocial.feed

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailScreen(
    topic: FeedItem.DiscussionTopic,
    postsState: TopicDetailState,
    onBack: () -> Unit,
) {
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
                    MarkwonHtmlText(html = topic.bodySummary)
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
                    ReplyItem(post = post)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReplyItem(post: Post) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        AuthorRow(user = post.user, timestamp = post.createdAt)
        if (post.bodyHtml.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            MarkwonHtmlText(html = post.bodyHtml)
        }
    }
}

@Composable
private fun AuthorRow(user: RavelryUser?, timestamp: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = user?.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
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

@Composable
fun MarkwonHtmlText(html: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    // Capture theme values in Compose scope so the AndroidView update block can apply them
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textSizeSp = MaterialTheme.typography.bodyMedium.fontSize.value

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setLinkTextColor(linkColor)
            tv.textSize = textSizeSp
            markwon.setMarkdown(tv, html)
        },
        modifier = modifier.fillMaxWidth(),
    )
}
