package com.autom8ed.fibersocial.auth

/**
 * Thrown when Ravelry returns 403 Forbidden for an authenticated API request: the
 * credentials are valid but lack permission for the attempted action (e.g. a missing
 * OAuth scope or a moderator-only operation). Unlike [SessionExpiredException],
 * re-authenticating cannot fix this, so callers must surface an actionable error
 * instead of bouncing the user to login.
 */
class ForbiddenException(message: String) : Exception(message)
