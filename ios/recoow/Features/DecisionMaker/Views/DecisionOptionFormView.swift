import SwiftUI
import UIKit

struct DecisionOptionFormView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var detail: String
    @State private var customInfo: String
    @State private var imageData: Data?
    @State private var imageAssetID: String?
    @State private var weight: Int
    @State private var isEnabled: Bool
    @State private var isShowingPhotoSourcePicker = false
    @State private var isPreparingPhoto = false
    @State private var imageErrorMessage: String?
    @State private var previewPhoto: PhotoPreviewItem?
    @State private var pendingEditablePhoto: EditablePhoto?
    @State private var editablePhoto: EditablePhoto?
    @State private var isShowingPhotoEditor = false
    @FocusState private var focusedField: String?

    let option: DecisionOption?
    let viewModel: DecisionOptionsViewModel

    init(option: DecisionOption?, viewModel: DecisionOptionsViewModel) {
        self.option = option
        self.viewModel = viewModel
        _title = State(initialValue: option?.title ?? "")
        _detail = State(initialValue: option?.detail ?? "")
        _customInfo = State(initialValue: option?.customInfo ?? "")
        _imageData = State(initialValue: option?.imageData)
        _imageAssetID = State(initialValue: option?.imageAssetID)
        _weight = State(initialValue: option?.weight ?? 1)
        _isEnabled = State(initialValue: option?.isEnabled ?? true)
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
                    Text("描述")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入描述", text: $detail, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "detail")
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("自定义信息")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入自定义信息", text: $customInfo, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "customInfo")
                }
            }

            PhotoInputSection(
                imageData: $imageData,
                imageAssetID: $imageAssetID,
                placeholderSystemImage: "questionmark.circle",
                isPreparingPhoto: isPreparingPhoto,
                errorMessage: imageErrorMessage,
                onPreviewPhoto: previewCurrentPhoto,
                onSourceRequest: showPhotoSourcePicker,
                onEditPhoto: editCurrentPhoto,
                onRemovePhoto: removePhoto
            )

            Section("随机设置") {
                Stepper(value: $weight, in: 1...100) {
                    LabeledContent("权重", value: "\(weight)")
                }

                Toggle("启用", isOn: $isEnabled)
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(option == nil ? "添加选项" : "编辑选项")
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
                onPhotoPicked: beginEditingPickedImage,
                mediaAssetRepository: container.mediaAssetRepository,
                onMediaAssetPicked: beginEditingPickedAsset
            )
        }
        .fullScreenCover(item: $previewPhoto) { item in
            PhotoPreviewView(item: item)
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedDetail: String? {
        let value = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedCustomInfo: String? {
        let value = customInfo.trimmingCharacters(in: .whitespacesAndNewlines)
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

    private func beginEditingPickedAsset(_ asset: MediaAsset) {
        guard container.mediaAssetRepository.data(for: asset) != nil else {
            imageErrorMessage = "无法准备照片，请重试"
            return
        }

        imageData = nil
        imageAssetID = asset.id
        isShowingPhotoEditor = false
        editablePhoto = nil
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
        guard let imageData = resolvedImageData else { return }
        previewPhoto = PhotoPreviewItem(imageData: imageData)
    }

    private func cancelPhotoEditing() {
        isShowingPhotoEditor = false
    }

    private func saveEditedImage(_ data: Data) {
        imageData = data
        imageAssetID = nil
        isShowingPhotoEditor = false
        editablePhoto = nil
    }

    private var resolvedImageData: Data? {
        imageReference.resolvedData
    }

    private var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }

    private func save() {
        var record = option ?? viewModel.makeOption(
            title: trimmedTitle,
            detail: normalizedDetail,
            customInfo: normalizedCustomInfo,
            imageData: imageReference.independentData,
            imageAssetID: imageReference.assetID,
            weight: weight,
            isEnabled: isEnabled
        )
        record.title = trimmedTitle
        record.detail = normalizedDetail
        record.customInfo = normalizedCustomInfo
        record.setImageReference(imageReference)
        record.weight = weight
        record.isEnabled = isEnabled

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
