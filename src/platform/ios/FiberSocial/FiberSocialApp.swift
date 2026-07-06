import SwiftUI
import ComposeApp

/// Launch-time wiring that must happen before the app finishes launching:
/// BGTaskScheduler handler registration and the notification-tap delegate
/// (a cold-start tap is delivered right after launch). Logic lives in Kotlin.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        AppSetupKt.onAppLaunch()
        return true
    }
}

@main
struct FiberSocialApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
