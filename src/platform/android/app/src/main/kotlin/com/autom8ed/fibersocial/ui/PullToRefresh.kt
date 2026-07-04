package com.autom8ed.fibersocial.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Wraps [content] with standard Android pull-to-refresh behavior: dragging down from
 * the top of a scrollable child inside [content] invokes [onRefresh], and a compact
 * spinner appears above the content while [refreshing] is `true` — the content itself
 * stays visible and interactive the whole time, unlike a full-screen loading state.
 *
 * Built on the older `pullRefresh` modifier (`androidx.compose.material`, marked
 * [ExperimentalMaterialApi]) rather than Material3's `PullToRefreshBox`: this project
 * pins `compose-bom` to `2024.06.00`, which resolves Material3 to 1.2.1 — `PullToRefreshBox`
 * wasn't added until Material3 1.3. Centralizing the `@OptIn` here keeps it out of every
 * call site.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullRefreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)
    Box(modifier = modifier.pullRefresh(pullRefreshState)) {
        content()
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
