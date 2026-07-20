// BackHandler is still experimental in Compose Multiplatform; opted in file-wide exactly
// as TopicDetailScreen does, since this screen has the same "system back must mirror the
// top-bar arrow" requirement (issue #38).
@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.messages

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.PostBody
import com.myhobbyislearning.fibersocial.feed.html.HtmlPostParser
import com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp
import com.myhobbyislearning.fibersocial.feed.relativeTimeSince
import com.myhobbyislearning.fibersocial.ui.UserAvatar

/** Label an outbound message is attributed to, in place of the signed-in user's own name. */
private const val SELF_LABEL = "You"

/** Attribution for a message whose sender Ravelry did not name — see `MessageThreads.kt`. */
private const val UNKNOWN_SENDER = "(unknown)"

/** Shown in place of a relative timestamp when a message has no parseable `sent_at`. */
private const val UNKNOWN_TIME = "—"

/**
 * How wide a message bubble may grow. Short of full width so the leftover gutter — on the
 * left for a sent message, the right for a received one — carries the sent/received
 * distinction even where colour can't (high-contrast modes, greyscale, a colour-blind
 * reader). [PostBody] fills whatever width it is given, so this is a hard bound rather
 * than a maximum the content shrinks inside.
 */
private const val BUBBLE_WIDTH_FRACTION = 0.88f

/**
 * One conversation, read top to bottom (issue #371, epic #365).
 *
 * ## Bodies render through the shared [PostBody], entered at the HTML parser
 *
 * PMs read back as `content_html` and nothing else — `Message.content` is documented
 * write-only and its presence on reads is unresolved (see `Message.content`). So a body is
 * parsed with [HtmlPostParser], the project's single HTML→`PostDocument` converter, and
 * handed to the same [PostBody] the forum uses. Adding a second HTML path is an explicit
 * project trap; this adds none.
 *
 * Two assumptions [PostBody]'s forum callers make DO NOT hold here, and both are handled
 * by entering the pipeline at the parser rather than at the post-shaped helpers above it:
 *
 * - **There is no Markdown source.** `Post.parseBodyDocument()` prefers the Markdown
 *   `body` and only falls back to `body_html`; a PM has no `body` at all, so that helper
 *   is inapplicable (and is a `Post` extension besides). `HtmlPostParser.parse` directly is
 *   the correct entry point, exactly as `messagePreviewText` already does it.
 * - **There is no `:shortcode:` emoji harvesting step.** That splice lives in
 *   `MarkdownPostParser`, and exists to put emoji back into a body parsed from *Markdown*
 *   source. Server-rendered `content_html` already carries emoji as `<img>` tags, which
 *   `HtmlPostParser` turns into `Inline.Image`s that `Inline.Image.isInlineEmoji` sizes at
 *   text height. Nothing is missing.
 *
 * Unknown tags need no special handling: [HtmlPostParser] is lenient by design — unknown
 * blocks unwrap in place, unknown inline tags degrade to their children and log — so a PM
 * containing markup the forum never produces degrades to its text instead of breaking.
 *
 * `LocalProjectLinkOpener` is not provided over this screen, so a Ravelry project link in a
 * PM opens in the browser rather than the in-app project page. That is [PostBody]'s
 * documented fallback for every caller that doesn't set it, and wiring it here would mean
 * pushing a project page over a screen that is itself pushed over the messages list.
 *
 * @param state The open conversation and its body-backfill status.
 * @param currentUsername Signed-in user's username, used to tell sent from received. Blank
 *   makes everything read as received — see [messageDirection].
 * @param onBack Returns to the conversation list. Wired to BOTH the top-bar arrow and the
 *   system back gesture, and the read state is already applied by the time it fires: the
 *   mark-read POSTs are issued on open, not on exit (unlike `TopicDetailScreen`, whose
 *   read marker is a scroll high-water mark that can only be known when leaving).
 * @param onToggleMute Placeholder hook for per-thread muting. `null` — the current state —
 *   renders the menu item disabled rather than hiding it, so the affordance is visibly
 *   coming rather than silently absent. Issue #377 owns the behaviour and only needs to
 *   pass a real lambda here.
 * @param isMuted Whether this thread is muted; drives the menu item's wording once #377
 *   supplies it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    state: OpenThreadState,
    currentUsername: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleMute: (() -> Unit)? = null,
    isMuted: Boolean = false,
) {
    BackHandler(onBack = onBack)

    val thread = state.thread
    Scaffold(
        modifier = modifier.testTag("MessageThreadScreen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = thread.subject.takeIf { it.isNotBlank() } ?: "(no subject)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { ThreadOverflowMenu(isMuted = isMuted, onToggleMute = onToggleMute) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loadingBodies) ThreadBodyNotice(loading = true)
            if (state.bodyError != null) ThreadBodyNotice(loading = false)
            LazyColumn(
                // Not reversed and not scrolled to the bottom: the thread is read OLDEST →
                // NEWEST, and the interesting end of a conversation you just opened from a
                // row previewing its newest message is the part you haven't read.
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize().testTag("MessageThreadList"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(thread.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        direction = messageDirection(message, currentUsername),
                        counterpartName = thread.counterpart?.username?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
    }
}

/**
 * The top-bar overflow, modelled on `TopicDetailScreen`'s.
 *
 * Always present even though its single item is currently inert, so the mute affordance
 * lands in a place users have already learned rather than appearing from nowhere with #377.
 */
