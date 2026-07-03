package com.autom8ed.fibersocial.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.ui.Avatar
import com.autom8ed.fibersocial.feed.PostBody
import com.autom8ed.fibersocial.feed.html.HtmlPostParser

/**
 * Full details of one event: when, where, the description (rendered like a forum post
 * body), and a save/unsave toggle (Ravelry's RSVP). The event page's "discussions"
 * table is deliberately not shown — it's just the hosting group's recent topics, which
 * the feed already covers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    state: EventDetailState,
    attendees: List<EventAttendee>?,
    onBack: () -> Unit,
    onToggleAttendance: () -> Unit,
) {
    // System back must mirror the top-bar back arrow instead of exiting the app
    // (same contract as TopicDetailScreen; see issue #38 / PR #56).
    BackHandler(onBack = onBack)
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
                attendees = attendees,
                padding = padding,
                onToggleAttendance = onToggleAttendance,
            )
        }
    }
}

@Composable
private fun EventDetailContent(
    detail: EventDetail,
    attendees: List<EventAttendee>?,
    padding: PaddingValues,
    onToggleAttendance: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(detail.whenText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // RSVP needs the page's authenticity token; without one the toggle can't work.
            if (detail.csrfToken != null) {
                Spacer(Modifier.height(12.dp))
                AttendButton(attending = detail.attending, onClick = onToggleAttendance)
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
            }
        }

        if (!attendees.isNullOrEmpty()) {
            item(key = "going_header") {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Going (${attendees.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
            // Namespaced so a username can never collide with the literal item
            // keys above ("header", "going_header", …); duplicate usernames are
            // already deduped by EventPeopleParser.
            items(attendees, key = { "attendee:${it.username}" }) { attendee ->
                AttendeeRow(attendee)
            }
        }
    }
}

@Composable
private fun AttendeeRow(attendee: EventAttendee) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Avatar(url = attendee.avatarUrl, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "@${attendee.username}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** RSVP toggle — the site calls this saving an event ("save event" / "event saved"). */
@Composable
private fun AttendButton(attending: Boolean, onClick: () -> Unit) {
    if (attending) {
        Button(onClick = onClick) { Text("✓ Going") }
    } else {
        OutlinedButton(onClick = onClick) { Text("RSVP") }
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

