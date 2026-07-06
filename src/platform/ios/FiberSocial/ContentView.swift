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
            // Compose applies its own IME insets; without this the keyboard would
            // squash the Compose hierarchy a second time.
            .ignoresSafeArea(.keyboard)
    }
}
