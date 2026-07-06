package com.autom8ed.fibersocial.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.PostBody
import com.autom8ed.fibersocial.feed.html.HtmlPostParser
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.relativeTime
import com.autom8ed.fibersocial.ui.Avatar

/**
 * In-app page for a Ravelry project (issue #103): opened when a
 * `ravelry.com/projects/{user}/{permalink}` link is tapped in a post, instead of
 * bouncing to the browser. Photos (tap for full-screen), the key facts, the owner's
 * notes, and the comment thread with a composer; an open-on-Ravelry escape hatch
 * covers everything the page doesn't show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPageScreen(
    state: ProjectPageState,
    commentsState: ProjectCommentsState,
    postState: CommentPostState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPostComment: (String) -> Unit,
    onPostErrorShown: () -> Unit,
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
            TopAppBar(
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
                loaded = state,
                commentsState = commentsState,
                postState = postState,
                onOpenOnRavelry = { uriHandler.openUri(link.webUrl) },
                onPostComment = onPostComment,
                onPostErrorShown = onPostErrorShown,
                modifier = Modifier.padding(padding),
            )

            is ProjectPageState.Hidden -> Unit
        }
    }
}

@Composable
private fun ProjectContent(
    loaded: ProjectPageState.Loaded,
    commentsState: ProjectCommentsState,
    postState: CommentPostState,
    onOpenOnRavelry: () -> Unit,
    onPostComment: (String) -> Unit,
    onPostErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = loaded.project
    val uriHandler = LocalUriHandler.current
    var fullScreenPhoto by remember { mutableStateOf<Int?>(null) }

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
                items(project.photos.size) { index ->
                    val photo = project.photos[index]
                    AsyncImage(
                        model = photo.mediumUrl ?: photo.gridUrl,
                        contentDescription = "Project photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { fullScreenPhoto = index },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("by ${loaded.link.username}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        PatternRow(project = project, pattern = loaded.pattern, onOpenPattern = { uriHandler.openUri(it) })
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
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        CommentsSection(
            commentsState = commentsState,
            postState = postState,
            onPostComment = onPostComment,
            onPostErrorShown = onPostErrorShown,
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenOnRavelry) { Text("Open on Ravelry") }
    }

    fullScreenPhoto?.let { index ->
        FullScreenPhoto(
            photos = project.photos,
            initialIndex = index,
            onDismiss = { fullScreenPhoto = null },
        )
    }
}

@Composable
private fun PatternRow(project: ProjectDetail, pattern: PatternInfo?, onOpenPattern: (String) -> Unit) {
    val name = project.patternName ?: pattern?.name
    if (name.isNullOrBlank()) return
    val author = pattern?.author?.name?.takeIf { it.isNotBlank() }
    val linkColor = MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = "Pattern",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Column {
            if (pattern != null && pattern.permalink.isNotBlank()) {
                // Linked to a Ravelry database pattern → open its library page. No
                // in-app pattern screen yet, so this deliberately goes to the browser.
                val styled = buildAnnotatedString {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(name)
                    }
                }
                Text(
                    text = styled,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onOpenPattern(pattern.webUrl) },
                )
            } else {
                Text(text = name, style = MaterialTheme.typography.bodyMedium)
            }
            if (author != null) {
                Text(
                    text = "by $author",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

@Composable
private fun CommentsSection(
    commentsState: ProjectCommentsState,
    postState: CommentPostState,
    onPostComment: (String) -> Unit,
    onPostErrorShown: () -> Unit,
) {
    Text("Comments", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    when (commentsState) {
        is ProjectCommentsState.Loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
        is ProjectCommentsState.Error -> Text(
            text = commentsState.message.ifBlank { "Couldn't load comments." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ProjectCommentsState.Loaded ->
            if (commentsState.comments.isEmpty()) {
                Text(
                    text = "No comments yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    commentsState.comments.forEach { CommentRow(it) }
                }
            }
    }

    Spacer(Modifier.height(12.dp))
    CommentComposer(postState = postState, onPost = onPostComment, onErrorShown = onPostErrorShown)
}

@Composable
private fun CommentRow(comment: ProjectComment) {
    Row(verticalAlignment = Alignment.Top) {
        Avatar(url = comment.user?.avatarUrl, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "@${comment.user?.username ?: "unknown"}",
                    style = MaterialTheme.typography.labelMedium,
                )
                comment.createdAt?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = relativeTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Comments have no Markdown source field, only the rendered HTML.
            val document = remember(comment.id) { HtmlPostParser.parse(comment.commentHtml) }
            PostBody(document = document)
        }
    }
}

@Composable
private fun CommentComposer(
    postState: CommentPostState,
    onPost: (String) -> Unit,
    onErrorShown: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val sending = postState is CommentPostState.Sending

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        if (postState is CommentPostState.Error) {
            Text(
                text = postState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (postState is CommentPostState.Error) onErrorShown()
                },
                placeholder = { Text("Add a comment…") },
                enabled = !sending,
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(4.dp))
            } else {
                IconButton(
                    onClick = {
                        onPost(text)
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment")
                }
            }
        }
    }
}

/**
 * Full-screen photo viewer: tapping a project photo opens it large over a scrim, tap
 * anywhere (or system back) to dismiss. Shows the largest available size.
 */
@Composable
private fun FullScreenPhoto(photos: List<ProjectPhoto>, initialIndex: Int, onDismiss: () -> Unit) {
    val photo = photos.getOrNull(initialIndex) ?: return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = photo.medium2Url ?: photo.mediumUrl ?: photo.smallUrl,
                contentDescription = "Project photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
