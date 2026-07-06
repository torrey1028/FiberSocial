# iOS

The iOS app shell (issue #117): a SwiftUI window hosting the shared Compose
Multiplatform UI from `src/compose`, built as the static `ComposeApp` framework.
All screens, ViewModels, parsers, and networking are shared Kotlin; this directory
contains only the Xcode project, the two-file Swift host, and configuration.

## Prerequisites

- Xcode 15+ with an iOS 16+ simulator runtime
- A JDK 17+ (the Xcode build phase probes `JAVA_HOME`, `/usr/libexec/java_home -v 17`,
  then homebrew's `openjdk@17`)

## Secrets

OAuth credentials follow the same pattern as Android's `local.properties`: create a
gitignored `Config.local.xcconfig` next to `Config.xcconfig` with

```
RAVELRY_CLIENT_ID = your-client-id
RAVELRY_CLIENT_SECRET = your-client-secret
```

Info.plist maps them to `RavelryClientId`/`RavelryClientSecret`, which the shared code
reads at startup. Without them the app builds and runs but login fails with
`invalid_client` (a warning is printed at launch).

## Building

Open `FiberSocial.xcodeproj` and run, or from the command line:

```
xcodebuild -project FiberSocial.xcodeproj -scheme FiberSocial \
  -destination 'platform=iOS Simulator,name=iPhone 16' build
```

The "Build ComposeApp framework" phase runs
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` in `src/platform/android`,
so the Kotlin side rebuilds automatically on every Xcode build.

## TestFlight & signing

Simulator builds need no signing. For a device or TestFlight build you need an
Apple Developer Program membership ($99/yr):

1. In Xcode: project → FiberSocial target → Signing & Capabilities → check
   "Automatically manage signing" and pick your Team. (The Background Modes
   capability is already configured via Info.plist.)
2. Bump `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` if this is a new release.
3. Product → Archive, then Distribute App → TestFlight & App Store Connect.
   First time: create the app record in App Store Connect with bundle id
   `com.autom8ed.fibersocial`.
4. Add yourself (and any testers) to the TestFlight internal group — internal
   testing needs no App Review.

Before shipping a build to testers, run through
`docs/ios-device-checklist.md` on a physical device — several notification
behaviors (real reminder fires, Background App Refresh grants) only exist
off-simulator.
