import SwiftUI

struct ReminderIconPickerRow: View {
    @Binding var selection: String
    @State private var isShowingIconSelection = false

    var body: some View {
        HStack {
            Button(action: showIconSelection) {
                ReminderIconView(memoryIcon: selection, size: 64)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("选择图标")

            Spacer()
        }
        .padding(.vertical, 6)
        .sheet(isPresented: $isShowingIconSelection) {
            NavigationStack {
                ReminderIconSelectionView(selection: $selection)
            }
            .presentationDetents([.height(330)])
            .presentationDragIndicator(.visible)
        }
    }

    private func showIconSelection() {
        isShowingIconSelection = true
    }
}
