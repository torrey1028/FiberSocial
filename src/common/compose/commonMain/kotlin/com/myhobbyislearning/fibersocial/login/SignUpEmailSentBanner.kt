package com.myhobbyislearning.fibersocial.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Shown over the login web view once Ravelry has emailed a signup link.
 *
 * The in-app sign-up flow necessarily ends here: Ravelry finishes account creation
 * through a link it emails, which opens in whatever mail app and browser the user has.
 * Leaving them on Ravelry's own "check your email" page parked them on a dead end inside
 * a screen meant for signing in — nothing on that page could move them forward, and the
 * only way out was backing out of login entirely.
 *
 * So the web view resets to a fresh login and this says why. The copy has to carry the
 * whole handover, because the page that explained it is no longer on screen: that the
 * email is coming, that the account is finished elsewhere, and that they should come back
 * *here* to sign in once it is.
 *
 * Shared between the Android and iOS login screens rather than written twice — the wording
 * is the load-bearing part, and two copies drift.
 */
@Composable
fun SignUpEmailSentBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("SignUpEmailSentBanner"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Email,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Check your email",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Ravelry is sending you a link to finish creating your account. " +
                        "Once it's done, come back here and sign in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}
