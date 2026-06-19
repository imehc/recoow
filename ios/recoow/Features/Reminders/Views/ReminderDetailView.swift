import SwiftUI

struct ReminderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: RemindersViewModel
    @State private var reminderForEditing: ReminderRecord?
    @State private var reminderPendingDeletion: ReminderRecord?

    let reminderID: String
    let reminderImageTransition: Namespace.ID?

    var body: some View {
        Group {
            if let reminder = viewModel.reminder(id: reminderID) {
                content(for: reminder)
            } else {
                ContentUnavailableView("打卡不存在", systemImage: "checkmark.circle")
            }
        }
        .navigationTitle("打卡详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $reminderForEditing) { reminder in
            NavigationStack {
                ReminderFormView(reminder: reminder, viewModel: viewModel)
            }
        }
        .task(id: reminderID) {
            await viewModel.loadReminderIfNeeded(id: reminderID)
        }
    }

    @ViewBuilder
    private func content(for reminder: ReminderRecord) -> some View {
        if reminder.imageData != nil, let reminderImageTransition {
            form(for: reminder)
                .navigationTransition(.zoom(sourceID: reminderID, in: reminderImageTransition))
        } else {
            form(for: reminder)
        }
    }

    private func form(for reminder: ReminderRecord) -> some View {
        List {
            if reminder.imageData != nil {
                Section("图片") {
                    PhotoSquareImageView(imageData: reminder.imageData, systemImage: "bell.fill")
                }
            }

            Section("打卡") {
                LabeledContent("标题", value: reminder.title)
                LabeledContent("规则", value: reminder.scheduleKind.title)
                LabeledContent("计划", value: reminder.scheduleTitle)
                if let nextOccurrenceDate = reminder.nextOccurrenceDate {
                    LabeledContent("下次提醒", value: AppFormatters.dateTime(milliseconds: RemindersViewModel.milliseconds(for: nextOccurrenceDate)))
                }
                LabeledContent("提前提醒", value: reminder.leadTime.localizedTitle)
                LabeledContent("状态", value: statusText(for: reminder))
                if let progressText = reminder.progressText {
                    LabeledContent("进度", value: progressText)
                }
            }

            if reminder.imageData == nil || reminder.note != nil {
                Section("记忆") {
                    if reminder.imageData == nil {
                        HStack {
                            Text("图标")
                            Spacer()
                            ReminderIconView(memoryIcon: reminder.memoryIcon, size: 36)
                        }
                    }

                    if let note = reminder.note {
                        LabeledContent("备注", value: note)
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("编辑", systemImage: "pencil") {
                    reminderForEditing = reminder
                }
            }

            ToolbarItem(placement: .bottomBar) {
                HStack {
                    Button(reminder.isCompleted ? "恢复" : "完成", systemImage: reminder.isCompleted ? "arrow.uturn.backward" : "checkmark.circle") {
                        setCompleted(reminder, isCompleted: reminder.isCompleted == false)
                    }

                    Spacer()

                    Button("删除", systemImage: "trash", role: .destructive) {
                        reminderPendingDeletion = reminder
                    }
                }
            }
        }
        .alert(item: $reminderPendingDeletion) { reminder in
            Alert(
                title: Text(AppLocalization.format("delete.record.title", reminder.title)),
                message: Text(AppLocalization.string("删除后该记录会从历史中移除。")),
                primaryButton: .destructive(Text("删除")) {
                    deleteReminder(id: reminder.id)
                },
                secondaryButton: .cancel(Text("取消")) {
                    reminderPendingDeletion = nil
                }
            )
        }
    }

    private func statusText(for reminder: ReminderRecord) -> String {
        if reminder.isCompleted {
            return AppLocalization.string("已完成")
        }

        if reminder.isTodayCompleted {
            return AppLocalization.string("今日已打卡")
        }

        if reminder.isEnabled == false {
            return AppLocalization.string("已关闭")
        }

        if reminder.isUpcoming {
            return AppLocalization.string("待打卡")
        }

        return AppLocalization.string("已结束")
    }

    private func deleteReminder(id: String) {
        reminderPendingDeletion = nil

        Task {
            await viewModel.deleteReminder(id: id)
            dismiss()
        }
    }

    private func setCompleted(_ reminder: ReminderRecord, isCompleted: Bool) {
        Task {
            await viewModel.setCompleted(reminder, isCompleted: isCompleted)
        }
    }
}
