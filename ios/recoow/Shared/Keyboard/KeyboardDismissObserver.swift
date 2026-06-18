import SwiftUI
import UIKit

struct KeyboardDismissObserver: UIViewRepresentable {
    let onDismiss: () -> Void

    func makeUIView(context: Context) -> KeyboardDismissInstallingView {
        let view = KeyboardDismissInstallingView()
        view.onDismiss = onDismiss
        return view
    }

    func updateUIView(_ uiView: KeyboardDismissInstallingView, context: Context) {
        uiView.onDismiss = onDismiss
    }
}
