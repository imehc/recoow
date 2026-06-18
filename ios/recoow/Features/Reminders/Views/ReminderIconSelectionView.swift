import SwiftUI

struct ReminderIconSelectionView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var selection: String
    @State private var customIcon = ""

    private let columns = [
        GridItem(.adaptive(minimum: 56), spacing: 14)
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(ReminderMemoryIcon.allCases) { icon in
                        Button {
                            select(icon.rawValue)
                        } label: {
                            ReminderIconView(memoryIcon: icon.rawValue, size: 56)
                                .overlay {
                                    if selection == icon.rawValue {
                                        RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                                            .stroke(.purple, lineWidth: 2)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(icon.title)
                    }
                }

                HStack(spacing: 12) {
                    TextField("自定义", text: $customIcon)
                        .textInputAutocapitalization(.never)

                    Button("使用", action: selectCustomIcon)
                        .disabled(normalizedCustomIcon.isEmpty)
                }
            }
        }
        .contentMargins(.horizontal, 20, for: .scrollContent)
        .contentMargins(.vertical, 18, for: .scrollContent)
        .navigationTitle("选择图标")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var normalizedCustomIcon: String {
        let value = customIcon.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let firstCharacter = value.first else { return "" }
        return String(firstCharacter)
    }

    private func select(_ icon: String) {
        selection = icon
        dismiss()
    }

    private func selectCustomIcon() {
        select(normalizedCustomIcon)
    }
}
