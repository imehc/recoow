import SwiftUI

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
