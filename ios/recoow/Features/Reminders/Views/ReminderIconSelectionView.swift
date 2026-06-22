import SwiftUI

struct ReminderIconSelectionView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var selection: String
    @State private var customIcon = ""
    @FocusState private var focusedField: String?

    private let columns = [
        GridItem(.adaptive(minimum: AppDesign.listIconSize), spacing: 14)
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(ReminderMemoryIcon.allCases) { icon in
                        Button {
                            select(icon.rawValue)
                        } label: {
                            ReminderIconView(memoryIcon: icon.rawValue, size: AppDesign.listIconSize)
                                .overlay {
                                    if selection == icon.rawValue {
                                        RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                                            .stroke(.purple, lineWidth: 2)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(icon.titleKey)
                    }
                }

                HStack(spacing: 12) {
                    Text("自定义")
                        .foregroundStyle(.secondary)

                    TextField("请输入图标", text: $customIcon)
                        .textInputAutocapitalization(.never)
                        .focused($focusedField, equals: "customIcon")

                    Button("使用", action: selectCustomIcon)
                        .disabled(normalizedCustomIcon.isEmpty)
                }
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
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
