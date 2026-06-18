import SwiftUI

struct PhotoInputRowView: View {
    let imageData: Data?
    let systemImage: String
    let isPreparingPhoto: Bool
    let onPreviewPhoto: () -> Void
    let onSourceRequest: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            if imageData == nil {
                PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: 88)
            } else {
                Button(action: onPreviewPhoto) {
                    PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: 88)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("预览图片")
                .disabled(isPreparingPhoto)
            }

            Button(action: onSourceRequest) {
                HStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(imageData == nil ? "选择照片" : "更换照片")
                            .foregroundStyle(.primary)

                        Text(statusText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    if isPreparingPhoto {
                        ProgressView()
                    } else {
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 88)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(imageData == nil ? "添加图片" : "更换图片")
            .accessibilityHint("选择照片或拍照")
            .disabled(isPreparingPhoto)
        }
        .padding(.vertical, 6)
    }

    private var statusText: String {
        if isPreparingPhoto {
            "正在准备编辑"
        } else if imageData == nil {
            "轻点右侧选择照片或拍照"
        } else {
            "左侧预览，右侧更换"
        }
    }
}
