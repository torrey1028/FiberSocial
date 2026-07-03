package com.autom8ed.fibersocial.events

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.PostBody
import com.autom8ed.fibersocial.feed.html.HtmlPostParser

/**
 * Full details of one event: when, where, the description (rendered like a forum post
 * body), and the forum discussions linked to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    state: EventDetailState,
    onBack: () -> Unit,
    onDiscussionClick: (EventDiscussion) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is EventDetailState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is EventDetailState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

            is EventDetailState.Loaded -> EventDetailContent(
                detail = state.detail,
                padding = padding,
                onDiscussionClick = onDiscussionClick,
            )
        }
    }
}

@Composable
private fun EventDetailContent(
    detail: EventDetail,
    padding: PaddingValues,
    onDiscussionClick: (EventDiscussion) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item(key = "header") {
            Text(detail.title, style = MaterialTheme.typography.titleLarge)
            if (detail.eventType != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail.eventType!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.whenText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "🗓 ${detail.whenText}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (detail.venue != null) {
            item(key = "venue") {
                VenueCard(detail.venue!!)
                Spacer(Modifier.height(16.dp))
            }
        }

        if (detail.descriptionHtml.isNotBlank()) {
            item(key = "description") {
                PostBody(
                    document = remember(detail.descriptionHtml) {
                        HtmlPostParser.parse(detail.descriptionHtml)
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        if (detail.discussions.isNotEmpty()) {
            item(key = "discussions_header") {
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Discussions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            items(detail.discussions, key = { it.topicId }) { discussion ->
                DiscussionRow(discussion, onClick = { onDiscussionClick(discussion) })
            }
        }
    }
}

@Composable
private fun VenueCard(venue: EventVenue) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        val rows = listOfNotNull(venue.name, venue.address, venue.cityState, venue.country)
        rows.forEachIndexed { index, row ->
            Text(
                text = row,
                style = if (index == 0) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.bodySmall,
                color = if (index == 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscussionRow(discussion: EventDiscussion, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = discussion.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "💬 ${discussion.postsCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
