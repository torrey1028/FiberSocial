package com.autom8ed.fibersocial.feed.html

/**
 * Golden fixture: the rendered `body_html` of a real, unusually rich Ravelry forum post
 * (from the "Library/Queue Sets" topic in the Ravelry API group), captured from the
 * website DOM. The canonical capture lives at `docs/samples/complex_post.html`; keep the
 * two in sync if the sample is ever re-captured.
 *
 * It exercises paragraphs with `<br>`, bullet lists with inline code, headings with `id`
 * slugs, a full `<thead>`/`<tbody>` table with per-cell alignment styles, inline code in
 * table cells, a fenced code block, and HTML entities.
 */
internal val COMPLEX_POST_HTML = """
<div class="body forum_post_body">

<p>hope this helps -- do you create a web app, or a mobile app ? <br>or what are you trying to do ? do you want it e.g. excel? <br>if so, there`s also an export function on the website  in the top right corner</p>

<p>Sets in the website UI map to two API mechanisms working together:</p>

<ul>
<li><code>tag_names</code> (array of strings) on the entity itself — Project, Stash entry, Friendship, Collection.</li>

<li><code>Collection</code> objects (<code>{ id, permalink, title, tag_names[] }</code>) — a named group of tag names. The Collection’s <code>title</code> is what you see as a Set name in the UI.</li>
</ul>

<p>Favourites (bookmarks) use a different shape: a space-delimited <code>tag_list</code> string.</p>

<h2 id="what_is_returned">what is returned</h2>
<table><thead><tr><th>Area</th><th>Per-item categorisation</th><th>Top-level Sets / Collections</th></tr></thead><tbody><tr><td style="text-align: left;">Projects</td><td style="text-align: left;"><code>tag_names[]</code> per project</td><td style="text-align: left;"><code>collections[]</code> via <code>?include=collections</code></td></tr>
<tr><td style="text-align: left;">Stash</td><td style="text-align: left;"><code>tag_names[]</code> per stash entry</td><td style="text-align: left;">(none — but you can group by <code>tag_names</code>)</td></tr>
<tr><td style="text-align: left;">Friends</td><td style="text-align: left;"><code>tag_names[]</code> per friendship</td><td style="text-align: left;"><code>collections[]</code> returned by default</td></tr>
<tr><td style="text-align: left;">Favourites</td><td style="text-align: left;"><code>tag_list</code> (space-delimited)</td><td style="text-align: left;">Bundles, via separate CRUD endpoints</td></tr>
<tr><td style="text-align: left;">Library</td><td style="text-align: left;">NOT returned</td><td style="text-align: left;">NOT returned — tag-filter only</td></tr>
<tr><td style="text-align: left;">Queue</td><td style="text-align: left;">NOT returned</td><td style="text-align: left;">NOT returned — tag-filter only</td></tr>
</tbody></table>
<p>Library and Queue accept tags as a filter input (<code>query_type=tags&amp;query=</code>) but do not provide a <code>tag_names</code> field back on the volume / queued_project, and do not return a <code>collections</code> array even with <code>include=collections</code>. The read-side <code>QueuedProjectFull_for_owner</code> and <code>QueuedProjectSmall_for_owner</code> data models simply do not list <code>tag_names</code>. Only the <code>QueuedProject (POST)</code> input form does, so you can SET tags but not READ them via the queue endpoints.</p>

<h2 id="examples_in_pseudo_code">Examples in pseudo code</h2>

<p>Let me know which language / script you use then i can adjust</p>

<pre class="fenced_code"><code># 1. PROJECTS — Collections returned natively
collections, projects = GET /projects/{user}/list.json?include=collections&amp;page_size=100
for project in projects:
    project.tag_names           # ["sewing", "shawl"]
for c in collections:
    c.title, c.tag_names        # "Sewing" -&amp;gt; ["sewing"]

# 2. FRIENDS — Collections returned natively
collections, friendships = GET /people/{user}/friends/list.json
for f in friendships:
    f.tag_names

# 3. STASH — tag_names is a regular field on the entry
stash_entries = GET /people/{user}/stash/list.json     # response key: "stash"
for s in stash_entries:
    s.tag_names                 # ["wool", "lace-weight"]

# 4. FAVOURITES — tag_list is space-delimited string per bookmark
favorites = GET /people/{user}/favorites/list.json
for f in favorites:
    f.tag_list.split(" ")

# 5. LIBRARY — no Collections, no tag_names 
library_sets = ["Gnome", "Cardigan", "Shawl", ...]   # known from website UI
for tag in library_sets:
    page = 1
    until last_page reached:
        volumes, paginator = GET /people/{user}/library/search.json
                             ? query_type=tags
                             &amp; query=urlencode(tag)
                             &amp; page=page
                             &amp; page_size=100
        for v in volumes:
            store(set=tag, volume_id=v.id, pattern_id=v.pattern_id, title=v.title)
        page += 1
        last_page = paginator.last_page

# 6. QUEUE — same pattern as Library
queue_sets = ["Next up", "Holiday gifts", ...]
for tag in queue_sets:
    queued = GET /people/{user}/queue/list.json
             ? query_type=tags
             &amp; query=urlencode(tag)
             &amp; page_size=100</code></pre>

</div>"""
