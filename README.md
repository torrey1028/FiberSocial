# FiberSocial

App for community connection/organization for Ravelry users.

## Download & install (Android)

The latest signed build is always available here:

**[⬇ Download the latest APK](https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk)**

This is a direct APK download, not a Play Store listing, so Android will warn you about installing from an unknown source — that's expected.

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

## Building from source

See [`src/README.md`](src/README.md) for build/test instructions per platform.
