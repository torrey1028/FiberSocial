package com.autom8ed.fibersocial.events

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.ui.PullToRefreshBox
import kotlinx.datetime.LocalDateTime

/**
 * Screen listing one group's upcoming events, soonest first. Opened from the calendar
 * badge on the group's drawer row (only shown when the group has events).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    state: EventsState,
    group: Group,
    onBack: () -> Unit,
    onEventClick: (GroupEvent) -> Unit,
    onRefresh: () -> Unit = {},
) {
    // System back must mirror the top-bar back arrow instead of exiting the app
    // (same contract as TopicDetailScreen; see issue #38 / PR #56).
    BackHandler(onBack = onBack)

    // Pull-to-refresh calls onRefresh(), which re-triggers a full load(); that briefly
    // reports EventsState.Loading again. Falling back to the last Loaded snapshot while
    // isRefreshing is in flight keeps the list on screen under the compact pull spinner
    // instead of replacing it with the full-screen loading indicator.
    var isRefreshing by remember(group.id) { mutableStateOf(false) }
    var lastLoaded by remember(group.id) { mutableStateOf<EventsState.Loaded?>(null) }
    LaunchedEffect(state) {
        if (state is EventsState.Loaded) lastLoaded = state
        if (state !is EventsState.Loading) isRefreshing = false
    }
    val displayState = if (state is EventsState.Loading && lastLoaded != null) lastLoaded!! else state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${group.name} events") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (displayState) {
            is EventsState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is EventsState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Couldn't load events. Check your connection and try again.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is EventsState.Loaded -> PullToRefreshBox(
                refreshing = isRefreshing,
                onRefresh = { isRefreshing = true; onRefresh() },
                modifier = Modifier.padding(padding),
            ) {
                val events = displayState.events.filter { it.group.id == group.id }
                if (events.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No upcoming events in this group.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(events, key = { it.event.permalink }) { groupEvent ->
                            EventCard(groupEvent, onClick = { onEventClick(groupEvent) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(groupEvent: GroupEvent, onClick: () -> Unit) {
    val event = groupEvent.event
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        DateChip(event.startsAt)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = event.whenText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The group is implicit now that the screen is per-group; only show turnout.
            if (event.attendeeCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${event.attendeeCount} going",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Calendar-page style chip: month abbreviation over day of month. */
@Composable
private fun DateChip(startsAt: LocalDateTime?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (startsAt == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("?", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = MONTH_ABBREVIATIONS[startsAt.monthNumber - 1],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = startsAt.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
