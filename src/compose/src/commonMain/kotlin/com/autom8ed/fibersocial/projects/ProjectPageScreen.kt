@file:OptIn(ExperimentalComposeUiApi::class)

package com.autom8ed.fibersocial.projects

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil3.compose.AsyncImage
import com.autom8ed.fibersocial.feed.PostBody
import com.autom8ed.fibersocial.feed.html.HtmlPostParser
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.relativeTime
import com.autom8ed.fibersocial.ui.Avatar
import com.autom8ed.fibersocial.ui.DeleteConfirmDialog
import com.autom8ed.fibersocial.ui.MessageComposer

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
    pattern: PatternInfo? = null,
    currentUsername: String? = null,
    onDeleteComment: (ProjectComment) -> Unit = {},
    onPostAcknowledged: () -> Unit = {},
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
                pattern = pattern,
                commentsState = commentsState,
                postState = postState,
                currentUsername = currentUsername,
                onOpenOnRavelry = { uriHandler.openUri(link.webUrl) },
                onPostComment = onPostComment,
                onPostErrorShown = onPostErrorShown,
                onDeleteComment = onDeleteComment,
                onPostAcknowledged = onPostAcknowledged,
                modifier = Modifier.padding(padding),
            )

            is ProjectPageState.Hidden -> Unit
        }
    }
}

@Composable
private fun ProjectContent(
    loaded: ProjectPageState.Loaded,
    pattern: PatternInfo?,
    commentsState: ProjectCommentsState,
    postState: CommentPostState,
    currentUsername: String?,
    onOpenOnRavelry: () -> Unit,
    onPostComment: (String) -> Unit,
    onPostErrorShown: () -> Unit,
    onDeleteComment: (ProjectComment) -> Unit,
    onPostAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = loaded.project
    val uriHandler = LocalUriHandler.current
    // rememberSaveable so an open full-screen photo survives rotation, matching the
    // composer text below (which is also saveable).
    var fullScreenPhoto by rememberSaveable { mutableStateOf<Int?>(null) }

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

        PatternRow(project = project, pattern = pattern, onOpenPattern = { uriHandler.openUri(it) })
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
            currentUsername = currentUsername,
            onPostComment = onPostComment,
            onPostErrorShown = onPostErrorShown,
            onDeleteComment = onDeleteComment,
            onPostAcknowledged = onPostAcknowledged,
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
    currentUsername: String?,
    onPostComment: (String) -> Unit,
    onPostErrorShown: () -> Unit,
    onDeleteComment: (ProjectComment) -> Unit,
    onPostAcknowledged: () -> Unit,
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
                    commentsState.comments.forEach { comment ->
                        val deletable = currentUsername != null &&
                            comment.user?.username?.equals(currentUsername, ignoreCase = true) == true
                        CommentRow(
                            comment = comment,
                            onDelete = if (deletable) ({ onDeleteComment(comment) }) else null,
                        )
                    }
                }
            }
    }

    Spacer(Modifier.height(12.dp))
    CommentComposer(
        postState = postState,
        onPost = onPostComment,
        onErrorShown = onPostErrorShown,
        onPosted = onPostAcknowledged,
    )
}

