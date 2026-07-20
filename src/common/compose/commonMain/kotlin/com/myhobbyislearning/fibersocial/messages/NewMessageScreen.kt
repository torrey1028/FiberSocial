// BackHandler is still experimental in Compose Multiplatform; opted in file-wide exactly as
// NewTopicScreen and MessageThreadScreen do — this screen has the same "system back must
// mirror the top-bar arrow" requirement (issue #38), with a discard confirmation behind both.
@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.models.UserSearchResult
import com.myhobbyislearning.fibersocial.ui.ErrorText
import com.myhobbyislearning.fibersocial.ui.SendingSpinner
import com.myhobbyislearning.fibersocial.ui.UserAvatar

/** Tallest the recipient result list grows before it scrolls inside itself. */
private val RESULTS_MAX_HEIGHT = 260.dp

/**
 * What turns this screen from "new message" into "reply", supplied by the caller when the
 * composer is opened from an open conversation (issue #374).
 *
 * Both fields are fixed for a reply and are shown read-only: the recipient is whoever the
 * conversation is with, and the subject is derived from the thread's ROOT subject by
 * [replySubject] so it cannot accrete `Re:` prefixes. Making either editable would be
 * offering a choice that Ravelry's `reply.json` ignores for the recipient and that only
 * corrupts the subject.
 *
 * @property counterpartName Who the reply goes to, for display.
 * @property subject The already-derived `Re: …` subject.
 */
data class ReplyContext(val counterpartName: String, val subject: String)

