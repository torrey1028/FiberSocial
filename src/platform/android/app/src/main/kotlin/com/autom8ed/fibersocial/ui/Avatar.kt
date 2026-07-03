package com.autom8ed.fibersocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.autom8ed.fibersocial.feed.models.RavelryUser

/**
 * Circular user avatar. A null [url] (user has no avatar) renders the plain
 * surfaceVariant circle as the placeholder. Decorative next to a visible
 * username, hence no content description.
 */
@Composable
fun Avatar(url: String?, size: Dp) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * [Avatar] for a possibly-not-yet-loaded [user]: falls back to a generic
 * account icon when the user (or their avatar) is unknown, for surfaces like
 * the drawer footer and settings header that render before the feed loads.
 */
@Composable
fun UserAvatar(user: RavelryUser?, size: Dp, modifier: Modifier = Modifier) {
    val avatarUrl = user?.avatarUrl
    if (avatarUrl != null) {
        Avatar(url = avatarUrl, size = size)
    } else {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size),
        )
    }
}
