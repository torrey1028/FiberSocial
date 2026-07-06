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
import kotlinx.coroutines.CompletableDeferred
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
    fun `attach failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(nullMessageApiClient(), this)
        vm.attach("photo.jpg", "image/jpeg", byteArrayOf(1))
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Failed to upload the image", assertIs<ImageAttachmentState.Error>(vm.state.value).message)
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
    fun `an unreadable image - null from the loader - surfaces a read error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.attach { null }
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ImageAttachmentState.Error>(vm.state.value)
        assertTrue("read" in state.message, "unexpected message: ${state.message}")
    }

    @Test
    fun `state is Uploading while the image is still being loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
        val gate = CompletableDeferred<UploadableImage?>()
        vm.attach { gate.await() }
        // The spinner must show (and double-picks be ignored) from the tap, not only
        // once the byte read finishes.
        assertIs<ImageAttachmentState.Uploading>(vm.state.value)
        gate.complete(UploadableImage("photo.jpg", "image/jpeg", byteArrayOf(1)))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ImageAttachmentState.Ready>(vm.state.value)
    }

    @Test
    fun `reset cancels an in-flight upload so its result cannot land in a later draft`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
            val gate = CompletableDeferred<UploadableImage?>()
            vm.attach { gate.await() }
            assertIs<ImageAttachmentState.Uploading>(vm.state.value)
            vm.reset()
            assertIs<ImageAttachmentState.Idle>(vm.state.value)
            // Even if the load completes afterwards, the cancelled upload must not
            // deliver a Ready state.
            gate.complete(UploadableImage("photo.jpg", "image/jpeg", byteArrayOf(1)))
            awaitChildren(coroutineContext[Job]!!)
            assertIs<ImageAttachmentState.Idle>(vm.state.value)
        }

    @Test
    fun `insertExisting hands the composer ready markdown without a network call`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(errorApiClient(), this)
        vm.insertExisting("[![Socks](https://img.example/m.jpg)](https://www.ravelry.com/projects/yarnie/socks)")
        val state = assertIs<ImageAttachmentState.Ready>(vm.state.value)
        assertEquals("[![Socks](https://img.example/m.jpg)](https://www.ravelry.com/projects/yarnie/socks)", state.markdown)
    }

    @Test
    fun `insertExisting and a second attach are ignored while an upload is in flight`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ImageAttachmentViewModel(uploadFlowApiClient(), this)
        val gate = CompletableDeferred<UploadableImage?>()
        vm.attach { gate.await() }
        assertIs<ImageAttachmentState.Uploading>(vm.state.value)
        // A project-photo pick mid-upload must not clobber the in-flight result...
        vm.insertExisting("![](/elsewhere.jpg)")
        assertIs<ImageAttachmentState.Uploading>(vm.state.value)
        // ...and neither may a second device pick double-submit.
        vm.attach("other.jpg", "image/jpeg", byteArrayOf(2))
        assertIs<ImageAttachmentState.Uploading>(vm.state.value)
        gate.complete(UploadableImage("photo.jpg", "image/jpeg", byteArrayOf(1)))
        awaitChildren(coroutineContext[Job]!!)
        // The in-flight upload's own result wins.
        assertEquals("![](/attached/yarnie/7.jpg)", assertIs<ImageAttachmentState.Ready>(vm.state.value).markdown)
    }

    @Test
    fun `appendImageMarkdown starts a new paragraph and handles a blank draft`() {
        assertEquals("![](/a.jpg)", appendImageMarkdown("", "![](/a.jpg)"))
        assertEquals("![](/a.jpg)", appendImageMarkdown("   ", "![](/a.jpg)"))
        assertEquals("Hello\n\n![](/a.jpg)", appendImageMarkdown("Hello\n", "![](/a.jpg)"))
    }
}
