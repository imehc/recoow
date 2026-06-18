import SwiftUI
import UIKit

struct PhotoSourcePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var mode: PhotoSourcePickerMode = .library

    let onPhotoPicked: (Data) -> Void

    var body: some View {
        VStack(spacing: 0) {
            PhotoSourcePickerController(
                mode: mode,
                onSelect: selectPhoto,
                onCancel: cancel
            )
            .id(mode)
            .ignoresSafeArea(edges: [.top, .horizontal])

            if isCameraAvailable {
                bottomSourceSwitcher
            }
        }
        .background(Color(.systemBackground))
    }

    private var bottomSourceSwitcher: some View {
        Picker("图片来源", selection: $mode) {
            Label("照片", systemImage: "photo.on.rectangle").tag(PhotoSourcePickerMode.library)
            Label("拍照", systemImage: "camera").tag(PhotoSourcePickerMode.camera)
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

    private func selectPhoto(_ data: Data) {
        onPhotoPicked(data)
        dismiss()
    }

    private func cancel() {
        dismiss()
    }
}
