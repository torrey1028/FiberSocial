package com.myhobbyislearning.fibersocial.moderation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Confirmation dialog shown before blocking a user (issue #410 — Apple Guideline 1.2's
 * "block abusive users" mechanism), reached from a post's overflow menu
 * ([com.myhobbyislearning.fibersocial.feed.TopicDetailScreen]) and from the user profile
 * screen ([com.myhobbyislearning.fibersocial.profile.UserProfileScreen]).
 *
 * States clearly, per the plan, what blocking does: content is hidden immediately (not on
 * next refresh), it can be undone later in Settings, and — since Ravelry has no report/flag
 * pipeline aimed at the app developer specifically — an optional checkbox offers to also
 * notify the developer. Mirrors [com.myhobbyislearning.fibersocial.feed.ReportPostDialog]'s
 * escalate-checkbox shape: the checkbox's value is threaded through [onConfirm] rather than
 * firing a second callback, so the caller decides in one place whether to open the
 * "notify the developer" mailto draft after blocking.
 *
 * The developer notification, if requested, is never auto-sent: [onConfirm] is expected to
 * open a pre-addressed mailto URI (see `blockUserEmailUri` in `FeedScreen.kt`) that the user
 * still has to send themself, the same drafts-only pattern as
 * [com.myhobbyislearning.fibersocial.about.AboutScreen]'s child-safety report and #409's
 * "report to app developer" fallback.
 *
 * @param username The Ravelry handle being blocked (shown without a leading '@' by callers'
 *   convention — this dialog adds it).
 * @param onConfirm Invoked with whether "Also notify the developer" was checked; [onDismiss]
 *   is then also called.
 * @param onDismiss Invoked to close the dialog (cancel, scrim tap, or after confirm).
 */
@Composable
fun BlockUserConfirmDialog(
    username: String,
    onConfirm: (notifyDeveloper: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var notifyDeveloper by rememberSaveable(username) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block @$username?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Their posts and messages will be hidden from you immediately, " +
                        "everywhere in FiberSocial. You can unblock them anytime in " +
                        "Settings → Blocked users.",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { notifyDeveloper = !notifyDeveloper },
                ) {
                    Checkbox(checked = notifyDeveloper, onCheckedChange = { notifyDeveloper = it })
                    Text("Also notify the developer")
                }
            }
        },
        confirmButton = {
            // Tagged (rather than matched by its "Block" text alone) since a caller like
            // UserProfileScreen keeps its own "Block" action row in the semantics tree
            // behind this dialog, which would otherwise make the two ambiguous to a test —
            // the same trap SettingsScreen's ConfirmSignOut tag documents.
            TextButton(
                onClick = {
                    onConfirm(notifyDeveloper)
                    onDismiss()
                },
                modifier = Modifier.testTag("ConfirmBlockUser"),
            ) { Text("Block") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A "no entry" glyph (a circle with a diagonal bar) for the block action — this app only
 * ships `material-icons-core` (see `src/common/compose/build.gradle.kts`), which has no
 * block/no-entry icon (that's `material-icons-extended` only). Drawn directly with [Canvas]
 * rather than a hand-built [androidx.compose.ui.graphics.vector.ImageVector] path, unlike
 * `FeedScreen.kt`'s `FilterListIcon`/`SettingsScreen.kt`'s `DarkModeIcon` — a stroked circle
 * and a line are simple enough that translating Material's SVG path data buys nothing here.
 */
@Composable
internal fun BlockGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidth = size.minDimension * 0.09f
        val radius = size.minDimension / 2f - strokeWidth / 2f
        drawCircle(color = tint, radius = radius, style = Stroke(width = strokeWidth))
        // The diagonal bar's endpoints sit on the circle at 45°/225°, offset inward by
        // half the stroke width so the bar's own stroke doesn't overshoot the ring.
        val inset = strokeWidth / 2f
        val diag = (radius - inset) * 0.70710678f // cos(45°) == sin(45°)
        drawLine(
            color = tint,
            start = Offset(center.x - diag, center.y + diag),
            end = Offset(center.x + diag, center.y - diag),
            strokeWidth = strokeWidth,
        )
    }
}
