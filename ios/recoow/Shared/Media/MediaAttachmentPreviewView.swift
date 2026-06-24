import SwiftUI

struct MediaAttachmentPreviewView: View {
    @Environment(\.dismiss) private var dismiss

    let attachment: MediaAttachment
    var attachments: [MediaAttachment] = []

    var body: some View {
        switch attachment.kind {
        case .photo:
            PhotoPreviewView(items: photoPreviewItems, initialID: attachment.id)
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

    private var photoPreviewItems: [PhotoPreviewItem] {
        let photoAttachments = attachments.filter { $0.kind == .photo }
        let source = photoAttachments.isEmpty ? [attachment] : photoAttachments

        return source.map { attachment in
            PhotoPreviewItem(
                id: attachment.id,
                imageData: attachment.data,
                title: attachment.displayTitle
            )
        }
    }
}