/**
 * Full-screen composer for a private message — a new conversation, or a reply (issue #374,
 * epic #365).
 *
 * ONE composer serves both, driven by [replyTo]. The reply case is not a separate inline bar
 * because everything that makes this screen careful is wanted there too: blank-field gating,
 * the discard confirmation, keep-the-text-on-failure, and above all the messaging-403 copy.
 * A second composer would be a second place for all of that to drift.
 *
 * ## The recipient is a COMMITTED selection, never free text
 *
 * The "To" field searches; it does not address. Until the user taps a result there is no
 * recipient and Send stays disabled, and the chosen person is then shown as a committed row
 * with the query field gone. Ravelry addresses `create.json` by username, and a username
 * that is merely *typed* fails server-side with an error that reads like a general delivery
 * failure — the user has no way to tell "no such person" from "Ravelry is down", and the
 * message they wrote is what is at stake.
 *
 * [lockedRecipient] is the same rule reached from the other direction (issue #373): opened
 * from someone's profile, the recipient is already decided by the navigation that got the
 * user here, so the picker is skipped entirely rather than pre-filled and editable. A
 * pre-filled but changeable "To" would re-open exactly the free-text hole the picker
 * closes — the user could type over an unambiguous, already-verified handle.
 *
 * ## Why a 403 shows copy naming BOTH causes rather than a probe
 *
 * A messaging 403 has two indistinguishable causes — the other party (or the user) has
 * Ravelry messaging disabled, or the user's token predates `message-write` joining `SCOPE`
 * and every write will 403 until they sign out and back in. `RavelryApiClient` documents a
 * heuristic that could tell them apart: cause (b) fails EVERY message-write call, so a
 * silent [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.archiveMessage] of some
 * message the user already has would succeed under (a) and fail under (b).
 *
 * That is deliberately NOT implemented, for three reasons:
 *
 *  1. It answers a diagnostic question by MUTATING the user's mailbox. Archiving files a
 *     message out of the inbox, so the probe needs an unarchive to undo it — and if that
 *     undo is the call that fails (offline, flaky), a message the user never touched has
 *     silently vanished from their inbox as a side effect of a failed send.
 *  2. It is unavailable exactly when it is most needed. A user with an empty or brand-new
 *     mailbox has nothing to archive, and a long-dormant account is precisely the profile
 *     whose token predates the scope change.
 *  3. The copy it would buy is barely narrower. The client's existing message names both
 *     causes and the fix for the actionable one, which is all a user can do either way —
 *     they cannot enable someone else's messaging setting.
 *
 * So the 403 surfaces as an ordinary composer error carrying that copy, the typed message is
 * kept, and — the part that matters most — it NEVER reads as a session expiry and never
 * triggers a sign-out (issue #82). [SendMessageState.Error.messagingBlocked] marks it so the
 * UI need not pattern-match the string.
 *
 * @param sendState State of the in-flight send.
 * @param searchState Recipient picker state. Ignored in reply mode.
 * @param onQueryChange Raw "To" text, on every keystroke; the ViewModel debounces.
 * @param onSend Invoked with the committed recipient (`null` in reply mode, where the
 *   endpoint derives it from the parent message), the subject, and the body.
 * @param onSent Invoked once the send is confirmed — navigate away and acknowledge.
 * @param onBack Leaves the composer. Reached only after the discard confirmation when there
 *   is something to lose.
 * @param replyTo Non-null makes this a reply. See [ReplyContext].
 * @param lockedRecipient Non-null addresses the message to this person and hides the picker
 *   (issue #373). Ignored in reply mode, where [replyTo] already fixes the recipient.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    sendState: SendMessageState,
    searchState: RecipientSearchState,
    onQueryChange: (String) -> Unit,
    onSend: (recipient: UserSearchResult?, subject: String, body: String) -> Unit,
    onSent: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    replyTo: ReplyContext? = null,
    lockedRecipient: UserSearchResult? = null,
) {
    // UserSearchResult is not Saveable, so the picked recipient survives recomposition but
    // not process death — the same trade NewTopicScreen makes for its Group, and for the
    // same reason: the typing effort (subject and body) is what survives, and re-picking a
    // recipient from a list is a tap.
    var pickedRecipient by remember { mutableStateOf<UserSearchResult?>(null) }
    // Who [onSend] is handed. A locked recipient wins over the picker — it isn't a default
    // the picker can be talked out of — but reply mode overrides BOTH with null, because
    // `reply.json` derives the recipient from the parent message and handing a caller one
    // anyway would invite it to route the reply through `create.json` and split the thread.
    val recipient = if (replyTo != null) null else lockedRecipient ?: pickedRecipient
    var query by rememberSaveable { mutableStateOf("") }
    var subjectDraft by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }

    val subject = replyTo?.subject ?: subjectDraft
    val sending = sendState is SendMessageState.Sending
    val canSend = !sending && subject.isNotBlank() && body.isNotBlank() &&
        (replyTo != null || recipient != null)
    // A reply has a fixed recipient and subject, so only the body can be "work in progress".
    // pickedRecipient, not recipient: a LOCKED recipient came from the navigation, not from
    // the user's effort, so it is nothing to warn about losing — leaving a locked composer
    // with empty fields should just leave, not ask.
    val hasDraft = body.isNotBlank() ||
        (replyTo == null && (subjectDraft.isNotBlank() || query.isNotBlank() || pickedRecipient != null))
    val attemptBack = { if (hasDraft) showDiscardConfirm = true else onBack() }

    // Disabled while sending, exactly as NewTopicScreen: leaving now unmounts this screen's
    // observer, so a Sent/Error landing afterwards would never navigate or surface.
    BackHandler(enabled = !sending, onBack = attemptBack)

    LaunchedEffect(sendState) { if (sendState is SendMessageState.Sent) onSent() }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(if (replyTo != null) "Discard this reply?" else "Discard this message?") },
            text = { Text("What you've written will be lost.") },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Keep editing") }
            },
            modifier = Modifier.testTag("MessageDiscardConfirm"),
        )
    }

    Scaffold(
        modifier = modifier.testTag("NewMessageScreen"),
        topBar = {
            TopAppBar(
                title = { Text(if (replyTo != null) "Reply" else "New message") },
                navigationIcon = {
                    IconButton(onClick = attemptBack, enabled = !sending) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sending) {
                        SendingSpinner()
                    } else {
                        TextButton(
                            onClick = { onSend(recipient, subject, body) },
                            enabled = canSend,
                            modifier = Modifier.testTag("SendMessageButton"),
                        ) { Text("Send") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (sendState is SendMessageState.Error) {
                ErrorText(
                    text = sendState.message,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("SendMessageError"),
                )
            }

            if (replyTo != null) {
                FixedRecipientRow(name = replyTo.counterpartName)
            } else if (lockedRecipient != null) {
                // The handle is shown alongside the display name whenever they differ, for
                // the same reason the picker's result rows show it: the handle is what is
                // actually addressed, and a display name can be anything.
                FixedRecipientRow(
                    name = lockedRecipient.displayName,
                    handle = lockedRecipient.username
                        .takeIf { !lockedRecipient.displayName.equals(it, ignoreCase = true) },
                )
            } else {
                RecipientPicker(
                    recipient = pickedRecipient,
                    query = query,
                    searchState = searchState,
                    enabled = !sending,
                    onQueryChange = { query = it; onQueryChange(it) },
                    onSelect = {
                        pickedRecipient = it
                        // Stop the picker searching for someone already chosen, and clear
                        // the half-typed name so backing out of the choice starts clean.
                        query = ""
                        onQueryChange("")
                    },
                    onClear = { pickedRecipient = null },
                )
            }

            OutlinedTextField(
                value = subject,
                onValueChange = { subjectDraft = it },
                label = { Text("Subject") },
                singleLine = true,
                // A reply's subject is derived, not chosen — shown so the user knows what it
                // will say, read-only so it cannot grow a second "Re:".
                readOnly = replyTo != null,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("MessageSubjectField"),
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                placeholder = { Text("Write your message…") },
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
                    .testTag("MessageBodyField"),
            )

            // Messages cannot be edited or unsent once delivered — Ravelry has no such
            // endpoint (see RavelryApiClient.sendMessage). Saying so before the tap is the
            // only warning we can give.
            Text(
                text = "Messages can't be edited after they're sent.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A fixed recipient: who the message goes to, with no way to change it. Used by both
 * modes that decide the recipient before the composer opens — a reply, and a message
 * started from a profile (issue #373).
 *
 * @param name What to call them; a display name where one is known.
 * @param handle The username actually addressed, shown only when it differs from [name].
 */
