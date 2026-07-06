package com.autom8ed.fibersocial.projects

/**
 * A parsed `ravelry.com/projects/{username}/{permalink}` link (issue #103).
 *
 * @property username Owner of the project.
 * @property permalink The project's URL slug, usable as the `{id}` in
 *   `projects/{username}/{id}.json` (the endpoint accepts ID or permalink).
 */
data class ProjectLink(val username: String, val permalink: String) {
    /** The canonical web URL, for the open-on-Ravelry escape hatch. */
    val webUrl: String get() = "https://www.ravelry.com/projects/$username/$permalink"
}

private val PROJECT_LINK = Regex(
    """^https?://(?:www\.)?ravelry\.com/projects/([^/?#]+)/([^/?#]+)/?(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Parses [url] as a link to a specific Ravelry project, or `null` for anything else
 * (other hosts, `/projects/{username}` profile listings, deeper subpages like
 * `/projects/{username}/{permalink}/people`).
 */
fun parseProjectLink(url: String): ProjectLink? {
    val match = PROJECT_LINK.find(url.trim()) ?: return null
    val (username, permalink) = match.destructured
    return ProjectLink(username = username, permalink = permalink)
}
