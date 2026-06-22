import SwiftUI
import UIKit

struct PhotoSourcePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var mode: PhotoSourcePickerMode = .library

    let onPhotoPicked: (Data) -> Void
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
                onSelect: selectPhoto,
                onCancel: cancel
            )
        }
    }

    private var bottomBar: some View {
        Picker(AppLocalization.string("图片来源"), selection: $mode) {
            Label(AppLocalization.string("照片"), systemImage: "photo.on.rectangle").tag(PhotoSourcePickerMode.library)
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
        isCameraAvailable
    }

    private func selectPhoto(_ data: Data) {
        onPhotoPicked(data)
        close()
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
