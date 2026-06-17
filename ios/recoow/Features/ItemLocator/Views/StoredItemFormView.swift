import SwiftUI

struct StoredItemFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var categoryID: String?
    @State private var title: String
    @State private var location: String
    @State private var note: String
    @State private var tags: String
    @State private var searchKeywords: String
    @State private var imageData: Data?

    let item: StoredItem?
    let viewModel: ItemLocatorViewModel

    init(item: StoredItem?, viewModel: ItemLocatorViewModel) {
        self.item = item
        self.viewModel = viewModel
        _categoryID = State(initialValue: item?.categoryID)
        _title = State(initialValue: item?.title ?? "")
        _location = State(initialValue: item?.location ?? "")
        _note = State(initialValue: item?.note ?? "")
        _tags = State(initialValue: item?.tags ?? "")
        _searchKeywords = State(initialValue: item?.searchKeywords ?? "")
        _imageData = State(initialValue: item?.imageData)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                TextField("物品标题", text: $title)

                Picker("分类", selection: $categoryID) {
                    Text("未分类").tag(nil as String?)

                    ForEach(viewModel.categories) { category in
                        Text(category.name).tag(Optional(category.id))
                    }
                }

                TextField("位置说明", text: $location, axis: .vertical)
                    .lineLimit(3...)
            }

            PhotoInputSection(imageData: $imageData, placeholderSystemImage: "shippingbox")

            Section("辅助记忆") {
                TextField("备注", text: $note, axis: .vertical)
                    .lineLimit(3...)

                TextField("标签", text: $tags)

                TextField("搜索关键词", text: $searchKeywords)
            }
        }
        .navigationTitle(item == nil ? "添加物品" : "编辑物品")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(trimmedTitle.isEmpty || trimmedLocation.isEmpty)
            }
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedLocation: String {
        location.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedTags: String? {
        let value = tags.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedSearchKeywords: String? {
        let value = searchKeywords.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = item ?? viewModel.makeItem(
            categoryID: categoryID,
            title: trimmedTitle,
            location: trimmedLocation,
            note: normalizedNote,
            tags: normalizedTags,
            searchKeywords: normalizedSearchKeywords,
            imageData: imageData
        )
        record.categoryID = categoryID
        record.title = trimmedTitle
        record.location = trimmedLocation
        record.note = normalizedNote
        record.tags = normalizedTags
        record.searchKeywords = normalizedSearchKeywords
        record.imageData = imageData

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
