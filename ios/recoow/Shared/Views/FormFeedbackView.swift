import SwiftUI

/// 表单分组下的反馈，不与可操作的列表行共用表面或分隔线。
struct FormFeedbackView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let message: String
    let systemImage: String
    var color: Color = AppPalette.secondaryInk
    var actionTitle: String?
    var action: (() -> Void)?
    var isWorking = false

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
                    messageLabel
                    actionButton
                }
            } else {
                HStack(alignment: .firstTextBaseline, spacing: AppDesign.metadataGroupSpacing) {
                    messageLabel
                    Spacer(minLength: 0)
                    actionButton
                }
            }
        }
        .font(AppDesign.metadataFont)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var messageLabel: some View {
        HStack(alignment: .firstTextBaseline, spacing: AppDesign.metadataItemSpacing) {
            Image(systemName: systemImage)
                .opacity(isWorking ? 0 : 1)
                .overlay {
                    if isWorking { ProgressView().controlSize(.mini) }
                }
                .accessibilityHidden(true)
            Text(message)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.updatesFrequently)
        }
        .foregroundStyle(color)
    }

    @ViewBuilder private var actionButton: some View {
        if let actionTitle, let action {
            Text(actionTitle)
                .hidden()
                .accessibilityHidden(true)
                .fixedSize(horizontal: true, vertical: false)
                .overlay {
                    Button(actionTitle, action: action)
                        .buttonStyle(.borderless)
                        .frame(minHeight: AppDesign.touchIconSize)
                        .disabled(isWorking)
                }
        }
    }
}
