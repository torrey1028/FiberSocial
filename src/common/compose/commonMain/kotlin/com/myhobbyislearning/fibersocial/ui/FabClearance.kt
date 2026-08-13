package com.myhobbyislearning.fibersocial.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical gap Material 3's `Scaffold` leaves between a floating action button and the
 * bottom of its content area. Not exposed by the Compose API, so it is restated here.
 */
private val FAB_MARGIN = 16.dp

/**
 * Remembers the measured height of a floating action button, for lists that have to scroll
 * clear of one.
 *
 * ## Why a list needs this at all
 *
 * A Scaffold's FAB *floats*: it is not part of the content's layout and the `innerPadding`
 * the Scaffold hands its content accounts only for top and bottom bars, never the FAB. So a
 * `LazyColumn` filling that content area happily scrolls its last item to rest underneath
 * the FAB, where it is permanently unreachable — scrolling further does nothing, because
 * the list has already hit its end (issue #401).
 *
 * The fix is bottom `contentPadding` big enough to clear the FAB. Padding, specifically,
 * rather than a spacer item: it is part of the scrollable extent, so the last message can
 * scroll up past the FAB, and it costs nothing when the list is too short to scroll —
 * the FAB is sitting in that space anyway, so there is no visible dead gap.
 *
 * ## Measured, not assumed
 *
 * An extended FAB and a plain one are different heights, and either can grow with the
 * user's font scale — a hard-coded guess is wrong for somebody. [modifier] measures the
 * real composed button instead.
 *
 * Usage: apply [measured] to the FAB, then add [padding] to the list's bottom
 * `contentPadding`. Before the FAB has been measured — and whenever there is no FAB at
 * all, since nothing then calls [measured] — [padding] is `0.dp`, so a screen without one
 * reserves nothing.
 */
@Composable
fun rememberFabClearance(): FabClearance {
    val density = LocalDensity.current
    val height = remember { mutableStateOf(0.dp) }
    return remember(density) { FabClearance(height, density.density) }
}

/** The measured-FAB clearance produced by [rememberFabClearance]. */
class FabClearance internal constructor(
    private val height: MutableState<Dp>,
    private val density: Float,
) {
    /** Height of the measured FAB plus its Scaffold margin, or `0.dp` if none was measured. */
    val padding: Dp
        get() = height.value.takeIf { it > 0.dp }?.plus(FAB_MARGIN) ?: 0.dp

    private val measureModifier = Modifier.onSizeChanged { size ->
        height.value = (size.height / density).dp
    }

    /**
     * Apply to the floating action button whose height the list must clear.
     *
     * Composable, rather than a plain `Modifier` property, so the measurement can be undone
     * when the button goes away. `onSizeChanged` reports a size only while something is
     * being measured — it does NOT fire with zero when the node leaves composition — so a
     * bare modifier would leave the last measured height latched forever, and a screen that
     * dropped its FAB would keep reserving a button-sized strip of dead space at the bottom
     * of its list. That is exactly the case this helper claims to return `0.dp` for, and
     * exactly the "no dead gap when there is nothing to clear" behaviour issue #401 asked
     * for, so the reset is part of the contract rather than a nicety.
     *
     * Reachable through the API today even though no caller hits it: `MessageThreadScreen`
     * takes a nullable `onReply` and composes the FAB only when it is non-null, so a thread
     * opened with no reply action after one that had it would inherit the stale height.
     */
    @Composable
    fun measured(): Modifier {
        DisposableEffect(this) { onDispose { height.value = 0.dp } }
        return measureModifier
    }
}
