import Foundation

enum AppNotificationError: LocalizedError {
    case permissionDenied
    case scheduleDateInPast
    case attachmentDirectoryUnavailable

    var errorDescription: String? {
        switch self {
        case .permissionDenied:
            AppLocalization.string("系统通知权限未开启，记录已保存但不会弹出打卡提醒。")
        case .scheduleDateInPast:
            AppLocalization.string("打卡提醒时间已过，无法安排系统通知。")
        case .attachmentDirectoryUnavailable:
            AppLocalization.string("无法准备通知图片附件。")
        }
    }
}
