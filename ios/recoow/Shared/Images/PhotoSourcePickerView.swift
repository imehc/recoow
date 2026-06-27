import SwiftUI
import UIKit

struct PhotoSourcePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppPreferenceStorageKeys.addsPickedPhotosToMediaLibrary) private var addsPickedPhotosToMediaLibrary = false
    @AppStorage(AppPreferenceStorageKeys.savesCameraPhotosToLibrary) private var savesCameraPhotosToLibrary = true
    @State private var mode: PhotoSourcePickerMode = .library

    let onPhotoPicked: (Data) -> Void
    var mediaAssetRepository: MediaAssetRepository? = nil
    var onMediaAssetPicked: ((MediaAsset) -> Void)? = nil
    var onClose: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            pickerContent
                .id(mode)
                .ignoresSafeArea(edges: [.top, .horizontal])

            if shouldShowBottomBar {
                bottomBar
            }
        }
        .background(Color(.systemBackground))
    }

    @ViewBuilder
    private var pickerContent: some View {
        switch mode {
        case .library, .camera:
            PhotoSourcePickerController(
                mode: mode,
                savesCameraPhotosToLibrary: savesCameraPhotosToLibrary,
                onSelect: selectPhoto,
                onCancel: cancel
            )
        case .assetLibrary:
            if let mediaAssetRepository, let onMediaAssetPicked {
                NavigationStack {
                    MediaAssetLibraryPickerView(
                        repository: mediaAssetRepository,
                        onSelect: { asset in
                            onMediaAssetPicked(asset)
                            close()
                        }
                    )
                }
            } else {
                PhotoSourcePickerController(
                    mode: .library,
                    savesCameraPhotosToLibrary: savesCameraPhotosToLibrary,
                    onSelect: selectPhoto,
                    onCancel: cancel
                )
            }
        }
    }

    private var bottomBar: some View {
        Picker(AppLocalization.string("图片来源"), selection: $mode) {
            Label(AppLocalization.string("照片"), systemImage: "photo.on.rectangle").tag(PhotoSourcePickerMode.library)
            if canPickMediaAsset {
                Label(AppLocalization.string("素材"), systemImage: "rectangle.stack").tag(PhotoSourcePickerMode.assetLibrary)
            }
            Label(AppLocalization.string("拍照"), systemImage: "camera").tag(PhotoSourcePickerMode.camera)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .background(.bar)
    }

    private var isCameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    private var shouldShowBottomBar: Bool {
        isCameraAvailable || canPickMediaAsset
    }

    private var canPickMediaAsset: Bool {
        mediaAssetRepository != nil && onMediaAssetPicked != nil
    }

    private func selectPhoto(source: PhotoSourcePickerMode, data: Data) {
        if savePickedPhotoToMediaLibraryIfNeeded(data, source: source) {
            close()
            return
        }

        onPhotoPicked(data)
        close()
    }

    private func savePickedPhotoToMediaLibraryIfNeeded(_ data: Data, source: PhotoSourcePickerMode) -> Bool {
        guard source != .assetLibrary,
              addsPickedPhotosToMediaLibrary,
              let mediaAssetRepository,
              let onMediaAssetPicked
        else { return false }

        do {
            // 开启自动入库时，当前记录保存 asset_id 引用；关闭时才走独立图片数据。
            let asset = try mediaAssetRepository.createImageAsset(data: data)
            onMediaAssetPicked(asset)
            return true
        } catch {
            return false
        }
    }

    private func cancel() {
        close()
    }

    private func close() {
        if let onClose {
            onClose()
        } else {
            dismiss()
        }
    }
}
