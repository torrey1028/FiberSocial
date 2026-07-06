package com.autom8ed.fibersocial.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.PostBody
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.html.HtmlPostParser

/**
 * In-app page for a Ravelry project (issue #103): opened when a
 * `ravelry.com/projects/{user}/{permalink}` link is tapped in a post, instead of
 * bouncing to the browser. Photos, the key facts, and the owner's notes; an
 * open-on-Ravelry escape hatch covers everything the page doesn't show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPageScreen(
    state: ProjectPageState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state is ProjectPageState.Hidden) return
    val link = when (state) {
        is ProjectPageState.Loading -> state.link
        is ProjectPageState.Loaded -> state.link
        is ProjectPageState.Error -> state.link
        is ProjectPageState.Hidden -> return
    }
    val uriHandler = LocalUriHandler.current

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text((state as? ProjectPageState.Loaded)?.project?.name ?: "Project")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is ProjectPageState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is ProjectPageState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.message.ifBlank { "Couldn't load the project." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = { uriHandler.openUri(link.webUrl) }) { Text("Open on Ravelry") }
            }

            is ProjectPageState.Loaded -> ProjectContent(
                project = state.project,
                ownerUsername = link.username,
                onOpenOnRavelry = { uriHandler.openUri(link.webUrl) },
                modifier = Modifier.padding(padding),
            )

            is ProjectPageState.Hidden -> Unit
        }
    }
}

@Composable
private fun ProjectContent(
    project: ProjectDetail,
    ownerUsername: String,
    onOpenOnRavelry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (project.photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Positional keys: ProjectPhoto.id defaults to 0, and two id-less
                // photos would collide as LazyRow keys.
                items(project.photos) { photo ->
                    AsyncImage(
                        model = photo.mediumUrl ?: photo.gridUrl,
                        contentDescription = "Project photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("by $ownerUsername", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        FactRow("Pattern", project.patternName)
        FactRow("Status", listOfNotNull(project.statusName, project.progress?.let { "$it%" }).joinToString(" · ").ifBlank { null })
        FactRow("Craft", project.craftName)
        FactRow("Started", project.started)
        FactRow("Completed", project.completed)
        FactRow("Made for", project.madeFor)
        FactRow("Size", project.size)

        if (project.tagNames.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                project.tagNames.take(4).forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag) }, enabled = false)
                }
            }
        }

        val notes = project.notes.orEmpty()
        val notesHtml = project.notesHtml.orEmpty()
        if (notes.isNotBlank() || notesHtml.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Notes", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // Same contract as post bodies: the Markdown source is canonical, the
            // rendering resolves emoji / is the fallback (issues #102/#144).
            val document = remember(project) {
                if (notes.isNotBlank()) MarkdownPostParser.parse(notes, renderedHtml = notesHtml)
                else HtmlPostParser.parse(notesHtml)
            }
            PostBody(document = document)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenOnRavelry) { Text("Open on Ravelry") }
    }
}

@Composable
private fun FactRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
