package com.myhobbyislearning.fibersocial.feed

/**
 * A real group Activity page, trimmed. Canonical copy:
 * `docs/samples/group_activity_page.html` — keep the two in sync.
 *
 * Page 1 of 40 for `kirkland-fiber-arts-circle-2`, requested with all eight type filters.
 * Chrome, scripts, styles and 32 of the 40 items were removed; what remains is as Ravelry
 * served it, apart from local-file image refs pointed back at remote URLs and the base64
 * colour-placeholder backgrounds dropped for size.
 *
 * What it covers, deliberately:
 * - the four activity types observed on real items (`projects`, `stash`, `queue`, `favorites`)
 *   — all rendered as `div.project`, which is why the container class can't discriminate;
 * - both possessive-username forms, including one already ending in s;
 * - favorites/queued items targeting `/patterns/library/…`, which carry no username in the URL;
 * - a live `<a class="next_page">` paginator.
 *
 * Last-page and malformed-item markup are inline in the tests instead, since they can't
 * coexist with this page in one capture.
 */
internal const val GROUP_ACTIVITY_PAGE_HTML = """<!-- Trimmed from a real Ravelry group Activity page:
     GET /groups/browse/activity/{permalink}?page=1&type_1=1..type_8=1
     Page chrome, scripts, styles and 32 of 40 items removed; the retained markup is
     byte-for-byte as Ravelry served it apart from local-file image refs being pointed
     back at remote URLs and base64 placeholder backgrounds dropped for size.
     Covers the four activity types observed on real items, plus both possessive-username
     forms. Last-page/edge-case markup lives inline in the tests instead. -->
<html><body>
<div class="page_links"><div class="previous_page previous_page--empty">&nbsp;</div><div class="pagination"><span aria-current="page" class="page_bar__current">1</span> <a href="https://www.ravelry.com/groups/browse/activity/kirkland-fiber-arts-circle-2?page=2&type_1=1&type_2=1&type_3=1&type_4=1&type_5=1&type_6=1&type_7=1&type_8=1" class="page_bar__page">2</a> <span class="ellipsis">...</span> <a href="https://www.ravelry.com/groups/browse/activity/kirkland-fiber-arts-circle-2?page=40&type_1=1&type_2=1&type_3=1&type_4=1&type_5=1&type_6=1&type_7=1&type_8=1" class="page_bar__page">40</a> <span class="pagination__last_page">of 40</span><select class="page_bar__hopper hopper" id="hopper_1" onkeydown="R.utils.loadHopper(this, 1, 40, &quot;?type_1=1&type_2=1&type_3=1&type_4=1&type_5=1&type_6=1&type_7=1&type_8=1&page=&quot;);" onmouseover="R.utils.loadHopper(this, 1, 40, &quot;?type_1=1&type_2=1&type_3=1&type_4=1&type_5=1&type_6=1&type_7=1&type_8=1&page=&quot;);" onchange="navigateWithSelect(this);"><option></option></select><label class="page_bar__hopper__label" for="hopper_1" aria-label="Jump to a page"></label></div><a href="https://www.ravelry.com/groups/browse/activity/kirkland-fiber-arts-circle-2?page=2&type_1=1&type_2=1&type_3=1&type_4=1&type_5=1&type_6=1&type_7=1&type_8=1" class="next_page"><span class="rsp_hidden">Next</span> →</a></div>
<div id="recent_activity">
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/people/Alecchi/stash/star-cluster-3" id="activity_807483746" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/Alecchi/1160819002/image_small.jpg&#39;); background-position: -5px -85px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--stash o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/stash.svg">
<div class="details">
<a href="https://www.ravelry.com/people/Alecchi/stash/star-cluster-3" id="activity_807483746_link">Alecchi stashed Somsomknit Star Cluster</a>
<span class="touched">less than a minute ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/patterns/library/rosi-5" id="activity_807473984" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/strickauszeit/859975407/webp/Foto_0_JHYG9981_small_best_fit.webp#JPG&#39;); background-position: 0px -50px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--favorites o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/favorites.svg">
<div class="details">
<a href="https://www.ravelry.com/patterns/library/rosi-5" id="activity_807473984_link">FlowerPower111 favorited Rosi by Christina Körber-Reith</a>
<span class="touched">about 1 hour ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/patterns/library/rolled-raglan" id="activity_807470634" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/JulesKnitThis/1157245461/IMG_6862_small_best_fit.jpeg&#39;); background-position: -4px -110px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--favorites o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/favorites.svg">
<div class="details">
<a href="https://www.ravelry.com/patterns/library/rolled-raglan" id="activity_807470634_link">CraftyStrawberi favorited Rolled Raglan by Jules Efterfield</a>
<span class="touched">about 1 hour ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/patterns/library/frankie-sweater-2" id="activity_807470239" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/PetiteKnitDK/1105454029/frankie_sweater_velvet_fig_almond_tweed2_small_best_fit.jpg&#39;); background-position: 0px -72px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--favorites o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/favorites.svg">
<div class="details">
<a href="https://www.ravelry.com/patterns/library/frankie-sweater-2" id="activity_807470239_link">CraftyStrawberi favorited Frankie Sweater by PetiteKnit</a>
<span class="touched">about 1 hour ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/patterns/library/riviera-bag" id="activity_807462252" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/PaulaMknits/866358240/webp/IMG_7730-01_small_best_fit.webp#jpg&#39;); background-position: 2px -90px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--queue o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/queue.svg">
<div class="details">
<a href="https://www.ravelry.com/patterns/library/riviera-bag" id="activity_807462252_link">oldfashionlady queued RIVIERA Bag by Susanne Müller</a>
<span class="touched">about 2 hours ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/patterns/library/plume-top--dress" id="activity_807459164" style="background-image: url(&#39;https://images4-f.ravelrycache.com/uploads/mayatinks/1160370928/MayaIX_c_AlexiaLinn-75_small_best_fit.jpg&#39;); background-position: 0px -53px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--queue o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/queue.svg">
<div class="details">
<a href="https://www.ravelry.com/patterns/library/plume-top--dress" id="activity_807459164_link">StitchInSeattle queued Plume Top &amp; Dress by Maya Déglon</a>
<span class="touched">about 3 hours ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/projects/wildahose/turtle-dove-v-neck" id="activity_807349588" style="background-image: url(&#39;https://images4-g.ravelrycache.com/uploads/wildahose/1160674784/image-0_small.jpg&#39;); background-position: -5px -85px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--projects o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/projects.svg">
<div class="details">
<a href="https://www.ravelry.com/projects/wildahose/turtle-dove-v-neck" id="activity_807349588_link">wildahose's Turtle Dove V-neck</a>
<span class="touched">about 19 hours ago</span>
</div>
</div>
<div class="project" style="position: relative; ">
<div class="photo_border framed_photo photo_170"><div class="photo_frame photo_170__frame photo_frame--with_placeholder" style=""><a class="photo photo_170__photo" href="https://www.ravelry.com/people/Klokief/stash/linen-quill" id="activity_807330661" style="background-image: url(&#39;https://images4-g.ravelrycache.com/uploads/Klokief/1160657550/Screenshot_2026-08-12_160132_small_best_fit.png&#39;); background-position: 0px -51px; background-repeat: no-repeat;"></a></div></div>
<img alt="" aria-hidden="true" class="icon activity_icon icon_16 o-icon--stash o-icon o-icon--xs" src="https://static.ravelry.com/assets/icons/stash.svg">
<div class="details">
<a href="https://www.ravelry.com/people/Klokief/stash/linen-quill" id="activity_807330661_link">Klokief stashed Purl Soho Linen Quill</a>
<span class="touched">about 22 hours ago</span>
</div>
</div>
</div>
</body></html>
"""
