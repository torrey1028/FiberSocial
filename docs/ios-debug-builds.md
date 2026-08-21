# iOS debug builds without a Mac

`.github/workflows/ios-debug-build.yml` builds an Ad Hoc-signed IPA on a
GitHub Actions macOS runner and publishes it as an over-the-air (OTA) install
link via GitHub Pages, so you can get a fresh build onto a real iPhone
straight from a Linux/WSL machine with no Mac and no cable.

This is a developer convenience tool, separate from the release pipeline
(`release.yml`'s `ios-release` job, `docs/ios-device-checklist.md`) — it has
no automated tests, no QA gate, and isn't meant for distributing anything
beyond your own registered device(s).

## Requesting a build

```bash
# Debug configuration (the default)
gh workflow run ios-debug-build.yml --repo torrey1028/FiberSocial --ref <branch>

# Release configuration
gh workflow run ios-debug-build.yml --repo torrey1028/FiberSocial --ref <branch> \
  -f configuration=Release
```

Or use the "Run workflow" button on the workflow's Actions page and pick the
branch and configuration there. It's `workflow_dispatch`-only (no automatic
trigger) since every run costs macOS runner time, the most expensive tier.

### Which configuration?

**Reach for `Release` whenever what you're checking differs between build
types** — otherwise the build cannot show you the thing you're trying to
verify:

- Anything gated on `DebugFlags.debugToolsAvailable` — the login web view's
  "Share log" button, the Settings "Debug panel" row. A Debug IPA shows those,
  so it can't confirm what a release user sees in their place.
- Anything gated out by `FeatureFlags` (e.g. `messagesEnabled`), which is
  compiled out of release builds entirely.

`Debug` is still the default, and is the right pick when you want those tools —
above all the login web view's log export, which is the only way to get a trace
off an OTA-installed iPhone.

Either way it's an Ad Hoc build for your own registered devices, not a release
artifact: no tests, no QA gate, and it never reaches TestFlight or the store.

The two configurations also differ in **whether they can coexist with the App
Store app**. The Debug configuration builds bundle id
`com.myhobbyislearning.fibersocial.debug` under the name "FiberSocial Debug"
with the dark app icon, so it installs as its own app beside the store one, with
its own container, keychain items and notification state — nothing is shared,
and you log in separately. The Release configuration keeps the real bundle id on
purpose (it exists to reproduce exactly what ships), so installing it *replaces*
an App Store install of FiberSocial — and, being Ad Hoc-signed rather than store-
signed, it can't upgrade it in place: you have to delete the App Store app first
and reinstall it from the store afterwards.

