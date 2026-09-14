import Foundation

struct ReminderRoute: Hashable {
    let id: String
}

/// 历史页同样走 NavigationPath 入栈。
///
/// 打卡任务所在的 NavigationStack 由 `homePath` 驱动，若用 `navigationDestination(isPresented:)`
/// 推入历史页，该层不会计入 path；随后在历史页里 push 详情时 path 比实际栈少一层，
/// 会导致详情入栈时渲染成历史页、返回一级才归位。
struct RemindersHistoryRoute: Hashable {}
