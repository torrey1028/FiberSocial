package com.autom8ed.fibersocial.events

/**
 * Golden fixture: trimmed capture of a real group page (Kirkland Fiber Arts Circle,
 * 2026-07-02) with 26 events in its `#upcoming_events` box, plus a decoy event link
 * outside the box to exercise selector scoping. Canonical copy:
 * `docs/samples/group_page_events.html` — keep the two in sync.
 */
internal val GROUP_PAGE_HTML = """
<!DOCTYPE html>
<!-- Trimmed capture of https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2 (2026-07-02).
     Page chrome removed; the #upcoming_events box is verbatim. A decoy event link outside
     the box exercises selector scoping. -->
<html>
<head><title>Ravelry: Kirkland Fiber Arts Circle</title></head>
<body>
<div class="page_title">Kirkland Fiber Arts Circle</div>
<div class="box" id="about">
<div class="box_contents">
We are a hyper-local group in Kirkland, WA. See also
<a href="https://www.ravelry.com/events/some-unrelated-event">this unrelated event link outside the box</a>.
</div>
</div>
<div class="box" id="upcoming_events" style="margin-top: 10px;">
<div class="box_title">
<img alt="" aria-hidden="true" class="inline icon_16 o-icon--events o-icon o-icon--xs" src="./Ravelry_ Kirkland Fiber Arts Circle_files/events.svg">
upcoming events
</div>
<div class="box_contents">
<div style="clear:both;" class="c_d"></div>
<div id="events" style="padding-left: 5px;">
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10">Sunday Circle at Postdoc Brewing</a>
</div>
<div class="when">
July  5, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10/people">1 person</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39">Wednesday HH at Chainline</a>
</div>
<div class="when">
July  8, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
July 11, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso/people">1 person</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-40">Wednesday HH at Chainline</a>
</div>
<div class="when">
July 15, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-40/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-6">Sunday Circle at KAC</a>
</div>
<div class="when">
July 19, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-6/people">1 person</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-41">Wednesday HH at Chainline</a>
</div>
<div class="when">
July 22, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-41/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-2">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
July 25, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-2/people">1 person</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-42">Wednesday HH at Chainline</a>
</div>
<div class="when">
July 29, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-42/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-11">Sunday Circle at Postdoc Brewing</a>
</div>
<div class="when">
August  2, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-11/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-43">Wednesday HH at Chainline</a>
</div>
<div class="when">
August  5, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-43/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-3">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
August  8, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-3/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-44">Wednesday HH at Chainline</a>
</div>
<div class="when">
August 12, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-44/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-7">Sunday Circle at KAC</a>
</div>
<div class="when">
August 16, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-7/people">1 person</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-45">Wednesday HH at Chainline</a>
</div>
<div class="when">
August 19, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-45/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-4">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
August 22, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-4/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-46">Wednesday HH at Chainline</a>
</div>
<div class="when">
August 26, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-46/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-12">Sunday Circle at Postdoc Brewing</a>
</div>
<div class="when">
August 30, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-12/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-47">Wednesday HH at Chainline</a>
</div>
<div class="when">
September  2, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-47/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-5">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
September  5, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-5/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-48">Wednesday HH at Chainline</a>
</div>
<div class="when">
September  9, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-48/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-13">Sunday Circle at Postdoc Brewing</a>
</div>
<div class="when">
September 13, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-13/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-49">Wednesday HH at Chainline</a>
</div>
<div class="when">
September 16, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-49/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-6">Saturday Morning Coffee at Diva Espresso</a>
</div>
<div class="when">
September 19, 2026
@
 9:30 AM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/saturday-morning-coffee-at-diva-espresso-6/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-50">Wednesday HH at Chainline</a>
</div>
<div class="when">
September 23, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-50/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-8">Sunday Circle at KAC</a>
</div>
<div class="when">
September 27, 2026
@
 1:00 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/sunday-circle-at-kac-8/people">0 people</a>
</div>
</div>
<div class="event" style="margin-bottom: 1em;">
<div class="what" style="font-size: 1.1em;">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-51">Wednesday HH at Chainline</a>
</div>
<div class="when">
September 30, 2026
@
 5:30 PM
</div>
<div class="who">
<a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-51/people">0 people</a>
</div>
</div>
</div>
</div>
</div>
</body>
</html>
"""
