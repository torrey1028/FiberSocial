import SwiftUI
import ComposeApp

/// Hosts the shared Compose UI. All app logic lives in the ComposeApp framework
/// (src/compose); this shell only provides the window.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Compose owns every inset, so the host must hand it the full screen —
            // the iOS counterpart of MainActivity's enableEdgeToEdge(). Material3's
            // Scaffold/TopAppBar pad for the status bar and home indicator
            // themselves, and Compose applies its own IME insets. Respecting the
            // container safe area here made SwiftUI push the Compose view below
            // the status bar while the TopAppBar padded for it again, stacking a
            // blank status-bar-sized band above the top bar (issue #433). The
            // full ignore also covers the keyboard region, which previously had
            // to be ignored on its own to stop the keyboard squashing the
            // hierarchy a second time.
            .ignoresSafeArea()
    }
}
