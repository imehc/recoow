import PhotosUI
import SwiftUI
import UIKit

struct PhotoInputSection: View {
    @Binding var imageData: Data?
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isShowingImageActions = false
    @State private var isShowingPhotoPicker = false
    @State private var isShowingCamera = false
    @State private var errorMessage: String?

    let placeholderSystemImage: String

    var body: some View {
        Section("图片") {
            Button(action: showImageActions) {
                HStack(spacing: 12) {
                    PhotoThumbnailView(imageData: imageData, systemImage: placeholderSystemImage, size: 72)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(imageData == nil ? "添加图片" : "更换图片")
                            .foregroundStyle(.primary)

                        Text("点击选择拍照或相册")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Image(systemName: "chevron.up.chevron.down")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(imageData == nil ? "添加图片" : "更换图片")
            .accessibilityHint("打开拍照或相册选项")
            .padding(.vertical, 4)
            .confirmationDialog("图片", isPresented: $isShowingImageActions, titleVisibility: .visible) {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("拍照", systemImage: "camera", action: showCamera)
                }

                Button("从相册选择", systemImage: "photo", action: showPhotoPicker)

                if imageData != nil {
                    Button("移除图片", systemImage: "trash", role: .destructive, action: removeImage)
                }
            } message: {
                Text("选择图片来源")
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .photosPicker(
            isPresented: $isShowingPhotoPicker,
            selection: $selectedPhotoItem,
            matching: .images,
            preferredItemEncoding: .current
        )
        .onChange(of: selectedPhotoItem) { _, newValue in
            loadPhoto(from: newValue)
        }
        .sheet(isPresented: $isShowingCamera) {
            CameraCaptureView { data in
                imageData = data
                errorMessage = nil
            }
        }
    }

    private func showImageActions() {
        isShowingImageActions = true
    }

    private func showPhotoPicker() {
        errorMessage = nil
        selectedPhotoItem = nil
        isShowingImageActions = false

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            isShowingPhotoPicker = true
        }
    }

    private func showCamera() {
        errorMessage = nil
        isShowingImageActions = false

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            isShowingCamera = true
        }
    }

    private func removeImage() {
        imageData = nil
        selectedPhotoItem = nil
        errorMessage = nil
    }

    private func loadPhoto(from item: PhotosPickerItem?) {
        guard let item else { return }

        Task {
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    errorMessage = "无法读取这张照片，请换一张重试"
                    selectedPhotoItem = nil
                    return
                }

                if let image = UIImage(data: data),
                   let compressedData = image.jpegData(compressionQuality: 0.82) {
                    imageData = compressedData
                } else {
                    imageData = data
                }

                errorMessage = nil
                selectedPhotoItem = nil
            } catch {
                errorMessage = "无法读取这张照片，请换一张重试"
                selectedPhotoItem = nil
            }
        }
    }
}
