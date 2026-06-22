import SwiftUI

struct PhotoInputRowView: View {
    let imageData: Data?
    let systemImage: String
    let isPreparingPhoto: Bool
    var titleText: String?
    var statusTextOverride: String?
    var accessibilityLabelText: String?
    var accessibilityHintText: String?
    let onPreviewPhoto: () -> Void
    let onSourceRequest: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            if imageData == nil {
                PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: AppDesign.largeThumbnailSize)
            } else {
                Button(action: onPreviewPhoto) {
                    PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: AppDesign.largeThumbnailSize)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string("预览图片"))
                .disabled(isPreparingPhoto)
            }

            Button(action: onSourceRequest) {
                HStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(AppLocalization.string(titleText ?? (imageData == nil ? "选择照片" : "更换照片")))
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
                .frame(maxWidth: .infinity, minHeight: AppDesign.largeThumbnailSize)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(AppLocalization.string(accessibilityLabelText ?? (imageData == nil ? "添加图片" : "更换图片")))
            .accessibilityHint(AppLocalization.string(accessibilityHintText ?? "选择照片或拍照"))
            .disabled(isPreparingPhoto)
        }
        .padding(.vertical, 6)
    }

    private var statusText: String {
        if let statusTextOverride {
            AppLocalization.string(statusTextOverride)
        } else if isPreparingPhoto {
            AppLocalization.string("正在准备编辑")
        } else if imageData == nil {
            AppLocalization.string("轻点右侧选择照片或拍照")
        } else {
            AppLocalization.string("左侧预览，右侧更换")
        }
    }
}
