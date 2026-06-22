import SwiftUI

struct MediaAttachmentListSection: View {
    let attachments: [MediaAttachment]
    let onPreview: (MediaAttachment) -> Void

    var body: some View {
        Section(AppLocalization.string("附件")) {
            if photoAttachments.isEmpty == false {
                MediaAttachmentPhotoGridView(
                    photos: photoAttachments,
                    onPreview: onPreview
                )
            }

            ForEach(audioAttachments) { attachment in
                MediaAttachmentRow(attachment: attachment) { attachment in
                    onPreview(attachment)
                }
            }
        }
    }

    private var photoAttachments: [MediaAttachment] {
        attachments.filter { $0.kind == .photo }
    }

    private var audioAttachments: [MediaAttachment] {
        attachments.filter { $0.kind == .audio }
    }
}
