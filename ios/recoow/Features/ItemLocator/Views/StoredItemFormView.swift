import SwiftUI
import UIKit

struct StoredItemFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var categoryID: String?
    @State private var title: String
    @State private var location: String
    @State private var note: String
    @State private var tags: String
    @State private var searchKeywords: String
    @State private var imageData: Data?
    @State private var isShowingPhotoSourcePicker = false
    @State private var isPreparingPhoto = false
    @State private var imageErrorMessage: String?
    @State private var previewPhoto: PhotoPreviewItem?
    @State private var pendingEditablePhoto: EditablePhoto?
    @State private var editablePhoto: EditablePhoto?
    @State private var isShowingPhotoEditor = false

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

            PhotoInputSection(
                imageData: $imageData,
                placeholderSystemImage: "shippingbox",
                isPreparingPhoto: isPreparingPhoto,
                errorMessage: imageErrorMessage,
                onPreviewPhoto: previewCurrentPhoto,
                onSourceRequest: showPhotoSourcePicker,
                onEditPhoto: editCurrentPhoto,
                onRemovePhoto: removePhoto
            )

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
        .navigationDestination(isPresented: $isShowingPhotoEditor) {
            if let editablePhoto {
                PhotoEditorView(
                    image: editablePhoto.image,
                    onCancel: cancelPhotoEditing,
                    onSave: saveEditedImage
                )
            } else {
                ContentUnavailableView("无法编辑图片", systemImage: "photo")
            }
        }
        .fullScreenCover(isPresented: $isShowingPhotoSourcePicker, onDismiss: presentPendingEditorIfNeeded) {
            PhotoSourcePickerView(
                onPhotoPicked: beginEditingPickedImage
            )
        }
        .sheet(item: $previewPhoto, content: PhotoPreviewView.init)
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

    private func showPhotoSourcePicker() {
        guard !isPreparingPhoto else { return }
        imageErrorMessage = nil
        isShowingPhotoSourcePicker = true
    }

    private func showPhotoEditor(_ photo: EditablePhoto) {
        editablePhoto = photo
        isShowingPhotoEditor = true
    }

    private func beginEditingPickedImage(_ data: Data) {
        imageErrorMessage = nil
        isPreparingPhoto = true

        Task {
            defer { isPreparingPhoto = false }

            guard let photo = await PhotoImagePreparer.editablePhoto(from: data) else {
                imageErrorMessage = "无法准备照片，请重试"
                return
            }

            pendingEditablePhoto = photo
            presentPendingEditorIfPossible()
        }
    }

    private func presentPendingEditorIfNeeded() {
        presentPendingEditorIfPossible()
    }

    private func presentPendingEditorIfPossible() {
        guard let photo = pendingEditablePhoto,
              !isShowingPhotoSourcePicker
        else { return }

        pendingEditablePhoto = nil
        showPhotoEditor(photo)
    }

    private func editCurrentPhoto() {
        guard let imageData else { return }
        imageErrorMessage = nil
        isPreparingPhoto = true

        Task {
            defer { isPreparingPhoto = false }

            guard let photo = await PhotoImagePreparer.editablePhoto(from: imageData) else {
                imageErrorMessage = "无法编辑当前图片，请重试"
                return
            }

            pendingEditablePhoto = photo
            presentPendingEditorIfPossible()
        }
    }

    private func removePhoto() {
        imageErrorMessage = nil
        previewPhoto = nil
        pendingEditablePhoto = nil
        editablePhoto = nil
    }

    private func previewCurrentPhoto() {
        guard let imageData else { return }
        previewPhoto = PhotoPreviewItem(imageData: imageData)
    }

    private func cancelPhotoEditing() {
        isShowingPhotoEditor = false
    }

    private func saveEditedImage(_ data: Data) {
        imageData = data
        isShowingPhotoEditor = false
        editablePhoto = nil
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
