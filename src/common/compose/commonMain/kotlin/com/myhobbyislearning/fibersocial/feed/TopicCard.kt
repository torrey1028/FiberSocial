package com.myhobbyislearning.fibersocial.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.html.parseSummaryDocument
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.ui.Avatar

/**
 * A forum-style feed card (issue #185): the topic title, who started it and when (issue
 * #242), the author-written summary rendered in full (omitted when there is none), the
 * reply count, how many posts are unread (from Ravelry's own read marker), and when it
 * was last active.
 */
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
                Avatar(url = item.author.avatarUrl, size = 40.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Issue #242: show when the topic was started alongside who started it,
                    // in addition to the last-activity time in the bottom meta row. Falls
                    // back to the plain "Started by" line when createdAt is missing or the
                    // timestamp can't be parsed (relativeTime returns "" in both cases).
                    val startedRelative = relativeTime(item.createdAt)
                    Text(
                        text = if (startedRelative.isNotBlank()) {
                            "Started $startedRelative by @${item.author.username}"
                        } else {
                            "Started by @${item.author.username}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The summary rendered in full — no clipping (issue #185). Topics without a
            // summary show just the title + meta.
            if (item.hasSummary) {
                Spacer(modifier = Modifier.height(8.dp))
                val document = remember(item) { item.parseSummaryDocument() }
                // Non-interactive so taps on the summary open the topic (the whole card is
                // the tap target) instead of a link/image swallowing them (issue #216).
                PostBody(document = document, interactive = false)
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.postCount == 1) "💬 1 post" else "💬 ${item.postCount} posts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${item.unreadCount} new",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Labeled "Last post" (not just the bare time) so it isn't confused with
                // the "Started ... " time now shown above (issue #242). A topic with only
                // its opening post (postCount <= 1) has no reply yet, so its own start
                // time IS the last post's time — use createdAt rather than lastPostAt
                // there, regardless of what Ravelry's replied_at happens to report for an
                // un-replied-to topic (its exact null-vs-populated behavior in that case
                // isn't relied on here).
                val lastPostRelative = relativeTime(
                    if (item.postCount <= 1) item.createdAt else item.lastPostAt,
                )
                Text(
                    text = if (lastPostRelative.isNotBlank()) "Last post $lastPostRelative" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
