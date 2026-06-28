package com.autom8ed.fibersocial.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.models.FeedItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(viewModel: FeedAndroidViewModel) {
    val state by viewModel.feed.state.collectAsState()

    val title = when (val s = state) {
        is FeedState.Loaded -> s.selectedGroup?.name ?: "All Groups"
        is FeedState.Refreshing -> s.stale.selectedGroup?.name ?: "All Groups"
        else -> "FiberSocial"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { /* group drawer — future PR */ }) {
                        Icon(Icons.Default.Menu, contentDescription = "Select group")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            FeedState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is FeedState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                val message = if (s.message.contains("403") || s.message.contains("401")) {
                    "Session expired. Please log out and sign in again."
                } else {
                    "Couldn't load the feed. Check your connection and try again."
                }
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            is FeedState.Loaded -> FeedList(s.items, Modifier.padding(padding))
            is FeedState.Refreshing -> FeedList(s.stale.items, Modifier.padding(padding))
        }
    }
}

@Composable
private fun FeedList(items: List<FeedItem>, modifier: Modifier = Modifier) {
    val renderable = items.filterIsInstance<FeedItem.DiscussionPost>()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(renderable, key = { it.id }) { item ->
            DiscussionPostCard(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
