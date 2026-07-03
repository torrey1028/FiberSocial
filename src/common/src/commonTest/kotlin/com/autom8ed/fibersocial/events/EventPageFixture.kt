package com.autom8ed.fibersocial.events

/**
 * Golden fixture: trimmed capture of a real event page (Wednesday HH at Chainline,
 * 2026-06-28) — authenticity-token meta, page title, attend button ("save event" =
 * not attending), and the full `event__detail` block with venue, markdown description,
 * and 10 linked discussions. Canonical copy: `docs/samples/event_page.html` — keep the
 * two in sync.
 */
internal val EVENT_PAGE_HTML = """
<!DOCTYPE html>
<!-- Trimmed capture of https://www.ravelry.com/events/wednesday-hh-at-chainline-38 (2026-06-28).
     Page chrome removed; page_title, attend button, and event__detail are verbatim. -->
<html>
<head><title>Ravelry: Event - Wednesday HH at Chainline</title>
<meta content="JEN38Bicg515OwvzGE7jaHa9qYjMQgYwmIZsRpzPYzU=" id="authenticity-token" name="authenticity-token">
</head>
<body>
<div class="page_title">
<div class="page_title__supertitle">
<a href="https://www.ravelry.com/events">events</a>
</div>
Wednesday HH at Chainline
</div>
<a class="button" href="https://www.ravelry.com/events/wednesday-hh-at-chainline-38#" id="attend_button" onclick="R.events.prepareAttend(); return false;">
<img alt="" aria-hidden="true" class="icon_16 o-icon--calendar o-icon o-icon--xs" src="./Ravelry_ Event - Wednesday HH at Chainline_files/events.svg">
<span>save event</span>
</a>
<div class="event__detail">
<div class="event__detail__content">
<div class="event__header">
<div class="event__header__summary">
<h2 class="core_item_content__title--without_attribution rsp_hidden">
Wednesday HH at Chainline
</h2>
<div class="event__type">
Knitting/crochet group
</div>
<div class="event__dates">
July  1, 2026
@  5:30 PM
</div>
</div>
</div>
<div style="clear:both;" class="c_d"></div>
<ul id="venue_summary">
<li class="venue_name">
Chainline Brewing
</li>
<li class="address">
500 Uptown Ct Ste 210, Kirkland, WA 98033
</li>
<li class="city_state">
Kirkland, Washington
</li>
<li class="country">
United States
</li>
</ul>
<div style="clear:both;" class="c_d"></div>
<div id="subsections" style="margin-top: 32px;">
<div class="subsection">
<div class="markdown">

<p>Bring your latest project and join us for our weekly happy hour! (no drinking required)</p>

</div>
</div>
<div class="subsection" style="margin-top: 32px;">
<h3>
<img alt="" aria-hidden="true" class="icon_16 o-icon--groups o-icon o-icon--xs" src="./Ravelry_ Event - Wednesday HH at Chainline_files/groups.svg">
discussions and groups
</h3>
<div class="event__groups" id="groups">
<a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2" class="badge_link" id="group_badge_50191" title="Kirkland Fiber Arts Circle: We are a hyper-local group in Kirkland, WA, USA that focuses on in-person meet ups celebrating all forms of fiber arts."><img alt="" class="group_badge" src="./Ravelry_ Event - Wednesday HH at Chainline_files/Blank_200_x_120.png"></a>

</div>
<div style="clear:both;" class="c_d"></div>
<table class="bordered grid lined">
<thead>
<tr>
<th>Group</th>
<th>Started on</th>
<th>Last post</th>
<th>Posts</th>
</tr>
</thead>
<tbody>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4410262">Felted bowl making oarty</a>
</td>
<td>June 22, 2026</td>
<td>
16 hours
ago
</td>
<td>
3
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4381834">Member introductions</a>
</td>
<td>November  2, 2025</td>
<td>
9 days
ago
</td>
<td>
31
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4409633">Cap Hill stitch cafe relocating</a>
</td>
<td>June 15, 2026</td>
<td>
12 days
ago
</td>
<td>
2
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4384629">Show off your FOs</a>
</td>
<td>November 24, 2025</td>
<td>
20 days
ago
</td>
<td>
15
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4407821">Knit in Public Day</a>
</td>
<td>May 30, 2026</td>
<td>
26 days
ago
</td>
<td>
5
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4406724">2026 retreat</a>
</td>
<td>May 19, 2026</td>
<td>
~1 month
ago
</td>
<td>
3
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4406879">IG group chat</a>
</td>
<td>May 21, 2026</td>
<td>
~1 month
ago
</td>
<td>
3
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4405969">Granny square adventures</a>
</td>
<td>May 12, 2026</td>
<td>
~1 month
ago
</td>
<td>
11
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4398132">Puget Sound LYS Tour</a>
</td>
<td>March  2, 2026</td>
<td>
~1 month
ago
</td>
<td>
7
</td>
</tr>
<tr>
<td>
<a href="https://www.ravelry.com/discuss/kirkland-fiber-arts-circle-2/4381854">Works in Progress </a>
</td>
<td>November  2, 2025</td>
<td>
2 months
ago
</td>
<td>
23
</td>
</tr>
</tbody>
</table>

</div>
</div>
</div>
<div class="event__detail__sidebar core_item_sidebar">
<div class="event__header__tools" id="tool_buttons">

<div id="button_box">
<div class="button_set button_set--2 false" id="event_button_set">
<a class="social_share_button button" href="https://www.ravelry.com/events/wednesday-hh-at-chainline-38#" id="social_share_button" onclick="R.coreItems.socialShare(); return false;">
<img alt="" aria-hidden="true" class="icon_16 o-icon--share o-icon o-icon--xs" src="./Ravelry_ Event - Wednesday HH at Chainline_files/share.svg">
<span>share this</span>
</a>
<a class="button" href="https://www.ravelry.com/events/wednesday-hh-at-chainline-38#" id="attend_button" onclick="R.events.prepareAttend(); return false;">
<img alt="" aria-hidden="true" class="icon_16 o-icon--calendar o-icon o-icon--xs" src="./Ravelry_ Event - Wednesday HH at Chainline_files/events.svg">
<span>save event</span>
</a>
<div style="clear:both;" class="c_d"></div>
</div>

</div></div>
<div style="clear:both;" class="c_d"></div>
<div class="photo_gallery_container">

<div class="photo_gallery resizable_photo_gallery resizable_photo_gallery--size_0" data-photo-manager="0" data-photographable-id="71853" data-photographable-type="Event">
<div class="photo_border framed_photo photo_170 framed_photo--empty"><div class="photo_frame photo_170__frame"><div class="photo photo_170__photo" style="background-position: center; background-image: url(https://style-cdn.ravelrycache.com/images/assets/illustrations/color/svg/blank-skein-hebridean.svg?v=6); background-repeat: no-repeat;"></div></div></div>
<div style="clear:both;" class="c_d"></div>
</div>
</div>

<div style="clear:both;" class="c_d"></div>
<div class="rsp_hidden">
<div class="event__detail__map">
<iframe allowfullscreen="true" class="x-embedded_map " frameborder="0" height="240" src="./Ravelry_ Event - Wednesday HH at Chainline_files/place.html" style="border:0" title="Google Map showing 500 Uptown Ct Ste 210, Kirkland, WA 98033, Kirkland, Washington" width="210"></iframe>
</div>
<div class="title" style="text-align: center; margin-top: 1em; margin-bottom: 3em; width: 240px;">
<img alt="" aria-hidden="true" class="inline icon_16 o-icon--directions o-icon o-icon--xs" src="./Ravelry_ Event - Wednesday HH at Chainline_files/roadtrip.svg">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-38#" onclick="R.maps.startDrivingDirections(); return false;">get directions</a>
<div id="driving_directions_entry" style="margin-top: 2em; display: none;">
<div class="dd_container">
<div class="legend" style="color: #666; font-size: .9em;">
get directions: enter your start address
</div>
<form class="short" onsubmit="R.maps.finishDrivingDirections(this, &#39;http://maps.google.com?daddr=500+Uptown+Ct+Ste+210%2C+Kirkland%2C+WA+98033%2C+Kirkland%2C+Washington&amp;mrt=loc&amp;t=m&amp;view=map&#39;); return false;">
<input id="address" name="address" style="width: 190px;" type="text" value="">
<button class="clicker_v2 clicker_v2--standard " style="width: 30px;" type="submit">Go</button>
</form>
</div>
</div>
</div>
</div>
<ul class="page_date_sidebar">
<li>
Event editors:
<a href="https://www.ravelry.com/people/SuchBullKnit" style="text-decoration: underline;">SuchBullKnit</a>
<a href="https://www.ravelry.com/people/ccb" style="text-decoration: underline;">ccb</a>
<a href="https://www.ravelry.com/people/jayasis" style="text-decoration: underline;">jayasis</a>
</li>
<li>
Page created: June 18, 2026
</li>
<li>
Last updated: June 18, 2026
</li>
</ul>
</div>
</div>
</body>
</html>
"""
