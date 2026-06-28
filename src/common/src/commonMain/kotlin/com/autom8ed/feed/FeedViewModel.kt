package com.autom8ed.feed

import com.autom8ed.feed.models.FeedItem
import com.autom8ed.feed.models.Group
import com.autom8ed.feed.models.RavelryUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class FeedState {
    object Loading : FeedState()
    data class Loaded(
        val user: RavelryUser,
        val groups: List<Group>,
        val selectedGroup: Group?,
        val items: List<FeedItem>,
    ) : FeedState()
    data class Refreshing(val stale: Loaded) : FeedState()
    data class Error(val message: String) : FeedState()
}

class FeedViewModel(
    private val repository: FeedRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            _state.value = FeedState.Loading
            _state.value = fetchFeed(selectedGroup = null)
        }
    }

    fun refresh() {
        val current = _state.value as? FeedState.Loaded ?: return
        scope.launch {
            _state.value = FeedState.Refreshing(current)
            _state.value = fetchFeed(selectedGroup = current.selectedGroup)
        }
    }

    fun selectGroup(group: Group?) {
        val current = _state.value as? FeedState.Loaded ?: return
        scope.launch {
            _state.value = FeedState.Refreshing(current)
            val groups = current.groups
            val filtered = if (group == null) groups else listOf(group)
            _state.value = try {
                FeedState.Loaded(
                    user = current.user,
                    groups = groups,
                    selectedGroup = group,
                    items = repository.getFeedItems(filtered),
                )
            } catch (e: Exception) {
                current // revert to previous state on error
            }
        }
    }

    private suspend fun fetchFeed(selectedGroup: Group?): FeedState = try {
        val user = repository.getCurrentUser()
        val groups = repository.getUserGroups(user.username)
        val filtered = if (selectedGroup == null) groups else listOf(selectedGroup)
        FeedState.Loaded(
            user = user,
            groups = groups,
            selectedGroup = selectedGroup,
            items = repository.getFeedItems(filtered),
        )
    } catch (e: Exception) {
        FeedState.Error(e.message ?: "Failed to load feed")
    }
}
