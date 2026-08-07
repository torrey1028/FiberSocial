package com.myhobbyislearning.fibersocial.profile

import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Handler for a tapped username (issue #194). When set, [UsernameLink] opens the user's
 * in-app profile through it; when null (previews, tests, or surfaces that don't provide
 * one) the username renders as plain, non-interactive text.
 */
val LocalProfileOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * A `@username` rendered as a tappable link to the user's profile when an opener is
 * available, and as plain text otherwise. [username] is the bare handle (no `@`).
 */
@Composable
fun UsernameLink(
    username: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val opener = LocalProfileOpener.current
    val clickable = if (opener != null) Modifier.clickable { opener(username) } else Modifier
    Text(text = "@$username", modifier = modifier.then(clickable), style = style)
}

/**
 * Opens [username]'s profile when this composable is tapped, through the same
 * [LocalProfileOpener] [UsernameLink] uses (issue #400).
 *
 * ## Why a modifier rather than reusing [UsernameLink]
 *
 * [UsernameLink] owns its own `Text`, and renders the handle in `@name` form. The messages
 * surfaces need neither: a conversation row's counterpart is a styled title (weight varies
 * with unread, ellipsized into a weighted slot) and a message's attribution is a plain
 * name, and prefixing either with `@` would change how those screens read. Making the
 * link a modifier lets each call site keep the exact `Text` — or `UserAvatar` — it already
 * had and gain only the tap.
 *
 * ## Non-links are the default, deliberately
 *
 * A blank or null [username] yields an inert modifier, so the placeholders never become
 * links to nothing: a thread with no counterpart renders `(unknown)`, and a message the
 * signed-in user sent is attributed to `You` — neither names a profile that could be
 * opened. Callers pass `null` for those rather than having to remember to branch. Same
 * when no [LocalProfileOpener] is provided (previews, tests, hosts without profile
 * navigation), matching [UsernameLink]'s existing contract.
 *
 * The click carries an [onClickLabel] so a screen reader announces what tapping does,
 * which matters more here than for [UsernameLink]: nothing about the rendered text says
 * "link" once the `@` is gone.
 */
@Composable
fun Modifier.profileClickable(username: String?): Modifier {
    val opener = LocalProfileOpener.current
    if (opener == null || username.isNullOrBlank()) return this
    return this.clickable(onClickLabel = "Open profile") { opener(username) }
}
