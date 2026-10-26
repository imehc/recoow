import Foundation
import Observation

/// 一次同步任务的实时进度。
/// 由 `RemoteSyncEngine` 分阶段写入，设置页直接读取；所有方法都必须在主线程调用。
///
/// 只有上传阶段与媒体阶段有确定总数（本地待推送条数 / 待传输媒体任务数），
/// 接收阶段服务端分页下发，总数不预先可知，因此只报已接收数量。
@MainActor
@Observable
final class SyncProgressStore {
    enum Phase: Equatable {
        case idle
        case preparing
        case uploading
        case receiving
        case media
        case finished
        case failed
    }

    private(set) var phase: Phase = .idle
    private(set) var uploaded = 0
    private(set) var uploadTotal = 0
    private(set) var received = 0
    private(set) var mediaDone = 0
    private(set) var mediaTotal = 0
    private(set) var mediaDirection = "upload"

    var isRunning: Bool {
        switch phase {
        case .preparing, .uploading, .receiving, .media:
            return true
        case .idle, .finished, .failed:
            return false
        }
    }

    /// 有确定总数时返回 0…1，否则返回 nil（UI 显示不确定进度条）。
    var fraction: Double? {
        switch phase {
        case .uploading where uploadTotal > 0:
            return min(1, Double(uploaded) / Double(uploadTotal))
        case .media where mediaTotal > 0:
            return min(1, Double(mediaDone) / Double(mediaTotal))
        default:
            return nil
        }
    }

    /// 进度主文案，例如「正在上传 12 / 300 条」。
    var headline: String {
        switch phase {
        case .idle:
            return ""
        case .preparing:
            return AppLocalization.string("正在连接服务器…")
        case .uploading:
            return uploadTotal > 0
                ? AppLocalization.format("正在上传 %d / %d 条", uploaded, uploadTotal)
                : AppLocalization.format("正在上传 %d 条", uploaded)
        case .receiving:
            return received > 0
                ? AppLocalization.format("正在接收 %d 条", received)
                : AppLocalization.string("正在读取云端变更…")
        case .media:
            switch (mediaDirection == "download", mediaTotal > 0) {
            case (true, true):
                return AppLocalization.format("正在下载媒体 %d / %d 个", mediaDone, mediaTotal)
            case (true, false):
                return AppLocalization.string("正在下载媒体…")
            case (false, true):
                return AppLocalization.format("正在上传媒体 %d / %d 个", mediaDone, mediaTotal)
            case (false, false):
                return AppLocalization.string("正在上传媒体…")
            }
        case .finished:
            return AppLocalization.format("同步完成：上传 %d 条，接收 %d 条", uploaded, received)
        case .failed:
            return AppLocalization.string("同步未完成")
        }
    }

    func begin() {
        phase = .preparing
        uploaded = 0
        uploadTotal = 0
        received = 0
        mediaDone = 0
        mediaTotal = 0
        mediaDirection = "upload"
    }

    func beginUpload(total: Int) {
        phase = .uploading
        uploaded = 0
        uploadTotal = max(0, total)
    }

    func addUploaded(_ count: Int) {
        guard count > 0 else { return }
        phase = .uploading
        uploaded += count
        if uploadTotal < uploaded { uploadTotal = uploaded }
    }

    func beginReceiving(received count: Int = 0) {
        phase = .receiving
        received = count
    }

    func addReceived(_ count: Int) {
        guard count > 0 else { return }
        phase = .receiving
        received += count
    }

    func beginMedia(direction: String) {
        phase = .media
        mediaDirection = direction
        mediaDone = 0
        mediaTotal = 0
    }

    func setMedia(done: Int, total: Int) {
        mediaDone = done
        mediaTotal = max(total, done)
    }

    func finish(success: Bool) {
        phase = success ? .finished : .failed
    }

    func reset() {
        phase = .idle
        uploaded = 0
        uploadTotal = 0
        received = 0
        mediaDone = 0
        mediaTotal = 0
    }
}
