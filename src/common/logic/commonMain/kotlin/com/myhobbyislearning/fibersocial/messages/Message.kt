package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which Ravelry message box to list (issue #366, epic #365).
 *
 * NAMING TRAP — Ravelry contradicts itself and the WIRE value is what matters. The
 * `folder` parameter on `/messages/list.json` accepts `inbox`, `sent`, `archived`, but
 * the prose for `/messages/{id}/archive.json` says it "moves a message from 'inbox' to
 * the 'saved' box", and the website labels that box "saved". They are the same box.
 * Send [wireName]; only use the enum name for our own code.
 */
enum class MessageFolder(val wireName: String) {
    /** Messages received by the signed-in user and not archived. */
    INBOX("inbox"),

    /** Messages the signed-in user sent. */
    SENT("sent"),

    /** Archived messages — the box Ravelry's own prose calls "saved". */
    ARCHIVED("archived"),
}

/**
 * A single Ravelry private message, from `/messages/list.json` and `/messages/{id}.json`.
 *
 * Ravelry serves two shapes behind one type: the `list` shape (everything except the
 * body) and the `full` shape (adds [contentHtml] and [folderName]). Both decode into
 * this class — the full-only fields are nullable and default to `null`, so a list entry
 * simply has no body. Callers that need a body must either request the full output
 * format on the list call or fetch the single message.
 *
 * PMs are FLAT, not threaded: there is no conversation object anywhere in Ravelry's API.
 * [parentMessageId] is the only link between a reply and what it replies to, which is
 * why conversation grouping is reconstructed client-side (issue #368).
 *
 * @property id Ravelry message ID; the path segment for show/mark-read/archive/delete.
 * @property subject Message subject line. Replies repeat the parent's subject.
 * @property sender Who sent it. Reusing [RavelryUser] rather than a new message-only
 *   user type: Ravelry embeds its `User (small)` shape here — the same shape already
 *   embedded on `Post`/`Topic` — and reuse means the existing `UserAvatar` composable
 *   renders a message correspondent with no adapter.
 * @property recipient Who received it. In [MessageFolder.SENT] this is the other party;
 *   in [MessageFolder.INBOX] it is the signed-in user.
 * @property sentAt Ravelry API timestamp (`"yyyy/MM/dd HH:mm:ss Z"`), same format as
 *   `Post.createdAt`, so the existing relative-time formatting applies unchanged.
 * @property readMessage Whether the message has been read. Drives the unread dot and is
 *   what [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.markMessageRead] flips.
 * @property replied Whether the recipient has replied to this message.
 * @property repliedAt Timestamp of that reply, or `null` if none.
 * @property parentMessageId The message this one replies to, or `null` if it starts a
 *   conversation.
 * @property messageTypeName Ravelry's own classification of the message (e.g. a normal
 *   user-to-user PM vs a system notice). Nullable — treat an unknown value as ordinary.
 * @property folderName Which box the message currently lives in. Full shape only.
 * @property contentHtml Server-rendered HTML body. Full shape only.
 * @property content Plain-text body — see the note below. Full shape only, and possibly
 *   never present at all.
 */
@Serializable
data class Message(
    val id: Long,
    val subject: String = "",
    val sender: RavelryUser? = null,
    val recipient: RavelryUser? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("read_message") val readMessage: Boolean = false,
    val replied: Boolean = false,
    @SerialName("replied_at") val repliedAt: String? = null,
    @SerialName("parent_message_id") val parentMessageId: Long? = null,
    @SerialName("message_type_name") val messageTypeName: String? = null,
    @SerialName("folder_name") val folderName: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    /**
     * UNRESOLVED DOC AMBIGUITY (#366) — does a read carry plain text alongside HTML?
     *
     * Ravelry's own field table for `Message (full)` lists ONLY `content_html`; `content`
     * appears exclusively on `Message (POST)`, i.e. it looks write-only. But third-party
     * clients refer to a `content` field on reads, and the docs are demonstrably sloppy
     * elsewhere in this same section (see [MessageFolder] and the `output_format`
     * comment on `getMessages`). We had no live token to settle it, so this field is
     * nullable and optional: the model decodes identically whether the key is present,
     * absent, or explicitly null, and nothing here commits to an answer.
     *
     * TO SETTLE IT: one authenticated `GET /messages/{id}.json` against a real account —
     * if the returned JSON has a `content` key, plain text exists on reads and the
     * detail screen may prefer it; if not, bodies MUST render through the HTML path
     * ([contentHtml]) and this field should be deleted rather than left as a trap.
     * Until then, treat [contentHtml] as the only body you can rely on.
     */
    val content: String? = null,
)
