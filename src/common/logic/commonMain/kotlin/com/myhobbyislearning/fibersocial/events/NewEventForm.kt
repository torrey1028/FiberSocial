package com.myhobbyislearning.fibersocial.events

/** A selectable option in one of the new-event form's dropdowns: Ravelry's own opaque numeric ID paired with its display label. */
data class EventOption(val id: Long, val label: String)

/**
 * Everything needed to render and submit the "New Event" form for a group, scraped live
 * from `www.ravelry.com/events/new?group={groupId}` (see [NewEventFormParser]).
 *
 * Ravelry has no events API — this page is a moderator-only form, and the dropdown
 * option lists below are parsed fresh on every fetch rather than bundled as static
 * constants, so the app can't drift from whatever categories/countries Ravelry
 * currently offers.
 *
 * @property authenticityToken Rails CSRF token from this page's form, required by
 *   [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.createEvent].
 * @property creationId Server-issued draft ID from the form's hidden
 *   `event[creation_id]` field, echoed back on submission.
 * @property countries `event[country_id]` options (in-person events only).
 * @property onlineCategories `event[online_event_type_id]` options.
 * @property inPersonCategories `event[in_person_event_type_id]` options.
 * @property estimatedAttendanceOptions `event[estimated_attendance]` options (in-person only).
 * @property timezones `event[start_timezone]`/`event[end_timezone]` values (online events
 *   only); Rails `ActiveSupport::TimeZone` names, e.g. `"Pacific Time (US & Canada)"`.
 */
data class NewEventForm(
    val authenticityToken: String,
    val creationId: String,
    val countries: List<EventOption>,
    val onlineCategories: List<EventOption>,
    val inPersonCategories: List<EventOption>,
    val estimatedAttendanceOptions: List<EventOption>,
    val timezones: List<String>,
)

/** A state/region option for a chosen country, from `RavelryApiClient.getStatesForCountry`. */
data class EventState(val id: Long, val name: String)

/**
 * Fields for `RavelryApiClient.createEvent`, moderator-only (see [NewEventForm]).
 *
 * @property groupId Group the event belongs to ([com.myhobbyislearning.fibersocial.feed.models.Group.id]).
 * @property name Event title.
 * @property online `true` for an online event (needs [startTimezone]/[endTimezone]);
 *   `false` for in-person (needs [countryId]/[city]).
 * @property categoryId An [NewEventForm.onlineCategories]/[NewEventForm.inPersonCategories] ID, matching [online].
 * @property startDate `yyyy-MM-dd`.
 * @property startTime `hh:mm AM/PM`, e.g. `04:00 PM`.
 * @property endDate `yyyy-MM-dd`; optional.
 * @property endTime `hh:mm AM/PM`; optional.
 * @property startTimezone An [NewEventForm.timezones] value; online events only.
 * @property endTimezone An [NewEventForm.timezones] value; online events only.
 * @property countryId An [NewEventForm.countries] ID; required for in-person events.
 * @property stateId An [RavelryApiClient.getStatesForCountry] result ID; in-person only,
 *   when [countryId] has states.
 * @property city Required for in-person events.
 * @property venueName Required for in-person events, along with [address] — not just
 *   descriptive: Ravelry won't list an event in its group's "upcoming events" box
 *   without both, confirmed on-device (an in-person event created without them is a
 *   real, individually loadable page that never appears in that listing).
 * @property address Street address; required for in-person events alongside [venueName].
 * @property estimatedAttendance An [NewEventForm.estimatedAttendanceOptions] ID; in-person only.
 */
data class NewEventInput(
    val groupId: Long,
    val name: String,
    val online: Boolean,
    val categoryId: Long?,
    val startDate: String,
    val startTime: String,
    val endDate: String? = null,
    val endTime: String? = null,
    val description: String? = null,
    val url: String? = null,
    val editorList: String? = null,
    val startTimezone: String? = null,
    val endTimezone: String? = null,
    val countryId: Long? = null,
    val stateId: Long? = null,
    val city: String? = null,
    val venueName: String? = null,
    val address: String? = null,
    val estimatedAttendance: Long? = null,
)
