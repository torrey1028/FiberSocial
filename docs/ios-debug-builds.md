# iOS device builds without a Mac

`.github/workflows/ios-debug-build.yml` builds an Ad Hoc-signed IPA on a
GitHub Actions macOS runner and publishes it as an over-the-air (OTA) install
link via GitHub Pages, so you can get a fresh build onto a real iPhone
straight from a Linux/WSL machine with no Mac and no cable.

It builds **Debug** by default and can build **Release** on request (see
"Choosing a configuration" below).

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

When it finishes, open **`https://torrey1028.github.io/FiberSocial/ios-debug/`**
in **Safari on the iPhone** (the `itms-services://` install link only works
from Mobile Safari — Chrome or any in-app browser won't trigger it) and tap
Install. The run's job summary links there too.

## Choosing a configuration

**Debug** (the default) is the everyday choice: faster to build, and it has
every feature the code has.

**Release** matters when a feature is *compile-time gated*. `FeatureFlags`'s
iOS implementation is `Platform.isDebugBinary`, so anything behind a flag —
today, Messages (#415) — is present in a Debug build and **absent in a
Release build**. A Debug OTA build therefore can't tell you what App Store
users will actually see; a Release one can. It costs extra runner time
(Kotlin/Native builds a release framework, which is much slower than the
debug one), so it isn't the default.

It also matters for anything gated on `DebugFlags.debugToolsAvailable` — the
login web view's "Share log" button, the Settings "Debug panel" row. A Debug
IPA shows those, so it can't confirm what a release user sees in their place.

That cuts both ways, and is the reason to stay on `Debug` unless you need
otherwise: the log export is the only way to get a trace off an OTA-installed
iPhone, and a Release build doesn't have it.

Note the install page and the OTA link are the same URL either way — a new
build replaces the previous one, whatever its configuration. The page says
which configuration it is.

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

Certificates, IDs & Profiles → **Profiles** → **+** → **Ad Hoc** →
select the `com.myhobbyislearning.fibersocial` App ID → select the existing
**Apple Distribution** certificate (the same one `release.yml`'s
`ios-release` job already uses — Ad Hoc profiles are signed with a
Distribution cert, just scoped to specific devices, so no new certificate is
needed) → select the device(s) registered in step 1 → generate and download
the `.mobileprovision` file.

Base64 it and add as a repo secret, same pattern as the App Store profile:

```bash
base64 -i AdHoc.mobileprovision | tr -d '\n' | pbcopy   # or xclip/clip.exe on Linux/WSL
```

Add the result as the `IOS_ADHOC_PROVISIONING_PROFILE_BASE64` repo secret
(Settings → Secrets and variables → Actions).

No new secrets needed beyond that — signing certificate, `APPLE_TEAM_ID`, and
the Ravelry OAuth credentials are all reused from `release.yml`'s
`ios-release` job's existing setup.

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
  that your Apple ID's device trust hasn't lapsed, and that the app isn't
  already installed from a *different* signing source (App Store/TestFlight)
  — remove the existing app first if so, since Ad Hoc and App Store builds
  aren't interchangeable/upgradable, same as Android's separate Play Store vs
  direct-APK channels.
- **First-time device trust:** after installing, iOS may require Settings →
  General → VPN & Device Management → trust the developer certificate before
  the app will launch.
