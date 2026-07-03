package com.autom8ed.fibersocial.activity

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses a group's activity page (`/groups/{permalink}/activity`) into a [GroupActivityPage].
 *
 * The scraped markup (see `docs/samples/group_activity.html`) is:
 * ```
 * <div class="pagination"><span class="page_bar__current">1</span> …
 *   <span class="pagination__last_page">of 3</span></div>
 * <div id="recent_activity">
 *   <div class="project">                          ← class is "project" for EVERY activity type
 *     <a class="photo" href="{target}" id="activity_{id}"
 *        style="background-image: url('{thumb}'); …"></a>
 *     <img class="icon activity_icon … o-icon--projects o-icon o-icon--xs">
 *     <div class="details">
 *       <a href="{target}" id="activity_{id}_link">wildahose's Honeycomb Aran</a>
 *       <span class="touched">about 18 hours ago</span>
 *     </div>
 *   </div> …
 * ```
 * Lenient by design: an entry without a `.details` link is skipped; a missing photo,
 * icon, or age degrades to null/[GroupActivityType.UNKNOWN]/empty. The page honors the
 * viewer's per-user activity-type filter, so callers must not assume which types appear.
 */
object GroupActivityParser {

    /** Parses the full HTML of a group activity page; items keep page (newest-first) order. */
    fun parse(activityPageHtml: String): GroupActivityPage {
        val doc = Ksoup.parse(activityPageHtml)
        val items = doc.select("#recent_activity div.project").mapNotNull { parseItem(it) }

        val pagination = doc.selectFirst("div.pagination")
        val currentPage = pagination?.selectFirst("span.page_bar__current")
            ?.let { it.text().trim().toIntOrNull() } ?: 1
        val totalPages = pagination?.selectFirst("span.pagination__last_page")
            ?.let { totalPagesFrom(it.text()) } ?: currentPage
        return GroupActivityPage(items, currentPage, maxOf(totalPages, currentPage))
    }

    private fun parseItem(entry: Element): GroupActivityItem? {
        val link = entry.selectFirst("div.details a") ?: return null
        val photo = entry.selectFirst("a.photo")
        val typeKey = entry.selectFirst("img.activity_icon")?.let { iconKey(it) }.orEmpty()
        val targetUrl = link.attr("href")
        val (username, permalink) = projectRefFromHref(targetUrl)

        return GroupActivityItem(
            id = activityIdFrom(photo?.attr("id")) ?: activityIdFrom(link.attr("id")),
            type = GroupActivityType.fromIconKey(typeKey),
            typeKey = typeKey,
            title = link.text(),
            targetUrl = targetUrl,
            thumbnailUrl = photo?.let { thumbnailFromStyle(it.attr("style")) },
            ageText = entry.selectFirst("span.touched")?.text().orEmpty(),
            projectUsername = username,
            projectPermalink = permalink,
        )
    }

    private val ACTIVITY_ID_REGEX = Regex("""^activity_(\d+)(?:_link)?$""")
    private val BACKGROUND_URL_REGEX = Regex("""background-image:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""")
    private val TOTAL_PAGES_REGEX = Regex("""of\s+(\d+)""")
    private val PROJECT_HREF_REGEX = Regex("""/projects/([^/?#]+)/([^/?#]+)""")

    /** Icon-modifier suffixes (`o-icon--xs` etc.) that are sizing, not an activity type. */
    private val ICON_SIZE_MODIFIERS = setOf("xs", "sm", "md", "lg", "xl")

    internal fun activityIdFrom(elementId: String?): Long? =
        elementId?.let { id ->
            ACTIVITY_ID_REGEX.find(id)?.let { it.groupValues[1].toLongOrNull() }
        }

    /** Total page count from the pagination's "of N" text, or null when it doesn't parse. */
    private fun totalPagesFrom(lastPageText: String): Int? =
        TOTAL_PAGES_REGEX.find(lastPageText)?.let { it.groupValues[1].toIntOrNull() }

    /** The activity-type key from an entry icon's `o-icon--<key>` class, ignoring size modifiers. */
    internal fun iconKey(icon: Element): String? =
        icon.classNames()
            .filter { it.startsWith("o-icon--") }
            .map { it.removePrefix("o-icon--") }
            .firstOrNull { it !in ICON_SIZE_MODIFIERS }

    /** Photo URL from an inline `background-image` style; data: placeholder URIs yield null. */
    internal fun thumbnailFromStyle(style: String): String? =
        BACKGROUND_URL_REGEX.find(style)
            ?.let { match -> match.groupValues[1].takeUnless { it.startsWith("data:") } }

    /**
     * `(username, permalink)` when [href] is a project page link (`/projects/{user}/{permalink}`),
     * `(null, null)` otherwise. This pair is the join key for the projects API.
     */
    internal fun projectRefFromHref(href: String): Pair<String?, String?> {
        val match = PROJECT_HREF_REGEX.find(href) ?: return null to null
        val (username, permalink) = match.destructured
        return username to permalink
    }
}
