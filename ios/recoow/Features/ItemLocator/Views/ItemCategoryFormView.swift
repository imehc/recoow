import SwiftUI

struct ItemCategoryFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var note: String

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
                TextField("名称", text: $name)

                TextField("备注", text: $note, axis: .vertical)
                    .lineLimit(3...)
            }
        }
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
