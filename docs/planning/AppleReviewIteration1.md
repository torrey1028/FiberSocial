# Apple Review — Iteration 1 Fix Plan

Apple rejected **FiberSocial iOS 1.0 (0.1.0 build 2)** on 2026-07-22 (submission
`8479a207-bf5c-44a3-8768-0e72f36bd515`, reviewed on iPad Air 11" M3, iPadOS 26.5.2)
with three issues. This document plans the fix for each, plus the resubmission
logistics.

---

## Item 1 — Guideline 2.1(a): crash tapping "Take Photo or Video"

### What Apple saw
The app crashed when the reviewer tapped **Take Photo or Video** while attaching an
image to a post. Crash logs are attached in App Store Connect.

### Diagnosis
FiberSocial has **no camera feature of its own** — "Take Picture" is still open
wishlist issue #245, and the iOS image flow is `PHPickerViewController` only
(`ImagePicker.ios.kt`). The button the reviewer tapped is the **camera affordance
built into the system photo-picker sheet**. Unlike the picker itself (which runs out
of process and needs no permission), that camera runs **in the app's process** and
requires `NSCameraUsageDescription` — and `Info.plist` currently contains **no
privacy usage-description keys at all**. iOS terminates an app instantly (TCC
privacy-violation kill) when it touches the camera without that key. This exactly
matches the reviewer's steps and is 100% reproducible.

### Fix — PR 1: `fix/ios-camera-usage-descriptions` (issue #407)
1. Add to `src/platform/ios/FiberSocial/Info.plist`:
   - `NSCameraUsageDescription` — e.g. "FiberSocial uses the camera when you choose
     to take a photo to attach to a post."
   - `NSMicrophoneUsageDescription` — the "or Video" half of the picker's capture UI
     can engage the mic; covers the same crash class.
2. Verify on a **real device** (the simulator has no camera): open a composer →
   attach image → Take Photo or Video → shot flows back through `PickerDelegate`
   and uploads. Test on iPad if available, since that was the review device.
3. Optional confirmation: download the attached crash log from App Store Connect —
   a TCC kill is identifiable from the termination-reason string without
   symbolication. The plist fix is warranted regardless.

Scope note: this makes the *system picker's* camera work. Building a first-class
in-app "Take Picture" flow remains issue #245 and is not needed for resubmission.

---

## Item 2 — Guideline 2.3.6: age rating missing "Messaging and Chat"

### What Apple wants
The Age Rating questionnaire must answer **Yes** to "Messaging and Chat" because the
app now has direct messages.

### Fix — App Store Connect only (no code, done by Becky)
App Store Connect → FiberSocial → **App Information → Age Rating** → set
"Messaging and Chat" to **Yes**. This may raise the displayed age rating; that is
expected and fine. No new binary is required for this item by itself.

---

## Item 3 — Guideline 1.2: user-generated content safeguards

Apple requires three concrete mechanisms, then proof (see Resubmission below):

1. Users must agree to terms (EULA) **before registering or logging in**, and the
   terms must state zero tolerance for objectionable content and abusive users.
2. A mechanism to **flag objectionable content**.
3. A mechanism to **block abusive users** — blocking must also notify the developer
   of the inappropriate content and remove it from the user's feed **instantly**.

### 3a. Terms-of-use gate — PR 2: `feat/terms-gate` (issue #408)

**Why the terms are legitimate despite not owning Ravelry's content/moderation:**
The EULA licenses *the app*, not the service — "you may use FiberSocial on
condition you don't use it to post objectionable content or harass people." Apple's
guideline 1.2 is a policy-display requirement (users must *agree to terms
containing zero-tolerance language*), not a litigation requirement. Real
enforcement is Ravelry's, and the terms say so explicitly: every FiberSocial user
already has a Ravelry account and has agreed to Ravelry's Terms and Community
Guidelines; Ravelry's moderators/staff remove content and restrict or close
accounts. This is the standard, Apple-accepted shape for third-party clients
(e.g. Reddit clients). Apple's default EULA doesn't contain the zero-tolerance
language or pre-login flow the reviewer demanded, hence a custom terms page.

**Terms document structure (`legal/terms-of-use.html`):**
1. License to use the app, conditioned on acceptable behavior.
2. Zero-tolerance statement — no tolerance for objectionable content or abusive
   users (Apple's required language).
3. Content is provided by Ravelry; users must also comply with Ravelry's Terms and
   Community Guidelines; Ravelry moderates and may remove content or accounts.
   Includes the existing non-affiliation statement.
4. Pointers to the in-app report (#409) and block (#410) mechanisms.
5. No-warranty / as-is boilerplate for free software.

**Implementation:**
- New pre-login **Terms screen** shown before `LoginScreen`'s "Log in with Ravelry"
  can proceed. Contains a summary with explicit zero-tolerance language for
  objectionable content and abusive users, a link to the full hosted terms, and an
  **Agree and continue** button.
- Acceptance persisted via `KeyValueStore` (plain settings store — not the
  encrypted token store) so the gate shows once, and again only if the terms
  version changes.
- Full terms hosted as `legal/terms-of-use.html` on GitHub Pages, next to the
  existing `privacy-policy.html` and `child-safety-standards.html`. Linked from the
  gate and from the About screen.
- Both platforms get this for free (common Compose), but the Apple recording only
  needs iOS.

### 3b. Flag objectionable content — PR 3: `feat/report-post` (issue #409)

**What Ravelry actually offers (investigated 2026-07-26):**
- **JSON API: nothing.** No flag/report endpoint exists (verified against the
  archived full API documentation in `docs/api/`). Closest adjacencies: only
  *moderators* can set `locked`/`archived` on topics, and the `Post` model carries
  a `deleted` flag — so removals performed by Ravelry are visible to the app.
- **Website: a real per-post flag pipeline.** Every forum post has a
  **"report a post" red flag** (also reachable via the menu by the poster's name).
  Reports go privately to the **group's volunteer moderators**; flags carry an
  **"Escalate to Ravelry staff"** option for issues beyond (or about) group
  moderation. There is also a dedicated Community Guidelines violation contact
  form (`ravelry.com/contact?question=i_want_to_report_a_violation_of_the_ravelry_community_guidelines`).
  Enforcement is real: violations get accounts restricted or removed.
- Ravelry has **no user-level block** anywhere (web "blocking" help results are
  about blocking knitwear) — consistent with the DM-epic findings; that's why
  #410 is client-side.

**Design consequence:** reporting is *not* client-side-only — it rides Ravelry's
real moderation pipeline, and content removal is Ravelry's job (a much stronger
story for Apple than anything local). The only client-side "removal" in the whole
plan is the instant-hide on block (#410), which Apple requires to be local and
instant anyway.

**Implementation:**
- A **Report** action on posts (overflow menu on feed/topic posts).
- Transport: **Ravelry's post-flag form via the web protocol** (same
  session-cookie + CSRF scraping approach as event creation, PR #331). First step
  is the usual protocol investigation: the flag form's URL shape, its fields
  (reason/category picker, staff-escalation option), CSRF token, success
  detection. Feasibility rated high given the event-form precedent.
- Also surfaced: the existing pre-addressed **developer email channel** (already
  used for child-safety reports) as a secondary path, so a report reaches the
  developer too.
- Fallback tiers if the protocol investigation hits a wall: (1) in-app web flag as
  above → (2) deep-link the user to the flag/contact page in a browser → (3)
  report-by-email alone. Apple's wording ("a mechanism for users to flag
  objectionable content") does not require the backend to be ours.

### 3c. Block abusive users — PR 4: `feat/block-users` (issue #410)
- Ravelry has **no block/mute API** (confirmed during the DM epic, #365), so
  blocking is client-side:
  - Local **blocked-users list** in `KeyValueStore`.
  - **Block** action on posts (overflow) and on user profiles.
  - Blocked users' posts and message threads are filtered out **immediately on
    block** — reactive state, not next-refresh — everywhere content renders (feed,
    topic detail, messages list, notifications).
  - **Manage/unblock** list in Settings.
- **Developer notification**: the block flow includes a report step. Plan: on
  block, offer/compose a pre-addressed email to the developer containing the
  offending content reference. Two constraints shape this:
  - The About screen truthfully states "Nothing you do in FiberSocial is sent to
    the developer." A user-sent email keeps that true; an auto-sent report would
    require rewording that disclosure (and building a receiving endpoint).
  - Project rule: the app never posts/sends on the user's behalf — drafts only.
  - Start with the email approach; revisit only if Apple pushes back.

---

## Resubmission logistics (after PRs 1–4 merge)

1. Bump version/build: cut a new tag via `scripts/release.sh` — `release.yml`'s
   `ios-release` job archives and uploads to App Store Connect automatically.
2. In App Store Connect (Becky):
   - Flip the Age Rating answer (Item 2).
   - Select the new build for the 1.0 submission.
   - Record a **screen recording on a physical device** demonstrating:
     (a) the terms/EULA gate before login, (b) flagging a post, (c) blocking a
     user (and their content vanishing). Attach it in the **Notes field of App
     Review Information**.
   - Reply to Apple's message noting the fixes, and resubmit.

## Sequencing

| Order | Work | Why this order |
|---|---|---|
| 1 | PR 1 — Info.plist usage descriptions (#407) | Tiny, independent, unblocks device testing |
| 2 | PR 2 — terms gate + hosted terms (#408) | Independent of 3b/3c; UX-visible, eyeball on device |
| 3 | PR 3 — report post (#409) | Needs Ravelry flag-protocol investigation |
| 4 | PR 4 — block users (#410) | Largest; builds on report channel for its notify step |
| 5 | Resubmission | After all merge; ASC steps + recording are Becky's |

Per the "device-test UX early" convention, each UX-visible PR (2–4) should be
deployed and eyeballed before stacking the next on top of it.
