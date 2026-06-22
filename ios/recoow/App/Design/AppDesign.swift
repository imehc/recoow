import SwiftUI

enum AppDesign {
    static let cornerRadius: CGFloat = 8
    static let iconCornerRadius: CGFloat = cornerRadius
    static let sectionSpacing: CGFloat = 18
    static let gridSpacing: CGFloat = 12
    static let metadataItemSpacing: CGFloat = 4
    static let metadataGroupSpacing: CGFloat = 14

    /// 图片和图标的统一规格入口；新增列表/历史/附件缩略图时优先复用这些尺寸，避免各页面自行硬编码。
    static let compactIconSize: CGFloat = 34
    static let formIconSize: CGFloat = 36
    static let touchIconSize: CGFloat = 44
    static let rowIconSize: CGFloat = 40
    static let compactMetricIconSize: CGFloat = 26
    static let metricIconSize: CGFloat = 28
    static let listIconSize: CGFloat = 56
    static let historyIconSize: CGFloat = 64
    static let largeThumbnailSize: CGFloat = 88
    static let mediaAttachmentThumbnailSize: CGFloat = 44
    static let mapMinimumHeight: CGFloat = 320
}
