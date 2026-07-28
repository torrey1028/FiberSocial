package com.myhobbyislearning.fibersocial.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Pre-login terms-of-use gate (issue #408, Apple Guideline 1.2). Shown instead of
 * [com.myhobbyislearning.fibersocial.login.LoginScreen] whenever the signed-out user hasn't
 * agreed to the current terms version — see [com.myhobbyislearning.fibersocial.settings.TermsAcceptance] —
 * so "Log in with Ravelry" is unreachable until they agree here. Never shown to an
 * already-authenticated user, and never reappears for the same version once agreed.
 *
 * Same background trap as `LoginScreen`: `FiberSocialTheme` sets colors but no background,
 * and this renders before the feed's `Scaffold`, so it needs its own themed [Surface].
 *
 * @param onOpenFullTerms Open the hosted `legal/terms-of-use.html` in the platform browser.
 * @param onAgree Persist acceptance of the current terms version and reveal the login screen.
 */
@Composable
fun TermsGateScreen(onOpenFullTerms: () -> Unit, onAgree: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Before you continue",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = "FiberSocial has no tolerance for objectionable content or abusive " +
                    "users. Every post can be reported, and every user can be blocked — " +
                    "blocking removes their content from your feed instantly.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = "Everything you see in FiberSocial is content from your own Ravelry " +
                    "account, hosted and moderated by Ravelry under its own Terms of Service " +
                    "and Community Guidelines, which you must also follow.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = onOpenFullTerms,
            ) {
                Text("Read the full Terms of Use")
            }
            Button(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(),
                onClick = onAgree,
            ) {
                Text("Agree and continue")
            }
        }
    }
}