@Composable
private fun ThreadOverflowMenu(isMuted: Boolean, onToggleMute: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(if (isMuted) "Unmute notifications" else "Mute notifications") },
            // Disabled rather than absent while #377 is unbuilt: an enabled item wired to
            // nothing would read as a broken control, which is worse than a greyed one.
            enabled = onToggleMute != null,
            onClick = {
                menuOpen = false
                onToggleMute?.invoke()
            },
        )
    }
}

/** One-line status strip above the thread while bodies are being fetched, or after they failed. */
@Composable
private fun ThreadBodyNotice(loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(if (loading) "MessageBodiesLoading" else "MessageBodiesError"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (loading) "Loading messages…" else "Some messages couldn't be loaded.",
            style = MaterialTheme.typography.labelMedium,
            color = if (loading) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * One message.
 *
 * Sent and received are distinguished FOUR ways, not one: which side of the screen the
 * bubble sits on, its background colour, the corner that is squared off, and the
 * attribution line ("You" versus the sender's name). Colour alone would carry none of that
 * to a greyscale or high-contrast reader, and side alone none of it to a screen reader.
 *
 * [MessageDirection.UNKNOWN] is rendered as received — it is the same "not from me" the
 * unread rule already declines to guess about, and putting it on the sent side would
 * attribute someone else's words to the user.
 */
@Composable
private fun MessageBubble(
    message: Message,
    direction: MessageDirection,
    counterpartName: String?,
) {
    val outbound = direction == MessageDirection.OUTBOUND
    val sender = when {
        outbound -> SELF_LABEL
        !message.sender?.username.isNullOrBlank() -> message.sender!!.username
        !counterpartName.isNullOrBlank() -> counterpartName
        else -> UNKNOWN_SENDER
    }
    val timestamp = parseRavelryTimestamp(message.sentAt)
        ?.let { relativeTimeSince(it.toEpochMilliseconds()) }
        ?: UNKNOWN_TIME

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_WIDTH_FRACTION)
                .testTag(if (outbound) "MessageSent-${message.id}" else "MessageReceived-${message.id}"),
            color = if (outbound) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            // The squared-off corner points at its author's side of the screen.
            shape = if (outbound) {
                RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)
            } else {
                RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp)
            },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only the other party gets an avatar: a column of the user's own
                    // face down the sent side adds nothing the "You" label doesn't.
                    if (!outbound) {
                        UserAvatar(user = message.sender, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(6.dp))
                MessageBodyView(contentHtml = message.contentHtml)
            }
        }
    }
}

/**
 * A message body, or an honest placeholder when there isn't one.
 *
 * A missing body is ROUTINE rather than broken (the same contract the conversation list
 * works to): the list shape omits `content_html`, the backfill that fills it in can fail,
 * and either way the message's author, time and place in the conversation are still worth
 * showing. Saying so beats a blank bubble that reads as a rendering bug.
 */
@Composable
private fun MessageBodyView(contentHtml: String?) {
    if (contentHtml.isNullOrBlank()) {
        Text(
            text = "(no message body)",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Parsed once per body rather than on every recomposition — a long conversation is a
    // column of these and scrolling recomposes them.
    val document = remember(contentHtml) { HtmlPostParser.parse(contentHtml) }
    Box {
        // interactive = true (the default): unlike a feed card, a bubble is not itself a
        // tap target, so a link in a PM should behave like a link.
        PostBody(document = document)
    }
}
