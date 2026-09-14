import SwiftUI

struct DecisionSceneView: UIViewRepresentable {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.sizeCategory) private var sizeCategory
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let controller: DecisionSceneController
    let isActive: Bool
    let onDraw: (DecisionSpinInput) -> Void

    func makeUIView(context: Context) -> DecisionSceneSurface {
        DecisionSceneSurface(controller: controller, onDraw: onDraw)
    }

    func updateUIView(_ view: DecisionSceneSurface, context: Context) {
        view.onDraw = onDraw
        view.configure(
            dark: colorScheme == .dark,
            contentSize: sizeCategory.uiCategory,
            reduceMotion: reduceMotion,
            active: isActive
        )
    }

    static func dismantleUIView(_ view: DecisionSceneSurface, coordinator: ()) {
        view.tearDown()
    }
}

private extension ContentSizeCategory {
    var uiCategory: UIContentSizeCategory {
        switch self {
        case .extraSmall: .extraSmall
        case .small: .small
        case .medium: .medium
        case .large: .large
        case .extraLarge: .extraLarge
        case .extraExtraLarge: .extraExtraLarge
        case .extraExtraExtraLarge: .extraExtraExtraLarge
        case .accessibilityMedium: .accessibilityMedium
        case .accessibilityLarge: .accessibilityLarge
        case .accessibilityExtraLarge: .accessibilityExtraLarge
        case .accessibilityExtraExtraLarge: .accessibilityExtraExtraLarge
        case .accessibilityExtraExtraExtraLarge: .accessibilityExtraExtraExtraLarge
        @unknown default: .large
        }
    }
}
