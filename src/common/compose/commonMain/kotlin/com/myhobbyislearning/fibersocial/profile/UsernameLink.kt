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
