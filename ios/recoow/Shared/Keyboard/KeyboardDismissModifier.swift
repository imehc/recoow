import SwiftUI

struct KeyboardDismissModifier<Field: Hashable>: ViewModifier {
    var focusedField: FocusState<Field?>.Binding

    func body(content: Content) -> some View {
        content
            .scrollDismissesKeyboard(.interactively)
            .background {
                KeyboardDismissObserver {
                    focusedField.wrappedValue = nil
                }
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()

                    Button("完成") {
                        focusedField.wrappedValue = nil
                    }
                }
            }
    }
}

extension View {
    func dismissesKeyboardOnTap<Field: Hashable>(
        focusedField: FocusState<Field?>.Binding
    ) -> some View {
        modifier(KeyboardDismissModifier(focusedField: focusedField))
    }
}
