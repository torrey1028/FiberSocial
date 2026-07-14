@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.about

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "About FiberSocial" screen (issue #289), reachable from Settings. Carries the two
 * disclosures the Ravelry Application Developer and API License Agreement requires of
 * any app built on the API: a non-affiliation statement (Respect for Ravelry, clause e)
 * and a plain-language data-use disclosure (Respect for Ravelry's Users, clause a).
 *
 * The data-use copy is a description of what the app actually does, not a policy
 * decision — keep it in sync with [KeyValueTokenStorage][com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage]
 * (encrypted token/session storage), the plain [KeyValueStore][com.myhobbyislearning.fibersocial.storage.KeyValueStore]-backed
 * settings stores (theme, group order, notification settings), and
 * [NotificationState][com.myhobbyislearning.fibersocial.notifications.NotificationState] (the local
 * event/reminder record) if any of those change shape.
 *
 * @param onBack Return to Settings.
 * @param onOpenRepo Open the GitHub repo in the platform browser.
 * @param onOpenPrivacyPolicy Open the hosted privacy policy (legal/privacy-policy.html,
 *   published via GitHub Pages) in the platform browser.
 * @param onReportChildSafetyConcern Open a private, pre-addressed email to report a child
 *   safety concern. Deliberately separate from "Send feedback" (FeedbackScreen), which posts
 *   publicly to a Ravelry forum topic — wrong channel for something this sensitive. Satisfies
 *   Play Console's Child Safety Standards requirement for an in-app reporting path reachable
 *   without leaving the app; Google's own guidance explicitly allows a support email for this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onReportChildSafetyConcern: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About FiberSocial") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "FiberSocial is an independent, unofficial app for Ravelry. It is not " +
                    "created by, operated by, affiliated with, or endorsed by Ravelry — it's a " +
                    "third-party client built by an outside developer using Ravelry's public API " +
                    "and website. \"Ravelry\" belongs to its own owners.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            Text(
                text = "How FiberSocial uses your data",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "FiberSocial talks directly to Ravelry using your own Ravelry account — " +
                    "there is no FiberSocial server in between. Most requests use an OAuth token " +
                    "for Ravelry's API; a handful of actions Ravelry's API doesn't expose (like " +
                    "event pages) instead reuse your Ravelry website session, the same way your " +
                    "browser would.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "Your OAuth token and Ravelry session are stored on your device in " +
                    "encrypted storage (Android's EncryptedSharedPreferences, or the iOS " +
                    "Keychain) so you don't have to log in every time.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "App preferences — theme, group order, and notification settings — are " +
                    "kept in ordinary on-device settings storage.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "To detect new events and remind you about the ones you're attending, " +
                    "the app keeps a small local record of event permalinks and reminder times " +
                    "on your device. It's used only to avoid re-notifying you and to schedule " +
                    "reminders, and never leaves your device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "Nothing you do in FiberSocial is sent to the developer or any third " +
                    "party. The app has no analytics or tracking.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            TextButton(onClick = onOpenPrivacyPolicy, contentPadding = PaddingValues(0.dp)) {
                Text("Privacy Policy")
            }

            TextButton(onClick = onReportChildSafetyConcern, contentPadding = PaddingValues(0.dp)) {
                Text("Report a child safety concern")
            }

            TextButton(onClick = onOpenRepo, contentPadding = PaddingValues(0.dp)) {
                Text("View source on GitHub")
            }
        }
    }
}
