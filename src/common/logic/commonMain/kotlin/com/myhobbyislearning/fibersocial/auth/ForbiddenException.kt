package com.myhobbyislearning.fibersocial.auth

/**
 * Thrown when Ravelry returns 403 Forbidden for an authenticated API request: the
 * credentials are valid but lack permission for the attempted action (e.g. a missing
 * OAuth scope or a moderator-only operation). Unlike [SessionExpiredException],
 * re-authenticating cannot fix this, so callers must surface an actionable error
 * instead of bouncing the user to login.
 *
 * @property body Ravelry's raw 403 response body, when available. [message] is
 *   deliberately generic (never the literal status digits — see its call site) so it's
 *   safe to surface in the UI as-is; [body] exists for callers that need to distinguish
 *   *why* a 403 happened (e.g. a specific missing-scope error) before deciding how to
 *   react, without every caller having to re-derive that from the exception's message.
 *   Defaults to `null` so existing catch sites are unaffected.
 */
class ForbiddenException(message: String, val body: String? = null) : Exception(message)
