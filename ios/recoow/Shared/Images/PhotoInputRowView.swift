import SwiftUI

struct PhotoInputRowView: View {
    let imageData: Data?
    let hasImage: Bool
    let systemImage: String
    let isPreparingPhoto: Bool
    var titleText: String?
    var statusTextOverride: String?
    var accessibilityLabelText: String?
    var accessibilityHintText: String?
    let onPreviewPhoto: () -> Void
    let onSourceRequest: () -> Void
    let onRemovePhoto: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            if hasImage == false {
                PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: AppDesign.largeThumbnailSize)
            } else {
                ZStack(alignment: .topTrailing) {
                    Button(action: onPreviewPhoto) {
                        PhotoThumbnailView(imageData: imageData, systemImage: systemImage, size: AppDesign.largeThumbnailSize)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(AppLocalization.string("预览图片"))
                    .disabled(isPreparingPhoto)

                    if isPreparingPhoto == false {
                        Button(role: .destructive, action: onRemovePhoto) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title3)
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, Color(.systemRed))
                                .frame(width: 44, height: 44, alignment: .topTrailing)
                                .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(AppLocalization.string("移除图片"))
                        .offset(x: 12, y: -12)
                    }
                }
                .frame(width: AppDesign.largeThumbnailSize, height: AppDesign.largeThumbnailSize)
            }

            Button(action: onSourceRequest) {
                HStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(AppLocalization.string(titleText ?? (hasImage ? "更换照片" : "选择照片")))
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
            .accessibilityLabel(AppLocalization.string(accessibilityLabelText ?? (hasImage ? "更换图片" : "添加图片")))
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
        } else if hasImage == false {
            AppLocalization.string("轻点右侧选择照片或拍照")
        } else {
            AppLocalization.string("左侧预览，右侧更换")
        }
    }
}
