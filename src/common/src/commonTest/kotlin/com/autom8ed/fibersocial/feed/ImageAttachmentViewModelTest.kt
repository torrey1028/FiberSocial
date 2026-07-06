package com.autom8ed.fibersocial.feed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageAttachmentViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    /** Client that serves the full three-step upload flow, ending in [attachmentStatus]. */
    private fun uploadFlowApiClient(attachmentStatus: HttpStatusCode = HttpStatusCode.OK): RavelryApiClient {
        val engine = MockEngine { request ->
            val (content, status) = when (request.url.encodedPath) {
                "/upload/request_token.json" -> """{"upload_token":"tok"}""" to HttpStatusCode.OK
                "/upload/image.json" -> """{"uploads":{"file0":{"image_id":7}}}""" to HttpStatusCode.OK
                "/extras/create_attachment.json" -> """{"image_path":"/attached/yarnie/7.jpg"}""" to attachmentStatus
                else -> error("Unexpected request: ${request.url}")
            }
            respond(content, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    @Test
    fun `initial state is Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
        assertIs<ImageAttachmentState.Idle>(vm.state.value)
    }

    @Test
    fun `attach transitions to Ready with insertable markdown`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ImageAttachmentState.Ready>(vm.state.value)
        assertEquals("![](/attached/yarnie/7.jpg)", state.markdown)
    }

    @Test
    fun `a 403 from create_attachment reports the Extras requirement`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(HttpStatusCode.Forbidden), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ImageAttachmentState.Error>(vm.state.value)
        assertEquals(ImageAttachmentViewModel.EXTRAS_REQUIRED_MESSAGE, state.message)
    }

    @Test
    fun `an oversized image is rejected locally without a network call`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.attach("huge.jpg", "image/jpeg", ByteArray(ImageAttachmentViewModel.MAX_UPLOAD_BYTES + 1))
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ImageAttachmentState.Error>(vm.state.value)
        assertTrue("50 MB" in state.message, "unexpected message: ${state.message}")
    }

    @Test
    fun `attach failure reports the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ImageAttachmentState.Error>(vm.state.value)
    }

    @Test
    fun `session expiry resets to Idle and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(sessionExpiredApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ImageAttachmentState.Idle>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `acknowledgeInserted resets Ready back to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        vm.acknowledgeInserted()
        assertIs<ImageAttachmentState.Idle>(vm.state.value)
    }

    @Test
    fun `reset clears an Error but acknowledgeInserted does not`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        vm.acknowledgeInserted()
        assertIs<ImageAttachmentState.Error>(vm.state.value)
        vm.reset()
        assertIs<ImageAttachmentState.Idle>(vm.state.value)
    }

    @Test
    fun `reportUnreadable surfaces a read error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.reportUnreadable()
        assertIs<ImageAttachmentState.Error>(vm.state.value)
    }

    @Test
    fun `appendImageMarkdown starts a new paragraph and handles a blank draft`() {
        assertEquals("![](/a.jpg)", appendImageMarkdown("", "![](/a.jpg)"))
        assertEquals("![](/a.jpg)", appendImageMarkdown("   ", "![](/a.jpg)"))
        assertEquals("Hello\n\n![](/a.jpg)", appendImageMarkdown("Hello\n", "![](/a.jpg)"))
    }
}
