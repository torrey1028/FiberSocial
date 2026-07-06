# iOS physical-device verification checklist (#119)

The simulator can't exercise everything. Run these on a real iPhone once, after
installing via Xcode or TestFlight. Items marked ✅(sim) already passed on the
simulator during #118/#119 and just need a device re-run; 📱 items are
device-only.

## Setup
- [ ] Build to device from Xcode (needs a development team selected — see
      "TestFlight & signing" in `src/platform/ios/README.md`)
- [ ] Log in with Ravelry; browse feed/topics/events ✅(sim)

## Events & notifications (the Android live-test matrix)
- [ ] Notification permission prompt appears after login; Allow ✅(sim)
- [ ] RSVP to a future event → within seconds, Settings→Notifications shows the
      app has pending notifications (or use Xcode's console to see
      "reminders to schedule") ✅(sim)
- [ ] Un-RSVP → reminders cancel ("2 to cancel" in console) ✅(sim)
- [ ] 📱 Leave the app backgrounded across a reminder's fire time (T-24h or
      T-15m before an RSVP'd event) → the local notification fires with the
      shared copy ("Tomorrow" / "Starting in 15 minutes")
- [ ] Notification tap → opens the event detail screen ✅(sim, via test push)
- [ ] 📱 Background App Refresh: with the app backgrounded a few hours (device
      on Wi-Fi/charger helps iOS grant slots), a new event added to one of your
      groups produces a "New event in <group>" notification without opening
      the app. iOS decides the timing; don't expect the exact cadence.
- [ ] 📱 Force-quit the app → confirm background refresh stops (iOS behavior,
      matches the documented limitation) and resumes after next manual open
- [ ] New-event banner while the app is open (foreground presentation) —
      trigger: have another account add an event, then reopen the app

## Composers & uploads
- [ ] Attach image from your projects ✅(sim)
- [ ] Device photo upload: attach → Upload from device → pick a *camera* photo
      (HEIC) → verify the inserted image renders after posting (transcodes to
      JPEG on upload) — sim verified with a JPEG sample ✅(sim)
- [ ] Keyboard behavior in reply/new-topic composers (IME insets, no clipped
      send button)

## Look & feel
- [ ] App icon on the home screen ✅(sim)
- [ ] Light/dark theme + in-app override ✅(sim)
- [ ] Safe areas on the notch/Dynamic Island device (top bar, drawer, FABs)
- [ ] 📱 ProMotion smoothness on 120Hz devices (CADisableMinimumFrameDurationOnPhone is set)
