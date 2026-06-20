import SwiftUI
import UIKit

struct KeyboardDismissModifier<Field: Hashable>: ViewModifier {
    var focusedField: FocusState<Field?>.Binding

    func body(content: Content) -> some View {
        content
            .scrollDismissesKeyboard(.interactively)
            .background {
                KeyboardDismissObserver {
                    focusedField.wrappedValue = nil
                    dismissKeyboard()
                }
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()

                    Button("完成") {
                        focusedField.wrappedValue = nil
                        dismissKeyboard()
                    }
                }
            }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

extension View {
    func dismissesKeyboardOnTap<Field: Hashable>(
        focusedField: FocusState<Field?>.Binding
    ) -> some View {
        modifier(KeyboardDismissModifier(focusedField: focusedField))
    }
}
