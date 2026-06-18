import SwiftUI

struct ReminderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: RemindersViewModel
    @State private var reminderForEditing: ReminderRecord?
    @State private var isConfirmingDelete = false

    let reminderID: String
    let reminderImageTransition: Namespace.ID?

    var body: some View {
        Group {
            if let reminder = viewModel.reminder(id: reminderID) {
                content(for: reminder)
            } else {
                ContentUnavailableView("提醒不存在", systemImage: "bell.slash")
            }
        }
        .navigationTitle("提醒详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $reminderForEditing) { reminder in
            NavigationStack {
                if reminder.isUpcoming {
                    ReminderFormView(reminder: reminder, viewModel: viewModel)
                } else {
                    ContentUnavailableView("提醒已到期", systemImage: "bell.slash")
                }
            }
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

            Section("提醒") {
                LabeledContent("标题", value: reminder.title)
                LabeledContent("时间", value: AppFormatters.dateTime(milliseconds: reminder.scheduledAt))
                LabeledContent("提前提醒", value: reminder.leadTime.title)
                LabeledContent("状态", value: statusText(for: reminder))
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
                .disabled(reminder.isUpcoming == false)
            }

            ToolbarItem(placement: .bottomBar) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    isConfirmingDelete = true
                }
            }
        }
        .confirmationDialog("删除提醒？", isPresented: $isConfirmingDelete) {
            Button("删除", role: .destructive) {
                deleteReminder()
            }

            Button("取消", role: .cancel) { }
        } message: {
            Text("删除后该提醒会从列表和历史记录中移除。")
        }
    }

    private func statusText(for reminder: ReminderRecord) -> String {
        if reminder.isUpcoming {
            return "待提醒"
        }

        if reminder.isEnabled {
            return "已到期"
        }

        return "已关闭"
    }

    private func deleteReminder() {
        Task {
            await viewModel.deleteReminder(id: reminderID)
            dismiss()
        }
    }
}
