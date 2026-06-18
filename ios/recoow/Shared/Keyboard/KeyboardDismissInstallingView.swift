import UIKit

final class KeyboardDismissInstallingView: UIView {
    var onDismiss: (() -> Void)?

    private weak var installedWindow: UIWindow?
    private var tapRecognizer: UITapGestureRecognizer?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        installTapRecognizerIfNeeded()
    }

    deinit {
        if let tapRecognizer {
            installedWindow?.removeGestureRecognizer(tapRecognizer)
        }
    }

    private func installTapRecognizerIfNeeded() {
        guard let window, installedWindow !== window else { return }

        if let tapRecognizer {
            installedWindow?.removeGestureRecognizer(tapRecognizer)
        }

        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delaysTouchesEnded = false
        recognizer.delegate = self
        window.addGestureRecognizer(recognizer)

        installedWindow = window
        tapRecognizer = recognizer
    }

    @objc private func handleTap() {
        onDismiss?()
    }
}

extension KeyboardDismissInstallingView: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let touchedView = touch.view else { return true }
        return touchedView.isInsideTextInput == false
    }
}

private extension UIView {
    var isInsideTextInput: Bool {
        if self is UITextField || self is UITextView {
            return true
        }

        return superview?.isInsideTextInput == true
    }
}
