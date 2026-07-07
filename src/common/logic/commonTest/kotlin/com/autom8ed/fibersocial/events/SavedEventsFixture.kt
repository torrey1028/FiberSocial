package com.autom8ed.fibersocial.events

/**
 * Golden fixture: trimmed capture of the "My Saved Events" page
 * (`www.ravelry.com/events/saved`, 2026-07-03) with one saved event under a July 2026
 * month header. Note the listing has no time of day. Canonical copy:
 * `docs/samples/saved_events.html` — keep the two in sync.
 */
internal val SAVED_EVENTS_HTML = """
<!DOCTYPE html>
<!-- Trimmed capture of https://www.ravelry.com/events/saved (2026-07-03). Page chrome
     removed; the event_list block is verbatim. Note: entries carry a date (month header
     + day) but no time of day — start times require the event page. -->
<html>
<head><title>Ravelry: My Saved Events</title></head>
<body>
<div class="page_title">
<div class="page_title__supertitle">
<a href="https://www.ravelry.com/events">events</a>
</div>
My Saved Events
</div>
<div class="event_list" id="event_list">
<div class="month">
July 2026
</div>
<div class="event event__search_result parent_event">
<div class="date">
<div class="day">5th</div>
<div class="dow">Sunday</div>
</div>
<div class="event_photo">
</div>
<div class="details">
<a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10" class="title">Sunday Circle at Postdoc Brewing</a>
<div class="event__search_result__type">
<span class="rsp_only">

</span>
Knitting/crochet group
</div>
<div class="event__search_result__attendance">
<span class="people"><strong class="strong_non_zero">2</strong> people</span>
<span class="attending attendance_indicator">
I'm attending
</span>
</div>
</div>
<div class="details details_2">
<div class="venue">
Postdoc Brewing
<br>
7204 NE 175th St, Kenmore, WA 98028
<br>
Kenmore, WA, Washington
</div>
</div>
<div style="clear:both;" class="c_d"></div>
</div>
<div style="clear:both;" class="c_d"></div>

</div>


</div>
<div style="clear:both;" class="c_d"></div>

</body>
</html>
"""
