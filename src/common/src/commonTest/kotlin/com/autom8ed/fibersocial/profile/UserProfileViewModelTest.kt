package com.autom8ed.fibersocial.profile

import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.nullMessageApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import com.autom8ed.fibersocial.feed.suspendableRoutingApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserProfileViewModelTest {
    private suspend fun awaitChildren(job: Job) {
        do {
            job.children.toList().forEach { it.join() }
        } while (job.children.any { it.isActive })
    }

    private val membershipsHtml =
        """<a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>"""

    /** Routes profile, projects, and group-membership scrape + resolution by path. */
    private fun fullApiClient(): RavelryApiClient = routingApiClient { path ->
        when {
            path == "/people/yarnie.json" ->
                """{"user":{"id":9,"username":"yarnie","first_name":"Yarnie","location":"Seattle",
                    "about_me_html":"<p>I knit</p>","large_photo_url":"https://img/large.jpg"}}"""
            path.endsWith("/groups/memberships") -> membershipsHtml
            path.contains("/searched") || path.contains("/groups/search") || path.contains("search.json") ->
                """{"groups":[{"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42}]}"""
            path.endsWith("/list.json") ->
                """{"projects":[{"id":1,"name":"Autumn Socks","permalink":"autumn-socks",
                    "first_photo":{"id":901,"square_url":"https://img/sq.jpg"},"photos_count":2}]}"""
            else -> """{"groups":[{"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42}]}"""
        }
    }

    @Test
    fun `initial state is Hidden`() = runTest(UnconfinedTestDispatcher()) {
        assertIs<UserProfileState.Hidden>(UserProfileViewModel(fullApiClient(), this).state.value)
    }

    @Test
    fun `open loads the profile projects and groups`() = runTest(UnconfinedTestDispatcher()) {
        val vm = UserProfileViewModel(fullApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<UserProfileState.Loaded>(vm.state.value)
        assertEquals("yarnie", state.profile.username)
        assertEquals("Yarnie", state.profile.firstName)
        assertEquals("Seattle", state.profile.location)
        assertEquals("https://img/large.jpg", state.profile.avatarUrl)
        assertEquals(listOf(1L), state.projects.map { it.id })
        assertEquals(listOf("KAL Hub"), state.groups.map { it.name })
    }

    @Test
    fun `a projects or groups failure still shows the profile`() = runTest(UnconfinedTestDispatcher()) {
        // Only the profile endpoint succeeds; projects/groups error out.
        val vm = UserProfileViewModel(
            routingApiClient { path ->
                if (path == "/people/yarnie.json") {
                    """{"user":{"id":9,"username":"yarnie"}}"""
                } else {
                    error("secondary down")
                }
            },
            this,
        )
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<UserProfileState.Loaded>(vm.state.value)
        assertEquals(emptyList(), state.projects)
        assertEquals(emptyList(), state.groups)
    }

    @Test
    fun `profile load failure reports the error and retry refetches`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val vm = UserProfileViewModel(
            suspendableRoutingApiClient { url ->
                if (url.encodedPath == "/people/yarnie.json") {
                    calls++
                    if (calls == 1) error("boom") else """{"user":{"id":9,"username":"yarnie"}}"""
                } else {
                    error("secondary")
                }
            },
            this,
        )
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", assertIs<UserProfileState.Error>(vm.state.value).message)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<UserProfileState.Loaded>(vm.state.value)
    }

    @Test
    fun `profile failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = UserProfileViewModel(nullMessageApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Couldn't load the profile", assertIs<UserProfileState.Error>(vm.state.value).message)
    }

    @Test
    fun `retry outside an error state is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = UserProfileViewModel(errorApiClient(), this)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<UserProfileState.Hidden>(vm.state.value)
    }

    @Test
    fun `session expiry hides the page and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = UserProfileViewModel(sessionExpiredApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<UserProfileState.Hidden>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `a load that outlives its page is ignored after dismissal`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = UserProfileViewModel(
            suspendableRoutingApiClient { url ->
                gate.await()
                if (url.encodedPath == "/people/yarnie.json") """{"user":{"id":9,"username":"yarnie"}}""" else "{}"
            },
            this,
        )
        vm.open("yarnie")
        assertIs<UserProfileState.Loading>(vm.state.value)
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<UserProfileState.Hidden>(vm.state.value)
    }
}
