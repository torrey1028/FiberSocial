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
3. Product → Archive, then Distribute App → TestFlight & App Store Connect
   (or "TestFlight Internal Only"). First time: create the app record in App
   Store Connect with bundle id `com.myhobbyislearning.fibersocial`.
4. Add yourself (and any testers) to the TestFlight internal group — internal
   testing needs no App Review.

### Signing gotchas (first-archive)

Automatic signing needs **at least one registered device** on the team to
generate the archive's provisioning profile — a fresh paid account with zero
devices fails with *"Your team has no devices from which to generate a
provisioning profile."* App Store distribution itself is device-free (the App
Store profile has no device list); it's only the archive build that wants a
device-backed development profile. Register any iOS device under
developer.apple.com → Devices to unblock it. (Maintainers with no iOS device
have registered a placeholder well-formed 40-hex-char UDID; a malformed `000…`
string is rejected.)

The optimized release framework link needs more heap than debug builds — see
`org.gradle.jvmargs` in `src/platform/android/gradle.properties` (raised to
`-Xmx4096m`) if you hit `OutOfMemoryError: Java heap space` while archiving.

Two `Info.plist` keys keep the upload clean: `UISupportedInterfaceOrientations`
must list all four orientations (iPad multitasking), and
`ITSAppUsesNonExemptEncryption=false` declares the app's HTTPS-only encryption
as exempt so uploads skip the "Missing Compliance" prompt.

## TestFlight testers

Anyone can test with a **free Apple ID** and the **TestFlight app** — no paid
Developer account required (that's only for whoever uploads). Every build goes
to App Store Connect first; "internal" vs "external" is chosen there.

Prospective testers sign up via the shared cross-platform form (root
`README.md`'s **Become a tester** section):
<https://forms.gle/FrQp4SMwbSVEj76o9> — it collects the Apple ID email needed
to send the invite.

- **Internal** (no App Review, ≤100): testers must be members of the App Store
  Connect team. Add each Apple ID from the form responses under **Users and
  Access**, then add them to the Internal Testing group. Trade-off: internal
  testers get a role (some visibility) in the App Store Connect account.
  Available minutes after a build finishes processing.
- **External** (public self-serve link, ≤10,000): testers need only a free
  Apple ID and get no account access. Add by email or share a public link; the
  **first build requires a one-time beta App Review** (lighter than full
  review, but it can surface third-party-content / "app-likeness" concerns).

Testers can be **added/removed freely at any time** — no review, no cost, no
annual lock (unlike device registration). Removing frees the slot immediately;
the 100 internal cap is concurrent, not cumulative.

**Contacting testers:** App Store Connect has no "email your testers" tool —
only Apple's own invite / build-available notifications go out. To reach
testers directly, use the emails collected via the sign-up form and send from
your own account (especially external public-link testers, whose emails you
otherwise never see).

**Testing the delivery flow needs a real device:** the TestFlight app does not
run on the iOS Simulator, so the invite→install→run experience can only be
verified on a physical iPhone/iPad. The app itself is fully exercisable in the
simulator; only the TestFlight install path is device-only.

Before shipping a build to testers, run through
`docs/ios-device-checklist.md` on a physical device — several notification
behaviors (real reminder fires, Background App Refresh grants) only exist
off-simulator.
