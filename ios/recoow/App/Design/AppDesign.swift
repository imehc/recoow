import SwiftUI

enum AppDesign {
    static let cornerRadius: CGFloat = 14
    static let iconCornerRadius: CGFloat = 10
    static let sectionSpacing: CGFloat = 22
    static let gridSpacing: CGFloat = 12
    static let pagePadding: CGFloat = 16
    static let pageTopPadding: CGFloat = 12
    static let groupedContentTopMargin: CGFloat = 8
    static let tabContentTopMargin: CGFloat = 2
    static let contentMaxWidth: CGFloat = 680
    static let sheetCornerRadius: CGFloat = 24
    static let rowTitleFont: Font = .body.weight(.medium)
    static let rowSubtitleFont: Font = .subheadline
    static let metadataFont: Font = .footnote
    static let summaryTitleFont: Font = .caption
    static let summaryValueFont: Font = .subheadline.weight(.medium)
    static let metadataItemSpacing: CGFloat = 4
    static let metadataGroupSpacing: CGFloat = 14

    static var sectionTitleFont: Font {
        if #available(iOS 26.0, *) {
            .headline
        } else {
            .footnote
        }
    }

    /// 图片和图标的统一规格入口；新增列表/历史/附件缩略图时优先复用这些尺寸，避免各页面自行硬编码。
    static let compactIconSize: CGFloat = 34
    static let formIconSize: CGFloat = 32
    static let touchIconSize: CGFloat = 44
    static let rowIconSize: CGFloat = 38
    static let compactMetricIconSize: CGFloat = 26
    static let metricIconSize: CGFloat = 28
    static let listIconSize: CGFloat = 44
    static let historyIconSize: CGFloat = 48
    static let largeThumbnailSize: CGFloat = 88
    static let mediaAttachmentThumbnailSize: CGFloat = 44
    static let mapMinimumHeight: CGFloat = 320

    static func metricColumns(for dynamicTypeSize: DynamicTypeSize) -> [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: gridSpacing, alignment: .top),
            count: dynamicTypeSize.isAccessibilitySize ? 1 : 2
        )
    }
}
