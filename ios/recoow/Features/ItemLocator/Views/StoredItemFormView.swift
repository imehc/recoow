import SwiftUI

struct StoredItemFormView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var categoryID: String?
    @State private var title: String
    @State private var location: String
    @State private var note: String
    @State private var tags: String
    @State private var searchKeywords: String
    @State private var imageData: Data?
    @State private var imageAssetID: String?
    @State private var photoInputCoordinator = EditablePhotoInputCoordinator()
    @FocusState private var focusedField: String?

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
        _imageAssetID = State(initialValue: item?.imageAssetID)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                LabeledContent("物品标题") {
                    TextField("请输入物品标题", text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                Picker("分类", selection: $categoryID) {
                    Text("未分类").tag(nil as String?)

                    ForEach(viewModel.categories) { category in
                        Text(category.name).tag(Optional(category.id))
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("位置说明")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入位置说明", text: $location, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "location")
                }
            }

            EditablePhotoInputSection(
                imageData: $imageData,
                imageAssetID: $imageAssetID,
                placeholderSystemImage: "shippingbox",
                coordinator: photoInputCoordinator
            )

            Section("辅助记忆") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("备注")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入备注", text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }

                LabeledContent("标签") {
                    TextField("请输入标签", text: $tags)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "tags")
                }

                LabeledContent("搜索关键词") {
                    TextField("请输入搜索关键词", text: $searchKeywords)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "searchKeywords")
                }
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(item == nil ? "添加物品" : "编辑物品")
        .navigationBarTitleDisplayMode(.inline)
        .editablePhotoInputPresentation(
            coordinator: photoInputCoordinator,
            imageData: $imageData,
            imageAssetID: $imageAssetID,
            mediaAssetRepository: container.mediaAssetRepository
        )
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
            imageData: imageReference.independentData,
            imageAssetID: imageReference.assetID
        )
        record.categoryID = categoryID
        record.title = trimmedTitle
        record.location = trimmedLocation
        record.note = normalizedNote
        record.tags = normalizedTags
        record.searchKeywords = normalizedSearchKeywords
        record.setImageReference(imageReference)

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }

    private var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }
}
