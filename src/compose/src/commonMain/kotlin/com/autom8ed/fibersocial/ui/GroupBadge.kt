package com.autom8ed.fibersocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.autom8ed.fibersocial.feed.models.Group

/**
 * Small rounded-square group icon for list rows (issue #167), from the group's Ravelry
 * badge image. Groups without one get a monogram tile (the name's first letter) rather
 * than an empty box. Decorative next to the visible group name, hence no content
 * description — same convention as [Avatar].
 */
@Composable
fun GroupBadge(group: Group, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    if (group.badgeUrl != null) {
        AsyncImage(
            model = group.badgeUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("GroupBadgeImage"),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .testTag("GroupBadgeMonogram"),
        ) {
            Text(
                text = monogramOf(group.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * First user-perceived character of [name], uppercased, for the monogram tile — or "#"
 * when the name is blank. Takes a whole Unicode codepoint, not a single UTF-16 char, so
 * an emoji-prefixed group name (common on Ravelry) renders the emoji rather than a lone
 * surrogate that draws as a tofu box.
 */
private fun monogramOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "#"
    // Multiplatform codepoint math (java.lang.Character isn't available on Native): a
    // leading surrogate pair travels as a two-char unit, anything else as one char.
    val length = if (trimmed.length >= 2 && trimmed[0].isHighSurrogate() && trimmed[1].isLowSurrogate()) 2 else 1
    return trimmed.take(length).uppercase()
}
