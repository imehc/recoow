import SwiftUI

struct DecisionCollectionFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var note: String
    @FocusState private var focusedField: String?

    let collection: DecisionCollection?
    let viewModel: DecisionCollectionsViewModel

    init(collection: DecisionCollection?, viewModel: DecisionCollectionsViewModel) {
        self.collection = collection
        self.viewModel = viewModel
        _title = State(initialValue: collection?.title ?? "")
        _note = State(initialValue: collection?.note ?? "")
    }

    var body: some View {
        Form {
            Section("基础信息") {
                LabeledContent("标题") {
                    TextField("请输入标题", text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
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
        .navigationTitle(collection == nil ? "添加集合" : "编辑集合")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(trimmedTitle.isEmpty)
            }
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = collection ?? viewModel.makeCollection(title: trimmedTitle, note: normalizedNote)
        record.title = trimmedTitle
        record.note = normalizedNote

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
