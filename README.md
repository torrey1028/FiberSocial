# FiberSocial

App for community connection/organization for Ravelry users.

[Privacy Policy](https://torrey1028.github.io/FiberSocial/) · unofficial, not affiliated with Ravelry.

## Become a tester

FiberSocial is in testing on both platforms. To get access:

1. **Sign up**: **[Sign up to test FiberSocial](https://forms.gle/FrQp4SMwbSVEj76o9)** — one form works for either iPhone or Android.
2. Once you're added to the tester list, follow the platform-specific steps below (**Join the Android closed test**, or **Install (iOS — TestFlight)**).

Android also has a direct APK download that doesn't require joining the closed test — see **Download & install (Android)** below if you'd rather sideload.

## Join the Android closed test (Play Store)

1. Sign up above if you haven't already — you need to be added to the tester list before the link below works.
2. Once you're added, open the listing on your Android device and tap **Become a tester**: **[FiberSocial on Google Play](https://play.google.com/store/apps/details?id=com.myhobbyislearning.fibersocial)**.
3. Install from the Play Store as normal. Updates arrive through the Play Store automatically from then on.

## Download & install (Android)

The most recent release is always available here:

**[⬇ Download the latest release APK](https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk)**

Releases are cut deliberately (a maintainer tags a version), so this link points at the newest *released* version rather than the newest commit.

This is a direct APK download, not a Play Store listing, so Android will warn you about installing from an unknown source — that's expected.

**This is a separate install from the Play Store closed test above** — Play re-signs uploads with its own signing key (Play App Signing), so it doesn't match this APK's signature. You can't upgrade between the two channels in place; switching means uninstalling first.

### Install steps

1. Open the download link above on your Android phone (or download it on a computer and transfer the `.apk` file to the phone).
2. Tap the downloaded `app-release.apk` file to start installing.
3. If prompted, allow installs from this source: **Settings > Apps > Special app access > Install unknown apps**, then enable it for the app you used to download the file (e.g. your browser or file manager).
4. Tap **Install**, then open FiberSocial and log in with your Ravelry account.

### Updating to a newer build

Downloading and installing the link again upgrades the app in place — no need to uninstall first.

### Troubleshooting

| Problem | Fix |
|---|---|
| `App not installed` / `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | You likely have a debug build installed from source (signed differently than the release build). Uninstall the existing app first, then install the downloaded APK. |
| Android blocks the install | Enable **Install unknown apps** for the app you used to open the `.apk` (see step 3 above), then retry. |
| App crashes on launch after an update | Uncommon, but if it happens: **Settings > Apps > FiberSocial > Storage > Clear storage**, then log in again. |

## Install (iOS — TestFlight)

The iOS app is distributed through **TestFlight** (Apple's beta-testing app),
not the App Store. To try it:

1. Sign up in **Become a tester** above, if you haven't already — it collects
   the Apple ID email your invite is sent to.
2. Install **[TestFlight](https://apps.apple.com/app/testflight/id899247664)**
   from the App Store on your iPhone or iPad.
3. Once you're added you'll get an email with a redeem link — open it, tap
   **Accept**, then **Install** in TestFlight, and log in with your Ravelry
   account.

All you need is an iPhone or iPad, a **free Apple ID**, and the TestFlight app —
no paid developer account. Newer builds show up in TestFlight automatically.

## Building from source

See [`src/README.md`](src/README.md) for build/test instructions per platform.
