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
fun ravelryAccountDeletionUrl(username: String?): String {
    val handle = username?.trim().orEmpty()
    if (handle.isEmpty()) return "https://www.ravelry.com/people/edit"
    // Ravelry handles are conventionally [A-Za-z0-9_-], but this value comes off the
    // wire, and an unescaped one would silently build a different URL than intended.
    return "https://www.ravelry.com/people/${handle.encodeURLPathPart()}/edit"
}
