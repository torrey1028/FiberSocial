# iOS debug builds without a Mac

`.github/workflows/ios-debug-build.yml` builds a Debug-configuration,
Ad Hoc-signed IPA on a GitHub Actions macOS runner and publishes it as an
over-the-air (OTA) install link via GitHub Pages, so you can get a fresh
build onto a real iPhone straight from a Linux/WSL machine with no Mac and
no cable.

This is a developer convenience tool, separate from the release pipeline
(`release-ios.yml`, `docs/ios-device-checklist.md`) — it has no automated
tests, no QA gate, and isn't meant for distributing anything beyond your own
registered device(s).

## Requesting a build

```bash
gh workflow run ios-debug-build.yml --repo torrey1028/FiberSocial --ref <branch>
```

Or use the "Run workflow" button on the workflow's Actions page and pick the
branch there. It's `workflow_dispatch`-only (no automatic trigger) since
every run costs macOS runner time, the most expensive tier.

When it finishes, open **`https://torrey1028.github.io/FiberSocial/ios-debug/`**
in **Safari on the iPhone** (the `itms-services://` install link only works
from Mobile Safari — Chrome or any in-app browser won't trigger it) and tap
Install. The run's job summary links there too.

## One-time setup

Two things beyond what `release-ios.yml` already has:

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
**Apple Distribution** certificate (the same one `release-ios.yml` already
uses — Ad Hoc profiles are signed with a Distribution cert, just scoped to
specific devices, so no new certificate is needed) → select the device(s)
registered in step 1 → generate and download the `.mobileprovision` file.

Base64 it and add as a repo secret, same pattern as the App Store profile:

```bash
base64 -i AdHoc.mobileprovision | tr -d '\n' | pbcopy   # or xclip/clip.exe on Linux/WSL
```

Add the result as the `IOS_ADHOC_PROVISIONING_PROFILE_BASE64` repo secret
(Settings → Secrets and variables → Actions).

No new secrets needed beyond that — signing certificate, `APPLE_TEAM_ID`, and
the Ravelry OAuth credentials are all reused from `release-ios.yml`'s
existing setup.

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
