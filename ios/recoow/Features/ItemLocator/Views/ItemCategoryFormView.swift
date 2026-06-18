import SwiftUI

struct ItemCategoryFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var note: String
    @FocusState private var focusedField: String?

    let category: ItemCategory?
    let viewModel: ItemLocatorViewModel

    init(category: ItemCategory?, viewModel: ItemLocatorViewModel) {
        self.category = category
        self.viewModel = viewModel
        _name = State(initialValue: category?.name ?? "")
        _note = State(initialValue: category?.note ?? "")
    }

    var body: some View {
        Form {
            Section("分类") {
                LabeledContent("名称") {
                    TextField("请输入名称", text: $name)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "name")
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("备注")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入备注", text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(category == nil ? "添加分类" : "编辑分类")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(trimmedName.isEmpty)
            }
        }
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = category ?? viewModel.makeCategory(name: trimmedName, note: normalizedNote)
        record.name = trimmedName
        record.note = normalizedNote

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
