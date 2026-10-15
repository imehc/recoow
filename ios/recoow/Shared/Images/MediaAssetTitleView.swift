import SwiftUI

/// 图片分组的标题行：左侧沿用「图片」分组标题样式，右侧显示素材标题；无标题时不占位。
struct MediaAssetSectionHeader: View {
    @Environment(AppContainer.self) private var container
    var title: LocalizedStringKey = "图片"
    let assetID: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: AppDesign.gridSpacing) {
            Text(title)

            if let assetTitle = container.mediaAssetTitle(for: assetID) {
                Text(assetTitle)
                    .font(AppDesign.rowSubtitleFont)
                    .foregroundStyle(AppPalette.ink)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .appSectionHeaderStyle()
    }
}

/// 标题只从素材引用解析；没有标题时不占用图片区域的布局空间。
struct MediaAssetTitleView: View {
    @Environment(AppContainer.self) private var container
    let assetID: String?
    var fallback: String? = nil
    var lineLimit: Int? = 2

    var body: some View {
        if let title = container.mediaAssetTitle(for: assetID) ?? fallback {
            Text(title)
                .font(AppDesign.rowSubtitleFont)
                .foregroundStyle(AppPalette.ink)
                .lineLimit(lineLimit)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
