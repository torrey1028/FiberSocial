package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
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
            println("FiberSocial: FeedViewModel.load() starting")
            _state.value = FeedState.Loading
            _state.value = fetchFeed(selectedGroup = null)
            println("FiberSocial: FeedViewModel.load() -> ${_state.value::class.simpleName}")
        }
    }

    fun refresh() {
        val current = _state.value as? FeedState.Loaded ?: return
        scope.launch {
            println("FiberSocial: FeedViewModel.refresh()")
            _state.value = FeedState.Refreshing(current)
            _state.value = fetchFeed(selectedGroup = current.selectedGroup)
            println("FiberSocial: FeedViewModel.refresh() -> ${_state.value::class.simpleName}")
        }
    }

    fun selectGroup(group: Group?) {
        val current = _state.value as? FeedState.Loaded ?: return
        println("FiberSocial: FeedViewModel.selectGroup(${group?.name ?: "All Groups"})")
        scope.launch {
            _state.value = FeedState.Refreshing(current)
            val groups = current.groups
            val filtered = if (group == null) groups else listOf(group)
            _state.value = try {
                val items = repository.getFeedItems(filtered)
                println("FiberSocial: selectGroup loaded ${items.size} items")
                FeedState.Loaded(
                    user = current.user,
                    groups = groups,
                    selectedGroup = group,
                    items = items,
                )
            } catch (e: Exception) {
                println("FiberSocial: selectGroup error: ${e.message}")
                current
            }
        }
    }

    private suspend fun fetchFeed(selectedGroup: Group?): FeedState = try {
        val user = repository.getCurrentUser()
        println("FiberSocial: fetched user=${user.username}")
        val groups = repository.getUserGroups(user.username)
        println("FiberSocial: fetched ${groups.size} groups: ${groups.map { it.name }}")
        val filtered = if (selectedGroup == null) groups else listOf(selectedGroup)
        val items = repository.getFeedItems(filtered)
        println("FiberSocial: fetched ${items.size} feed items")
        FeedState.Loaded(
            user = user,
            groups = groups,
            selectedGroup = selectedGroup,
            items = items,
        )
    } catch (e: Exception) {
        println("FiberSocial: fetchFeed error: ${e.message}")
        FeedState.Error(e.message ?: "Failed to load feed")
    }
}
