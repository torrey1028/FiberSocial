package com.myhobbyislearning.fibersocial.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.ui.ErrorText
import com.myhobbyislearning.fibersocial.ui.SendingSpinner

/**
 * Ravelry's own public "report a violation of the community guidelines" contact form —
 * reachable signed out, and the only report route to Ravelry that doesn't depend on this
 * app's scraping of their login-walled flag form (issue #467).
 */
private const val RAVELRY_REPORT_VIOLATION_URL =
    "https://www.ravelry.com/contact?question=i_want_to_report_a_violation_of_the_ravelry_community_guidelines"

/** Label of the optional free-text box, when Ravelry's form offers one. */
internal const val COMMENT_LABEL = "Anything the moderators should know? (optional)"

/**
 * "Report post" dialog (issue #409 — Apple Guideline 1.2's "flag objectionable content"
 * mechanism). Driven by [ReportState]: shows a spinner while [TopicDetailViewModel.openReportDialog]
 * fetches Ravelry's flag form, then the form's own reason options (plus an
 * "Escalate to Ravelry staff" toggle when the form offers one) once loaded.
 *
 * The "Report to app developer instead" fallback is always offered — while loading,
 * once ready, and if the flag form failed to load — per the plan's fallback tiers: the
 * in-app Ravelry flag is the primary channel, but the pre-addressed developer email
 * (issue #409's secondary channel, mirroring [com.myhobbyislearning.fibersocial.about.AboutScreen]'s
 * child-safety report) always reaches someone even if Ravelry's own form can't be
 * reached. A load failure additionally offers Ravelry's own public
 * report-a-guidelines-violation contact form in the browser (the plan's middle tier),
 * so a broken scrape can never leave the user with no route to Ravelry itself — issue
 * #467 was exactly that dead end.
 *
 * Renders nothing for [ReportState.Idle]/[ReportState.Sent] — the caller shows a
 * separate one-shot confirmation for [ReportState.Sent] (see [TopicDetailScreen]).
 */
@Composable
internal fun ReportPostDialog(
    state: ReportState,
    onSubmit: (reasonId: String, escalate: Boolean, comment: String) -> Unit,
    onReportToDeveloper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    if (state !is ReportState.LoadingForm && state !is ReportState.Ready && state !is ReportState.LoadError) {
        return
    }
    val ready = state as? ReportState.Ready
    // Keyed by post ID (not the whole state) so a rejected submission's fresh Ready
    // (same post, same form) keeps the user's picks instead of resetting them, while
    // opening the dialog for a DIFFERENT post starts from a clean slate.
    var selectedReasonId by rememberSaveable(ready?.post?.id ?: -1L) {
        mutableStateOf(ready?.form?.initialReasonId)
    }
    var escalate by rememberSaveable(ready?.post?.id ?: -1L) { mutableStateOf(false) }
    var comment by rememberSaveable(ready?.post?.id ?: -1L) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report post") },
        text = {
            // The reason list, an optional comment box, the escalate toggle and the
            // fallback links can together outgrow a short screen — without this the
            // fallbacks (the whole point of the LoadError state) get clipped away.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    is ReportState.LoadingForm -> Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    is ReportState.Ready -> {
                        // Only claim the report goes to the group's moderators when the
                        // form doesn't say otherwise itself: Ravelry's real form splits
                        // its reasons into "Report to group moderators" and "Escalate to
                        // Ravelry staff" sections, and those headings are the only thing
                        // telling the user which one they picked.
                        if (!state.form.hasGroupedReasons) {
                            Text(
                                text = "This is sent privately to the group's moderators.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        var lastGroup: String? = null
                        state.form.reasons.forEach { reason ->
                            val group = reason.group
                            if (group != null && group != lastGroup) {
                                Text(text = group, style = MaterialTheme.typography.titleSmall)
                            }
                            lastGroup = group
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.submitting) { selectedReasonId = reason.id },
                            ) {
                                RadioButton(
                                    selected = selectedReasonId == reason.id,
                                    onClick = { selectedReasonId = reason.id },
                                    enabled = !state.submitting,
                                )
                                Text(reason.label)
                            }
                        }
                        if (state.form.supportsComment) {
                            OutlinedTextField(
                                value = comment,
                                onValueChange = { comment = it },
                                label = { Text(COMMENT_LABEL) },
                                enabled = !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (state.form.supportsEscalate) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.submitting) { escalate = !escalate },
                            ) {
                                Checkbox(
                                    checked = escalate,
                                    onCheckedChange = { escalate = it },
                                    enabled = !state.submitting,
                                )
                                Text("Escalate to Ravelry staff")
                            }
                        }
                        state.error?.let { ErrorText(text = it) }
                    }

                    is ReportState.LoadError -> {
                        ErrorText(text = state.message.ifBlank { "Couldn't load the report form." })
                        TextButton(
                            onClick = {
                                runCatching { uriHandler.openUri(RAVELRY_REPORT_VIOLATION_URL) }
                                    .onFailure { println("FiberSocial: openUri failed: ${it.message}") }
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Report to Ravelry on the web")
                        }
                    }

                    else -> Unit
                }
                HorizontalDivider()
                TextButton(onClick = onReportToDeveloper, contentPadding = PaddingValues(0.dp)) {
                    Text("Report to app developer instead")
                }
            }
        },
        confirmButton = {
            if (state is ReportState.Ready) {
                if (state.submitting) {
                    SendingSpinner()
                } else {
                    TextButton(
                        onClick = { selectedReasonId?.let { onSubmit(it, escalate, comment) } },
                        enabled = selectedReasonId != null,
                    ) { Text("Report") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** One-shot confirmation shown after a successful report (issue #409). */
@Composable
internal fun ReportSentDialog(onDismiss: () -> Unit) {
    OneShotDialog(
        title = "Report sent",
        message = "Thanks — this post has been reported to the group's moderators.",
        onDismiss = onDismiss,
    )
}

/**
 * One-shot acknowledgement modal — a title, a message, and a single OK button that
 * dismisses it. Shared by [ReportSentDialog] and [TopicDetailScreen]'s post-action
 * error dialog so the acknowledge-only shape stays consistent.
 */
@Composable
internal fun OneShotDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
