package com.myhobbyislearning.fibersocial.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.composeapp.resources.Res
import com.myhobbyislearning.fibersocial.composeapp.resources.app_logo_content_description
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The app's logo, name, and tagline — the branding block shared by `LaunchLoadingScreen`
 * and `LoginScreen`, which previously duplicated the strings ("FiberSocial", "A community
 * companion for Ravelry") and spacing separately (issue #268). Callers append whatever
 * comes next (a spinner, a login button, ...) directly below in their own `Column`.
 */
@Composable
fun AppBranding() {
    Image(
        painter = painterResource(appLogoResource()),
        contentDescription = stringResource(Res.string.app_logo_content_description),
        modifier = Modifier.size(120.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "FiberSocial", style = MaterialTheme.typography.displaySmall)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "A community companion for Ravelry",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
