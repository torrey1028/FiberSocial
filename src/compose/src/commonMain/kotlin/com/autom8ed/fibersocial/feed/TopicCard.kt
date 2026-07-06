package com.autom8ed.fibersocial.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.autom8ed.fibersocial.ui.Avatar
import com.autom8ed.fibersocial.feed.html.parsePreviewDocument
import com.autom8ed.fibersocial.feed.html.previewImageUrl
import com.autom8ed.fibersocial.feed.html.previewInlines
import com.autom8ed.fibersocial.feed.models.FeedItem

@Composable
fun TopicCard(
    item: FeedItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (item.sticky) {
                Text(
                    text = "📌 Pinned",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Avatar(url = item.displayAuthor.avatarUrl, size = 40.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Rich preview (issue #154): render the post's actual formatting
                    // instead of stripping it (imperfectly) to plain text, plus a
                    // thumbnail of its first photo. The old stripped string stays as a
                    // fallback for content that flattens to nothing renderable.
                    val document = remember(item) { item.parsePreviewDocument() }
                    val previewInlines = remember(item) { document.previewInlines() }
                    val previewImage = remember(item) { document.previewImageUrl() }
                    val preview = buildInlineText(
                        content = previewInlines,
                        linkColor = MaterialTheme.colorScheme.primary,
                        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                    ).takeIf { it.text.isNotBlank() }
                        ?: AnnotatedString(item.displayPreview)
                    if (preview.text.isNotBlank() || previewImage != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            if (preview.text.isNotBlank()) {
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            if (previewImage != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                AsyncImage(
                                    model = previewImage,
                                    contentDescription = "Preview photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@${item.displayAuthor.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "💬 ${item.replyCount} · ${relativeTime(item.lastPostAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