When it finishes, open **`https://torrey1028.github.io/FiberSocial/ios-debug/`**
in **Safari on the iPhone** (the `itms-services://` install link only works
from Mobile Safari — Chrome or any in-app browser won't trigger it) and tap
Install. The run's job summary links there too.

## One-time setup

Two things beyond what `release.yml`'s `ios-release` job already has:

### 1. Register your iPhone's UDID

You need the device's UDID (identifier), and getting it doesn't require a
Mac:

- **Windows/WSL path (recommended):** install the **Apple Devices** app from
  the Microsoft Store on the Windows host underneath WSL, plug the iPhone in
  via USB, trust the computer on the phone when prompted, and the app's
  device summary page shows the Serial Number/Identifier.
- **Phone-only alternative:** a UDID-capture web profile (several third-party
  services offer this — search "get iOS UDID" and use one you trust; it
  installs a small configuration profile that reveals the UDID, which you
  then remove). Prefer the Apple Devices app above if possible, since it
  doesn't involve a third-party service seeing your device identifier.

Then register it: [developer.apple.com](https://developer.apple.com) →
Certificates, IDs & Profiles → **Devices** → **+** → paste in the UDID and a
name for the device.

### 2. Create an Ad Hoc provisioning profile and add it as a secret

A profile covers one App ID, and the two configurations build two different
bundle ids (`…fibersocial.debug` for Debug, `…fibersocial` for Release — see
"Which configuration?" above). **The simplest setup is one wildcard profile
that covers both.**

Certificates, IDs & Profiles → **Identifiers** → **+** → App IDs → choose
**Wildcard** and register `com.myhobbyislearning.*` (skip this if you'd rather
keep explicit App IDs — see the two-profile note below). Then **Profiles** →
**+** → **Ad Hoc** → select that App ID → select the existing **Apple
Distribution** certificate (the same one `release.yml`'s `ios-release` job
already uses — Ad Hoc profiles are signed with a Distribution cert, just scoped
to specific devices, so no new certificate is needed) → select the device(s)
registered in step 1 → generate and download the `.mobileprovision` file.

Base64 it and add as a repo secret, same pattern as the App Store profile:

```bash
base64 -i AdHoc.mobileprovision | tr -d '\n' | pbcopy   # or xclip/clip.exe on Linux/WSL
```

Add the result as the `IOS_ADHOC_PROVISIONING_PROFILE_BASE64` repo secret
(Settings → Secrets and variables → Actions).

**Two explicit profiles instead of one wildcard:** register a
`com.myhobbyislearning.fibersocial.debug` App ID alongside the existing
`com.myhobbyislearning.fibersocial` one, make an Ad Hoc profile for each, and
put the second in the optional `IOS_ADHOC_DEBUG_PROVISIONING_PROFILE_BASE64`
repo secret. The workflow installs whichever profiles it's given and picks the
one covering the bundle id it's building — an exact App ID match first, then a
wildcard — and fails with the list of what it found if neither covers it.

No other new secrets — signing certificate, `APPLE_TEAM_ID`, and the Ravelry
OAuth credentials are all reused from `release.yml`'s `ios-release` job's
existing setup.

## What's public about this

`app.ipa`/`manifest.plist`/`index.html` under `/ios-debug/` sit on this
repo's public GitHub Pages site with no access control — Ad Hoc's
device-registration requirement only gates *installing* the app, not
*downloading* the IPA file itself. Two things that means for anyone who
finds the URL (this repo is public, and the URL is also posted in every
run's public Actions job summary):

- The IPA has the live `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` baked in
  (same as every other signed build — see root `CLAUDE.md`'s "Residual,
  accepted exposure" note, which already treats this secret as extractable
  from any shipped binary), but this is now a **permanent, always-current**
  unauthenticated download, sourced from whatever branch was last dispatched
  — not the transient (7-day) main-only CI artifact or the Apple-ID-gated
  TestFlight channel the other distribution paths use.
- The embedded Ad Hoc `.mobileprovision`'s `ProvisionedDevices` list —
  i.e. your registered iPhone's UDID — is extractable from the downloaded
  IPA the same way.

Rotate the Ravelry secret (if Ravelry ever supports it) or re-provision a
new device if either of those is a real concern; this workflow doesn't do
anything to reduce that exposure on its own.

## Troubleshooting

- **Nothing happens when tapping Install, or "Cannot Connect to
  FiberSocial":** almost always means the device's UDID isn't actually in
  the Ad Hoc profile the build was signed with — double check the profile
  includes the right device, regenerate it if you registered the device
  *after* creating the profile (profiles don't auto-update), and re-encode/
  re-upload the secret.
- **The link does nothing at all:** confirm you're using Safari, not another
  browser or an in-app link preview (Messages/Slack previews often open
  links in an embedded browser that won't honor `itms-services://`).
- **"Unable to install" after tapping Install and waiting a moment:** check
  that your Apple ID's device trust hasn't lapsed, and — on a **Release**
  build, which keeps the store bundle id — that the app isn't already
  installed from a *different* signing source (App Store/TestFlight). Remove
  the existing app first if so, since Ad Hoc and App Store builds aren't
  interchangeable/upgradable, same as Android's separate Play Store vs
  direct-APK channels. A **Debug** build has its own bundle id, so it doesn't
  hit this; if it does, something else already occupies
  `com.myhobbyislearning.fibersocial.debug`.
- **The build fails at "Install Ad Hoc provisioning profile" saying no profile
  covers the bundle id:** the profile in the secret is for the other App ID.
  Its error message lists the App IDs it did find — see step 2 above for the
  wildcard-profile or second-secret fix.
- **First-time device trust:** after installing, iOS may require Settings →
  General → VPN & Device Management → trust the developer certificate before
  the app will launch.
