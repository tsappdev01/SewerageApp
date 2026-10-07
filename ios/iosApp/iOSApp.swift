import SwiftUI
import ComposeApp

// DIP Field Service on iPhone and iPad. Everything on screen is the shared Kotlin app
// (android/app/src/commonMain); this file only hosts it.
@main
struct IOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