@Composable
private fun CommentRow(comment: ProjectComment, onDelete: (() -> Unit)?) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        DeleteConfirmDialog(
            itemLabel = "comment",
            container = "project",
            onConfirm = { onDelete?.invoke() },
            onDismiss = { confirming = false },
        )
    }
    Row(verticalAlignment = Alignment.Top) {
        Avatar(url = comment.user?.avatarUrl, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
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
        if (onDelete != null) {
            IconButton(onClick = { confirming = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete comment",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommentComposer(
    postState: CommentPostState,
    onPost: (String) -> Unit,
    onErrorShown: () -> Unit,
    onPosted: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val sending = postState is CommentPostState.Sending

    // Clear the field only once the comment actually posts — not eagerly on tap — so a
    // failed post keeps the user's text (which CommentPostState.Error documents).
    LaunchedEffect(postState) {
        if (postState is CommentPostState.Posted) {
            text = ""
            onPosted()
        }
    }

    MessageComposer(
        text = text,
        onTextChange = {
            text = it
            if (postState is CommentPostState.Error) onErrorShown()
        },
        sending = sending,
        placeholder = "Add a comment…",
        sendContentDescription = "Post comment",
        onSend = { onPost(text) },
        modifier = Modifier.fillMaxWidth().imePadding(),
        errorTexts = listOfNotNull((postState as? CommentPostState.Error)?.message),
    )
}

/**
 * Full-screen photo viewer: tapping a project photo opens it large over a scrim, with a
 * horizontal pager to swipe through all of the project's photos. Tap a photo (or system
 * back / the close button) to dismiss. Shows the largest available size.
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * Next zoom [scale] (clamped 1x–4x) and pan [offset] for the full-screen photo after a
 * pinch [zoom] and [pan] over a view of [size] (issue #192). Pan is clamped so the image
 * can't be dragged past its edges; zooming back to 1x recenters it.
 */
internal fun computeZoomTransform(
    scale: Float,
    offset: Offset,
    zoom: Float,
    pan: Offset,
    size: IntSize,
): Pair<Float, Offset> {
    val newScale = (scale * zoom).coerceIn(1f, 4f)
    if (newScale <= 1f) return 1f to Offset.Zero
    val maxX = size.width * (newScale - 1f) / 2f
    val maxY = size.height * (newScale - 1f) / 2f
    val newOffset = Offset(
        (offset.x + pan.x).coerceIn(-maxX, maxX),
        (offset.y + pan.y).coerceIn(-maxY, maxY),
    )
    return newScale to newOffset
}

@Composable
private fun FullScreenPhoto(photos: List<ProjectPhoto>, initialIndex: Int, onDismiss: () -> Unit) {
    if (photos.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, photos.size - 1),
            pageCount = { photos.size },
        )
        // Pinch-to-zoom state for the photo on screen (issue #192). Hoisted so the pager
        // can lock horizontal paging while zoomed (handing drags to panning instead); reset
        // whenever the visible page changes so each photo opens fit-to-screen.
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(pagerState.currentPage) {
            scale = 1f
            offset = Offset.Zero
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // While zoomed, a horizontal drag pans the image instead of flipping pages.
                userScrollEnabled = scale <= 1f,
            ) { page ->
                val photo = photos[page]
                val isCurrent = page == pagerState.currentPage
                val pageScale = if (isCurrent) scale else 1f
                val pageOffset = if (isCurrent) offset else Offset.Zero
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = photo.medium2Url ?: photo.mediumUrl ?: photo.smallUrl,
                        contentDescription = "Project photo ${page + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = pageScale
                                scaleY = pageScale
                                translationX = pageOffset.x
                                translationY = pageOffset.y
                            }
                            .pointerInput(isCurrent) {
                                if (!isCurrent) return@pointerInput
                                // Tap while fit-to-screen dismisses; double-tap toggles 2x.
                                detectTapGestures(
                                    onTap = { if (scale <= 1f) onDismiss() },
                                    onDoubleTap = {
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2f
                                        }
                                    },
                                )
                            }
                            .pointerInput(isCurrent) {
                                if (!isCurrent) return@pointerInput
                                // Consume gestures only for a pinch (2+ pointers) or once
                                // zoomed, so a plain one-finger swipe at 1x still reaches the
                                // pager and flips pages (the #192 gesture-coexistence catch).
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2 || scale > 1f) {
                                            val (newScale, newOffset) = computeZoomTransform(
                                                scale = scale,
                                                offset = offset,
                                                zoom = event.calculateZoom(),
                                                pan = event.calculatePan(),
                                                size = size,
                                            )
                                            scale = newScale
                                            offset = newOffset
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            if (photos.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}
