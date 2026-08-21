# Android release-candidate QA checklist

Run this against the release-candidate build (the Play Console testing track,
or the prerelease APK attached to the tag's GitHub Release) before approving
the `android-release` environment on that tag's `release-android.yml` run.
Automated tests already ran as part of building the candidate — this is what
they can't cover: does it actually behave right on a device.

## Setup
- [ ] Install the release-candidate build (Play testing track install, or
      sideload the prerelease APK — `adb install -d` if it needs to go over an
      existing debug/older build)
- [ ] Fresh login with Ravelry works; existing session survives a relaunch

## Core browsing
- [ ] Feed loads and scrolls; post previews render (images, embedded content)
- [ ] Open a topic; posts render fully, reply composer works
- [ ] Groups list and a group's events/forum load

## Messages
- [ ] Conversation list loads and shows unread state correctly
- [ ] Open a conversation, send a reply, mark-as-read updates
- [ ] Compose a new message and send it

## Notifications
- [ ] New-post/reply notification arrives and tapping it opens the right topic
- [ ] Notification settings toggles (e.g. "New messages") take effect
- [ ] With "New posts in groups" on, the bell FAB appears on a group's feed;
      subscribing to a busy group produces a "New posts in <group>" notification
      on the next sync, and tapping it selects that group. Turning the setting
      off hides the bell again.

## Anything changed since the last release
- [ ] Skim the GitHub Release's auto-generated notes for this tag and spot
      check each listed change/fix actually works as described

## Look & feel
- [ ] App icon, light/dark theme (and in-app override) look right
- [ ] No obvious layout breakage on a phone and a tablet/large-screen size, if
      available
