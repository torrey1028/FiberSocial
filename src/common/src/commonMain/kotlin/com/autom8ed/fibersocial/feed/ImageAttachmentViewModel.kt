package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.ForbiddenException
import com.autom8ed.fibersocial.auth.SessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * State of an in-flight image attachment upload for a composer (new topic or reply).
 */
sealed class ImageAttachmentState {
    /** No upload in flight. */
    object Idle : ImageAttachmentState()

    /** The image is being uploaded and converted into an attachment. */
    object Uploading : ImageAttachmentState()

    /**
     * The attachment is hosted on Ravelry; the composer should insert [markdown] into its
     * draft and acknowledge via [ImageAttachmentViewModel.acknowledgeInserted].
     * @property markdown Ready-to-insert markdown image reference, e.g. `![](/attached/...)`.
     */
    data class Ready(val markdown: String) : ImageAttachmentState()

    /**
     * The upload failed; the composer keeps its draft and may retry.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : ImageAttachmentState()
}

/**
 * Drives one composer's attach-image flow: uploads picked image bytes via
 * [RavelryApiClient.uploadForumImage] and exposes the resulting markdown for the
 * composer to insert into its draft.
 *
 * Each composer (new topic, reply) gets its own instance so an upload for one can
 * never deliver its markdown into the other's draft. The host screen calls [reset]
 * when the composer opens or closes so a result that lands after navigating away
 * isn't inserted into a later draft.
 *
 * @param apiClient Used to upload the image and create the attachment.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class ImageAttachmentViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImageAttachmentState>(ImageAttachmentState.Idle)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable state of the current attachment upload. */
    val state: StateFlow<ImageAttachmentState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Uploads [bytes] as an image attachment. Double-submits are ignored while an upload
     * is in flight. Oversized images are rejected locally — Ravelry caps an upload POST
     * at 50 MB and would answer 413 anyway. A missing Extras subscription (the server's
     * 403 on `extras/create_attachment`) surfaces as a self-explanatory [ImageAttachmentState.Error].
     */
    fun attach(fileName: String, contentType: String, bytes: ByteArray) {
        if (_state.value is ImageAttachmentState.Uploading) return
        if (bytes.size > MAX_UPLOAD_BYTES) {
            _state.value = ImageAttachmentState.Error("That image is larger than Ravelry's 50 MB upload limit.")
            return
        }
        _state.value = ImageAttachmentState.Uploading
        scope.launch {
            try {
                val imagePath = apiClient.uploadForumImage(fileName, contentType, bytes)
                println("FiberSocial: ImageAttachmentViewModel uploaded $fileName -> $imagePath")
                _state.value = ImageAttachmentState.Ready("![]($imagePath)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ImageAttachmentViewModel.attach session expired")
                _state.value = ImageAttachmentState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: ForbiddenException) {
                // Attachment hosting is a Ravelry Extras feature; the API answers 403
                // for accounts without the subscription.
                println("FiberSocial: ImageAttachmentViewModel.attach forbidden: ${e.message}")
                _state.value = ImageAttachmentState.Error(EXTRAS_REQUIRED_MESSAGE)
            } catch (e: Exception) {
                println("FiberSocial: ImageAttachmentViewModel.attach error: ${e.message}")
                _state.value = ImageAttachmentState.Error(e.message ?: "Failed to upload the image")
            }
        }
    }

    /** Reports a picked image that couldn't be read from the device (no upload attempted). */
    fun reportUnreadable() {
        if (_state.value !is ImageAttachmentState.Uploading) {
            _state.value = ImageAttachmentState.Error("Couldn't read that image from your device.")
        }
    }

    /** Resets [state] from [ImageAttachmentState.Ready] back to Idle once the markdown is in the draft. */
    fun acknowledgeInserted() {
        if (_state.value is ImageAttachmentState.Ready) _state.value = ImageAttachmentState.Idle
    }

    /**
     * Clears a stale [ImageAttachmentState.Ready] or [ImageAttachmentState.Error] when the
     * composer opens or closes. No-op mid-upload: the in-flight result still needs to land
     * somewhere (and [attach] ignores submits while Uploading).
     */
    fun reset() {
        if (_state.value !is ImageAttachmentState.Uploading) _state.value = ImageAttachmentState.Idle
    }

    companion object {
        /** Ravelry's documented cap on a single upload POST. */
        const val MAX_UPLOAD_BYTES = 50 * 1024 * 1024

        /** Shown when `extras/create_attachment` answers 403 (no Extras subscription). */
        const val EXTRAS_REQUIRED_MESSAGE =
            "Uploading images to posts requires a Ravelry Extras subscription on your Ravelry account."
    }
}

/**
 * Appends an image's markdown to a draft body: on its own paragraph, so the image
 * renders as a block instead of gluing onto the last line of text.
 */
fun appendImageMarkdown(body: String, imageMarkdown: String): String =
    if (body.isBlank()) imageMarkdown else body.trimEnd() + "\n\n" + imageMarkdown
