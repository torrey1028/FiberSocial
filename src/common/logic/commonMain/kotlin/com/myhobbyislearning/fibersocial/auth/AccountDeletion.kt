package com.myhobbyislearning.fibersocial.auth

import io.ktor.http.encodeURLPathPart

/**
 * Deep link to the Ravelry page that carries the account-deletion control.
 *
 * App Store Review guideline 5.1.1(v) requires any app supporting account creation to
 * also offer account deletion — the 2026-08-07 rejection of 1.0 (3001). FiberSocial has
 * no accounts of its own: a FiberSocial account *is* a Ravelry account, and only Ravelry
 * can delete one (there is no API for it — no OAuth scope covers account destruction,
 * and the web form is a logged-in, CSRF-protected profile-edit control). The guideline
 * covers exactly this case: "If users need to visit a website to finish deleting their
 * account, include a link directly to the website page where they can complete the
 * process." So this is a link, not an in-app flow — and it deliberately opens in the
 * system browser rather than a WebView, because the user usually has to log in to
 * Ravelry there first and the login WebView is confined to the OAuth flow
 * ([isAllowedLoginNavigation]).
 *
 * The page is the profile editor. Ravelry's own privacy policy is the source: "you may
 * terminate your account and delete your account data at any time for any reason by
 * navigating to your profile page, choosing 'edit profile' and using the 'delete Ravelry
 * account' link at the bottom of the page." That link has no separate URL of its own —
 * `/people/<user>/edit/...` sub-paths all resolve through the same generic route, so
 * there is nothing deeper to point at. `/people/<user>/edit` is as direct as Ravelry
 * gets, which is why the confirmation dialog that precedes this tells the user to look
 * for the link at the bottom of the page rather than leaving them hunting.
 *
 * [username] is null only in the window before `/current_user.json` has resolved. Ravelry
 * accepts `/people/edit` without a handle and redirects it to the signed-in user's own
 * editor, so the fallback still lands on the right page instead of a dead end.
 */
fun ravelryAccountDeletionUrl(username: String?): String =
    "https://www.ravelry.com${accountDeletionPath(username)}"

/** Path component of [ravelryAccountDeletionUrl], shared with the navigation allowlist. */
private fun accountDeletionPath(username: String?): String {
    val handle = username?.trim().orEmpty()
    // Ravelry accepts the handle-less form and redirects it to the signed-in user's own
    // editor, so the pre-`/current_user.json` case still lands on the right page.
    if (handle.isEmpty()) return "/people/edit"
    // Ravelry handles are conventionally [A-Za-z0-9_-], but this value comes off the
    // wire, and an unescaped one would silently build a different URL than intended.
    val encoded = handle.encodeURLPathPart()
    // A handle that needed escaping puts a % in the path — and ravelrySafePath rejects
    // any %, so isAllowedAccountDeletionNavigation would refuse the very URL this
    // function just built and the web view would block its own first page. Falling back
    // to the handle-less editor keeps the builder and the allowlist provably in
    // agreement, and Ravelry redirects it to the signed-in user's own editor anyway.
    if (encoded != handle) return "/people/edit"
    return "/people/$encoded/edit"
}

/**
 * Whether the account-deletion web view may render [url] for the signed-in [username].
 *
 * An allowlist, not a host check, for the same reason the login WebView has one: an
 * unconstrained in-app web view on ravelry.com is the whole logged-in website, and that
 * is what App Review crashed the app through under 2.1(a) (issue #425) — they reached the
 * web messages composer's image upload from a page we had let them onto. "It's still
 * Ravelry" is not a safety property; the reviewer never left Ravelry either.
 *
 * What is allowed is only what the deletion flow actually walks through:
 *
 * - The profile editor itself ([ravelryAccountDeletionUrl]) and its tab sub-paths, which
 *   is where Ravelry's "delete Ravelry account" link lives.
 * - `/account/login`, because Ravelry bounces there when the session is not valid, and
 *   the sign-in form POSTs back to the same path.
 *
 * Everything else — the home page, the forums, messages, another member's profile — is
 * cancelled and logged by the platform web views.
 *
 * **This list is provisional in one place, on purpose.** The URL behind Ravelry's delete
 * link itself has not been observed: `/people/<user>/edit/<anything>` all resolve through
 * one generic route when signed out, so it cannot be discovered without walking the flow
 * on a real account. It is very likely under the editor prefix and therefore already
 * covered — but until a device trace confirms it, the blocked-navigation log line is what
 * will say so. Do not widen this list by guessing; widen it from a trace, the way the
 * login allowlist was built (issue #425).
 */
fun isAllowedAccountDeletionNavigation(url: String, username: String?): Boolean {
    val path = ravelrySafePath(url) ?: return false
    // Not user-scoped. /account/login is where Ravelry bounces a stale session (its form
    // POSTs back to the same path); /account/closed is where deletion lands afterwards,
    // and is the only page that confirms to the user that it worked.
    if (path == "/account/login" || path == "/account/closed") return true
    val editor = accountDeletionPath(username)
    if (path == editor || path.startsWith("$editor/")) return true
    val profile = editor.removeSuffix("/edit")
    // The two steps behind Ravelry's "delete Ravelry account" link, both observed on
    // device (2026-08-13) and neither derivable from outside: confirm_delete is the
    // confirmation page, delete is what its "permanently delete my account" button
    // submits to. Both are SIBLINGS of the editor rather than children, so the prefix
    // this list originally shipped with covered neither — the page simply blanked.
    return path == "$profile/confirm_delete" || path == "$profile/delete"
}
