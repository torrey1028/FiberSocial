package com.myhobbyislearning.fibersocial.feed

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.myhobbyislearning.fibersocial.feed.models.ActivityItem
import com.myhobbyislearning.fibersocial.feed.models.ActivityType
import com.myhobbyislearning.fibersocial.feed.models.parseActorUsername

/** One page of a group's activity, plus whether Ravelry offers another. */
data class ActivityPage(
    val items: List<ActivityItem>,
    val hasMore: Boolean,
)

/**
 * Parses a group's Activity page (epic #483) — the HTML behind
 * `www.ravelry.com/groups/browse/activity/{permalink}`.
 *
 * Ravelry has no group-activity JSON API, so this is a scrape. The shape, from captured
 * pages (a trimmed one is the fixture in `GroupActivityParserTest`):
 *
 * ```
 * <div class="page_links"> … <a class="next_page">Next →</a> </div>
 * <div id="recent_activity">
 *   <div class="project" style="position: relative; ">
 *     <a class="photo …" href="{targetUrl}" id="activity_807483746"
 *        style="background-image: url('{thumbnail}'); …"></a>
 *     <img class="icon activity_icon … o-icon--stash o-icon o-icon--xs" src="…/stash.svg">
 *     <div class="details">
 *       <a href="{targetUrl}" id="activity_807483746_link">Alecchi stashed Somsomknit …</a>
 *       <span class="touched">about 12 hours ago</span>
 *     </div>
 *   </div>
 *   …39 more
 * </div>
 * ```
 *
 * Two traps this parser exists to encapsulate:
 *
 * 1. **`div.project` is not the type discriminator.** Every item carries that class
 *    whatever it actually is — one captured page held 40 `div.project` items spanning four
 *    different activity types. The `o-icon--*` modifier is the only thing that
 *    distinguishes them (see [ActivityType.iconModifier]).
 * 2. **The next-page control never disappears.** On the last page it degrades to
 *    `<div class="next_page next_page--empty">` rather than being absent, so a `.next_page`
 *    class selector matches on *every* page. Selecting the class instead of the anchor
 *    would report "more pages" forever and page past the end indefinitely. See [parseHasMore].
 */
object GroupActivityParser {

    /** Ravelry serves 40 activity items per page. A short page therefore means the end. */
    const val PAGE_SIZE = 40

    /**
     * Matches both places the id appears: `id="activity_807483746"` on the photo anchor and
     * `id="activity_807483746_link"` on the title link. The `_link` alternative is not
     * belt-and-braces — an item with no photo has no photo anchor, so the title link is the
     * *only* place its id exists, and requiring the bare form silently drops those items.
     */
    private val ACTIVITY_ID_REGEX = Regex("""^activity_(\d+)(?:_link)?$""")
    private val BACKGROUND_IMAGE_URL_REGEX = Regex("""background-image:\s*url\(\s*['"]?(.*?)['"]?\s*\)""")
    private val ICON_MODIFIER_REGEX = Regex("""o-icon--([A-Za-z0-9_-]+)""")

    /**
     * Parses one activity page out of [html].
     *
     * Malformed items are skipped rather than failing the page: a single item Ravelry
     * renders unusually must not blank a member's whole feed. Each skip logs why, because
     * "some rows are silently missing" is otherwise undiagnosable from a device.
     */
    fun parse(html: String): ActivityPage {
        val doc = Ksoup.parse(html)
        val items = doc.select("#recent_activity > div")
            .mapNotNull { parseItem(it) }
            // Sort rather than trusting document order: ids jitter by a handful within a
            // single photo-upload batch (a run sharing one project and one timestamp), and
            // the id is the only exact ordering key — the markup has no timestamp at all.
            .sortedByDescending { it.id }
        return ActivityPage(items = items, hasMore = parseHasMore(doc))
    }

    /**
     * Whether Ravelry offers a further page.
     *
     * **Must select the anchor, not the class.** The paginator always renders a
     * `next_page` element; on the last page it is an empty `div` carrying an extra
     * `next_page--empty` modifier:
     *
     * ```
     * page 1:    <a href="…?page=2" class="next_page">Next →</a>
     * last page: <div class="next_page next_page--empty">&nbsp;</div>
     * ```
     *
     * (`previous_page` degrades identically on page 1.)
     */
    private fun parseHasMore(doc: Element): Boolean =
        doc.selectFirst("a.next_page") != null

    private fun parseItem(container: Element): ActivityItem? {
        // The id lives on the photo anchor as id="activity_<id>", and is repeated on the
        // title link as id="activity_<id>_link". Take whichever is present — the photo
        // anchor is absent when an item has no image.
        val id = container.select("[id]")
            .firstNotNullOfOrNull { ACTIVITY_ID_REGEX.find(it.id())?.groupValues?.get(1) }
            ?.toLongOrNull()
        if (id == null) {
            println("FiberSocial: GroupActivityParser: skipping an item with no activity id")
            return null
        }

        val iconModifier = container.selectFirst("img.activity_icon")
            ?.className()
            ?.let { ICON_MODIFIER_REGEX.find(it)?.groupValues?.get(1) }
        if (iconModifier == null) {
            println("FiberSocial: GroupActivityParser: skipping activity $id — no o-icon-- modifier")
            return null
        }
        val type = ActivityType.fromIconModifier(iconModifier)
        if (type == null) {
            // Not a defect: Ravelry can add a ninth activity type whenever it likes. Naming
            // the modifier is what turns a silently missing row into a diagnosable report.
            println("FiberSocial: GroupActivityParser: skipping activity $id — unknown type icon '$iconModifier'")
            return null
        }

        val titleLink = container.selectFirst("div.details a[href]")
            ?: container.selectFirst("a[id\$=_link]")
        val targetUrl = titleLink?.attr("href")?.trim().orEmpty()
        val title = titleLink?.text()?.trim().orEmpty()
        if (targetUrl.isEmpty() || title.isEmpty()) {
            println("FiberSocial: GroupActivityParser: skipping activity $id — no title link")
            return null
        }

        return ActivityItem(
            id = id,
            type = type,
            // Verbatim: there is no absolute timestamp anywhere in the markup, and this
            // text is day-granular past a day, so it is for display only — never sorting.
            relativeTime = container.selectFirst("span.touched")?.text()?.trim().orEmpty(),
            actorUsername = parseActorUsername(title = title, targetUrl = targetUrl),
            title = title,
            thumbnailUrl = parseThumbnailUrl(container),
            targetUrl = targetUrl,
        )
    }

    /**
     * The item image, pulled out of the inline `background-image: url('…')` on the photo
     * anchor — Ravelry renders these as CSS backgrounds, not `<img>` tags.
     *
     * Null when the item has no photo. Ravelry also paints a `data:image/svg+xml` colour
     * placeholder on the *frame* behind real photos, so anything inline-data is rejected
     * rather than shown as if it were content.
     */
    private fun parseThumbnailUrl(container: Element): String? =
        container.select("a.photo[style]")
            .firstNotNullOfOrNull { anchor ->
                BACKGROUND_IMAGE_URL_REGEX.find(anchor.attr("style"))
                    ?.groupValues?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            }
}