@Composable
private fun FixedRecipientRow(name: String, handle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("FixedRecipient"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "To:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (handle != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "@$handle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Search-and-commit recipient picker: a query field with results under it until someone is
 * chosen, then the chosen person in place of both.
 *
 * Swapping the field OUT once a selection is made is the point — leaving an editable "To"
 * box next to a committed choice invites the user to type over it and believe the typing is
 * what will be addressed. See [NewMessageScreen] on why the recipient is never free text.
 */
@Composable
private fun RecipientPicker(
    recipient: UserSearchResult?,
    query: String,
    searchState: RecipientSearchState,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSelect: (UserSearchResult) -> Unit,
    onClear: () -> Unit,
) {
    if (recipient != null) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("SelectedRecipient"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(
                    user = RavelryUser(username = recipient.username, avatarUrl = recipient.avatarUrl),
                    size = 28.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = recipient.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Choose someone else")
                }
            }
        }
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("To") },
        placeholder = { Text("Search for someone") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("RecipientQueryField"),
    )

    when (searchState) {
        RecipientSearchState.Idle -> Unit

        RecipientSearchState.Searching -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("RecipientSearching"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Searching…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is RecipientSearchState.Error -> ErrorText(
            text = searchState.message,
            modifier = Modifier.padding(vertical = 8.dp).testTag("RecipientSearchError"),
        )

        is RecipientSearchState.Results -> if (searchState.users.isEmpty()) {
            Text(
                text = "No one found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp).testTag("RecipientNoResults"),
            )
        } else {
            // Bounded height so the results scroll inside themselves rather than pushing the
            // subject and body fields off the bottom of a screen with the keyboard up.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = RESULTS_MAX_HEIGHT)
                    .testTag("RecipientResults"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(searchState.users, key = { it.username }) { user ->
                    RecipientRow(user = user, onClick = { onSelect(user) })
                }
            }
        }
    }
}

/** One search hit: avatar, display name, and the handle that will actually be addressed. */
@Composable
private fun RecipientRow(user: UserSearchResult, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("RecipientResult-${user.username}"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                user = RavelryUser(username = user.username, avatarUrl = user.avatarUrl),
                size = 32.dp,
            )
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Shown separately whenever it differs from the display name: the handle
                    // is what gets addressed, and a display name can be anything.
                    if (!user.displayName.equals(user.username, ignoreCase = true)) {
                        Text(
                            text = user.username,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
