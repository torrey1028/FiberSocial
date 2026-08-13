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
 * @property messageTypeName Ravelry's own classification of the message. An OPEN-ENDED
 *   string, not a two-value normal-vs-system flag. A live account (2026-08-06) returned
 *   seven distinct inbox values — `simple_message`, `link_message`,
 *   `gift_certificate_message`, `comment_message`, `friend_message`, `group_invitation`,
 *   `photo_request` — and, in the SENT folder, `sent_message` for every single message
 *   regardless of what it would have been called in the recipient's inbox. So this field
 *   describes the row you are looking at, NOT an intrinsic property of the message, and
 *   it cannot be used to pair a sent message with its inbox counterpart.
 *
 *   Nullable, and nothing branches on it. Treat an unrecognised value as ordinary rather
 *   than enumerating: the set is Ravelry's to extend, and a `when` over these would
 *   silently mishandle whatever they add next.
 * @property folderName Which box the message currently lives in. Full shape only.
 * @property contentHtml Server-rendered HTML body, and THE ONLY BODY A READ CARRIES.
 *   Full shape only. Ravelry's `content` is write-only: it is accepted on create/reply
 *   and never returned by `/messages/list.json` (with or without `output_format=full`)
 *   or `/messages/{id}.json` — live-confirmed against a real account, 2026-08-06, after
 *   the `message-read` grant made reads observable at all (#366, #396). A plain-text
 *   `content` field used to exist on this model as a hedge while that was unverifiable;
 *   it was always null and has been deleted rather than left as a trap. Bodies render
 *   through the HTML path.
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
)
