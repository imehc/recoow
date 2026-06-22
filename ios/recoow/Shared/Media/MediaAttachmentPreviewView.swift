import SwiftUI

struct MediaAttachmentPreviewView: View {
    @Environment(\.dismiss) private var dismiss

    let attachment: MediaAttachment

    var body: some View {
        switch attachment.kind {
        case .photo:
            AdaptivePhotoPreviewView(
                title: attachment.displayTitle,
                imageData: attachment.data
            )
        case .audio:
            NavigationStack {
                ZStack {
                    Color(.systemBackground)
                        .ignoresSafeArea()

                    ContentUnavailableView(AppLocalization.string("无法预览语音"), systemImage: "waveform")
                }
                .navigationTitle(attachment.displayTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(AppLocalization.string("完成"), action: close)
                    }
                }
            }
        }
    }

    private func close() {
        dismiss()
    }
}
