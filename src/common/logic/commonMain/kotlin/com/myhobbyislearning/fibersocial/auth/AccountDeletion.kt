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
 * account' link at the bottom of the page." The link does have a URL of its own —
 * `/people/<user>/confirm_delete`, observed on device 2026-08-13 — but it is not the
 * right thing to open: it is the confirmation step, so linking straight to it would drop
 * the user in front of a delete button having skipped the page the guideline points at.
 * `/people/<user>/edit` is the entry point, which is why the confirmation dialog that
 * precedes this tells the user to look for the link at the bottom of the page.
 *
 * [username] is null only in the window before `/current_user.json` has resolved. Ravelry
 * accepts `/people/edit` without a handle and redirects it to the signed-in user's own
 * editor, so the fallback still lands on the right page instead of a dead end.
 */
fun ravelryAccountDeletionUrl(username: String?): String =
    "https://www.ravelry.com${accountDeletionPath(username)}"

/**
 * The editor path with no handle in it, which Ravelry resolves to the signed-in user's
 * own. Both the URL builder's fallback and the allowlist's "handle unknown" case.
 */
private const val HANDLELESS_EDITOR = "/people/edit"

/** Path component of [ravelryAccountDeletionUrl], shared with the navigation allowlist. */
private fun accountDeletionPath(username: String?): String {
    val handle = username?.trim().orEmpty()
    // Ravelry accepts the handle-less form and redirects it to the signed-in user's own
    // editor, so the pre-`/current_user.json` case still lands on the right page.
    if (handle.isEmpty()) return HANDLELESS_EDITOR
    // Ravelry handles are conventionally [A-Za-z0-9_-], but this value comes off the
    // wire, and an unescaped one would silently build a different URL than intended.
    val encoded = handle.encodeURLPathPart()
    // A handle that needed escaping puts a % in the path — and ravelrySafePath rejects
    // any %, so isAllowedAccountDeletionNavigation would refuse the very URL this
    // function just built and the web view would block its own first page. Falling back
    // to the handle-less editor keeps the builder and the allowlist provably in
    // agreement, and Ravelry redirects it to the signed-in user's own editor anyway.
    if (encoded != handle) return HANDLELESS_EDITOR
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
 * What is allowed is only what the deletion flow actually walks through, every entry
 * below confirmed by walking it on a real account (2026-08-13):
 *
 * - The profile editor itself ([ravelryAccountDeletionUrl]) and its tab sub-paths, which
 *   is where Ravelry's "delete Ravelry account" link lives.
 * - `/people/<user>/confirm_delete`, that link's target, and `/people/<user>/delete`,
 *   what its "permanently delete my account" button submits to.
 * - `/account/login`, because Ravelry bounces there when the session is not valid, and
 *   the sign-in form POSTs back to the same path.
 * - `/account/closed`, where deletion lands afterwards — the only page that tells the
 *   user it actually worked.
 *
 * Everything else — the home page, the forums, messages, another member's profile — is
 * cancelled and logged by the platform web views; `/help`, `/about`, `/contact`,
 * `/patterns` and `/` were all confirmed blocked in that same trace.
 *
 * **The list originally shipped guessing that the delete link sat under the editor
 * prefix.** It does not: `confirm_delete` and `delete` are SIBLINGS of `edit`, so the
 * prefix covered neither and each step blanked in turn, looking like a dead button rather
 * than a blocked navigation. That is the standing argument for only ever widening this
 * from a trace, the way the login allowlist was built (issue #425) — reasoning from
 * outside got it wrong here, and quietly.
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
    if (path == "$profile/confirm_delete" || path == "$profile/delete") return true
    // Handle unknown at open time, so every check above is scoped to the handle-LESS
    // editor — but the pages this flow actually reaches all carry a handle. Ravelry
    // resolves /people/edit to the signed-in user's own editor, and the delete link on
    // it points at /people/<their-handle>/confirm_delete. Without this the flow blocks
    // its own next page and blanks, which is reachable: FeedScreen renders Settings
    // while the feed is still Loading (its `user` is null then), so a rotation with
    // Settings open, then a tap on Delete account, opens the page with no handle.
    //
    // Matching the shape rather than a specific handle is a real widening, and a
    // deliberately small one: still only the editor and the two delete endpoints, so
    // messages — the composer image upload that produced the 2.1(a) crash — the forums,
    // and the home page stay blocked exactly as before. Ravelry refuses these pages for
    // anyone but their owner anyway (/people/<someone>/confirm_delete 404s), so the
    // widened set is not usable against another account.
    return editor == HANDLELESS_EDITOR && isAnyHandleDeletionPath(path)
}

/**
 * Whether [path] is one of the deletion flow's pages for SOME single-segment handle —
 * `/people/<handle>/edit` (and its tab sub-paths), `/people/<handle>/confirm_delete`, or
 * `/people/<handle>/delete`. Used only when the signed-in handle is unknown; see the
 * caller for why that case cannot simply be refused.
 */
private fun isAnyHandleDeletionPath(path: String): Boolean {
    val rest = path.removePrefix("/people/").takeIf { it != path } ?: return false
    val handle = rest.substringBefore('/')
    if (handle.isEmpty()) return false
    val tail = rest.removePrefix(handle)
    return tail == "/edit" || tail.startsWith("/edit/") ||
        tail == "/confirm_delete" || tail == "/delete"
}
