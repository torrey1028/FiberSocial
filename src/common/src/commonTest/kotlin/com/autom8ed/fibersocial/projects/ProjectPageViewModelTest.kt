package com.autom8ed.fibersocial.projects

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
class ProjectPageViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    private val link = ProjectLink("yarnie", "autumn-socks")

    private fun projectApiClient(): RavelryApiClient = routingApiClient {
        """{"project":{"id":7,"name":"Autumn Socks","permalink":"autumn-socks",
            "pattern_name":"Vanilla Socks","status_name":"Finished","progress":100,
            "notes":"So *cozy*","notes_html":"<p>So <em>cozy</em></p>",
            "tag_names":["socks"],
            "photos":[{"id":901,"medium_url":"https://img.example/m1.jpg"}]}}"""
    }

    @Test
    fun `initial state is Hidden`() = runTest(UnconfinedTestDispatcher()) {
        assertIs<ProjectPageState.Hidden>(ProjectPageViewModel(projectApiClient(), this).state.value)
    }

    @Test
    fun `open loads the project detail`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(projectApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertEquals(link, state.link)
        assertEquals("Autumn Socks", state.project.name)
        assertEquals("Vanilla Socks", state.project.patternName)
        assertEquals("Finished", state.project.statusName)
        assertEquals(100, state.project.progress)
        assertEquals("So *cozy*", state.project.notes)
        assertEquals(listOf("socks"), state.project.tagNames)
        assertEquals(listOf(901L), state.project.photos.map { it.id })
    }

    @Test
    fun `load failure reports the error and retry refetches`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val client = suspendableRoutingApiClient { url ->
            calls++
            if (calls == 1) error("boom")
            """{"project":{"id":7,"name":"Autumn Socks"}}"""
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", assertIs<ProjectPageState.Error>(vm.state.value).message)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Loaded>(vm.state.value)
    }

    @Test
    fun `load failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(nullMessageApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Couldn't load the project", assertIs<ProjectPageState.Error>(vm.state.value).message)
    }

    @Test
    fun `retry outside an error state is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(errorApiClient(), this)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }

    @Test
    fun `session expiry hides the page and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(sessionExpiredApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `a load that outlives its page is ignored after dismissal`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            gate.await()
            """{"project":{"id":7,"name":"Autumn Socks"}}"""
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)
        assertIs<ProjectPageState.Loading>(vm.state.value)
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        // The page must not pop back open after the user closed it.
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }
}
