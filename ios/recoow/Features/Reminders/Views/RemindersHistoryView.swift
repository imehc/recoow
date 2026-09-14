import SwiftUI

struct RemindersHistoryView: View {
    let viewModel: RemindersViewModel
    @State private var reminderPendingDeletion: ReminderRecord?

    let reminderImageTransition: Namespace.ID

    var body: some View {
        List {
            if viewModel.pastReminders.isEmpty {
                ContentUnavailableView {
                    Label(AppLocalization.string("暂无历史记录"), systemImage: "clock.arrow.circlepath")
                }
            } else {
                ForEach(viewModel.pastReminders) { reminder in
                    NavigationLink(value: ReminderRoute(id: reminder.id)) {
                        ReminderRow(
                            reminder: reminder,
                            reminderImageTransition: reminderImageTransition
                        )
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        if reminder.isTodayCompleted {
                            Button {
                                undoTodayCheckIn(reminder)
                            } label: {
                                Label("撤销打卡", systemImage: "arrow.uturn.backward.circle")
                            }
                            .tint(AppPalette.amber)
                        } else if reminder.canRestoreCompletion {
                            Button {
                                restoreReminder(reminder)
                            } label: {
                                Label("恢复", systemImage: "arrow.uturn.backward")
                            }
                            .tint(AppPalette.accent)
                        }

                        Button {
                            requestDeleteReminder(reminder)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                        .tint(.red)
                    }
                }
            }
        }
        .appGroupedListStyle()
        .navigationTitle(AppLocalization.string("历史"))
        .appNavigationStyle()
        // ReminderRoute 的 navigationDestination 只在 RemindersView 注册一次：同一 NavigationStack 里
        // 重复注册同一类型属于未定义行为，会让详情入栈时渲染成上一层页面。
        .alert(
            reminderPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($reminderPendingDeletion),
            presenting: reminderPendingDeletion
        ) { reminder in
            Button("删除", role: .destructive) {
                confirmDeleteReminder(reminder)
            }
            Button("取消", role: .cancel) {
                reminderPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func undoTodayCheckIn(_ reminder: ReminderRecord) {
        Task {
            await viewModel.undoTodayCheckIn(reminder)
        }
    }

    private func restoreReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.setCompleted(reminder, isCompleted: false)
        }
    }

    private func requestDeleteReminder(_ reminder: ReminderRecord) {
        reminderPendingDeletion = reminder
    }

    private func confirmDeleteReminder(_ reminder: ReminderRecord) {
        reminderPendingDeletion = nil

        Task {
            await viewModel.deleteReminder(id: reminder.id)
        }
    }
}
