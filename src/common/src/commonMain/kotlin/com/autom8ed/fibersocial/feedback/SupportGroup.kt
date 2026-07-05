package com.autom8ed.fibersocial.feedback

/**
 * The Ravelry group in-app feedback is filed to: [FeedbackViewModel] posts feedback as a
 * topic in this group's forum. Settings offers a "Send feedback" entry point that links out
 * to the group when posting requires membership — native one-tap join is a follow-up (PR A).
 *
 * Hardcoded rather than resolved by search on purpose: feedback targets one fixed, known
 * group, and Ravelry's `groups/search.json` doesn't index a freshly created group for a
 * while — so a runtime lookup can transiently miss it. Values captured 2026-07-05 from
 * https://www.ravelry.com/groups/fibersocial-app-support (cross-checked against the app's
 * own `getGroup()` resolution once the group was indexed).
 */
object SupportGroup {
    const val PERMALINK = "fibersocial-app-support"

    /** `forum_id` that feedback topics are created in. */
    const val FORUM_ID = 50803L

    const val GROUP_ID = 50702L
}
