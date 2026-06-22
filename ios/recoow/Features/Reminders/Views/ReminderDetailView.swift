import SwiftUI

struct ReminderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: RemindersViewModel
    @State private var reminderForEditing: ReminderRecord?
    @State private var reminderPendingDeletion: ReminderRecord?
    @State private var makeUpRequest: ReminderMakeUpRequest?

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
        .navigationTitle("打卡任务详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $reminderForEditing) { reminder in
            NavigationStack {
                ReminderFormView(reminder: reminder, viewModel: viewModel)
            }
        }
        .sheet(item: $makeUpRequest) { request in
            NavigationStack {
                ReminderMakeUpSheet(request: request, viewModel: viewModel)
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
                LabeledContent("类型", value: AppLocalization.string(reminder.scheduleKind.title))
                LabeledContent("计划", value: reminder.scheduleTitle(locale: locale))
                if let nextOccurrenceDate = reminder.nextOccurrenceDate {
                    LabeledContent(
                        "下次提醒",
                        value: AppFormatters.dateTime(
                            milliseconds: RemindersViewModel.milliseconds(for: nextOccurrenceDate),
                            locale: locale
                        )
                    )
                }
                LabeledContent("提前提醒", value: reminder.leadTime.localizedTitle)
                LabeledContent("状态", value: statusText(for: reminder))
                if let progressText = reminder.progressText {
                    LabeledContent("进度", value: progressText)
                }
                if let progressRemainingDays = reminder.progressRemainingDays {
                    LabeledContent("剩余", value: AppLocalization.format("%d 天", progressRemainingDays))
                }
            }

            if reminder.scheduleKind == .dailyGoal {
                Section("坚持统计") {
                    LabeledContent("累计打卡", value: AppLocalization.format("%d 天", reminder.totalCheckInDays))
                    LabeledContent("当前连续", value: AppLocalization.format("%d 天", reminder.currentStreakDays()))
                    LabeledContent("最长连续", value: AppLocalization.format("%d 天", reminder.longestStreakDays()))
                }
            }

            if reminder.completionRecords.isEmpty == false {
                Section("打卡记录") {
                    ForEach(reminder.completionRecords.reversed()) { completion in
                        ReminderCompletionRecordRow(completion: completion)
                    }
                }
            }

            if reminder.imageData == nil || reminder.note != nil {
                Section("记忆") {
                    if reminder.imageData == nil {
                        HStack {
                            Text("图标")
                            Spacer()
                            ReminderIconView(memoryIcon: reminder.memoryIcon, size: AppDesign.formIconSize)
                        }
                    }

                    if let note = reminder.note {
                        LabeledContent("备注", value: note)
                    }
                }
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    reminderPendingDeletion = reminder
                }
                .tint(.red)

                Button("编辑", systemImage: "square.and.pencil") {
                    reminderForEditing = reminder
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if hasAction(for: reminder) {
                bottomActions(for: reminder)
            }
        }
        .alert(
            reminderPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($reminderPendingDeletion),
            presenting: reminderPendingDeletion
        ) { reminder in
            Button("删除", role: .destructive) {
                deleteReminder(id: reminder.id)
            }
            Button("取消", role: .cancel) {
                reminderPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func statusText(for reminder: ReminderRecord) -> String {
        AppLocalization.string(reminder.checkInStatus().title)
    }

    private func hasAction(for reminder: ReminderRecord) -> Bool {
        reminder.isTodayCompleted || reminder.isCompleted || reminder.canCheckIn() || reminder.firstMissedCheckInDate() != nil
    }

    @ViewBuilder
    private func bottomActions(for reminder: ReminderRecord) -> some View {
        if reminder.isTodayCompleted {
            bottomActionContainer {
                undoButton(for: reminder)
            }
        } else if reminder.isCompleted {
            bottomActionContainer {
                Button("恢复", systemImage: "arrow.uturn.backward") {
                    setCompleted(reminder, isCompleted: false)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        } else {
            let canCheckIn = reminder.canCheckIn()
            let missedDate = reminder.firstMissedCheckInDate()

            bottomActionContainer {
                if let missedDate {
                    Button("补签", systemImage: "calendar.badge.plus") {
                        makeUpRequest = ReminderMakeUpRequest(reminder: reminder, date: missedDate)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .tint(.orange)
                }

                if canCheckIn {
                    Button("打卡", systemImage: "checkmark.circle.fill") {
                        setCompleted(reminder, isCompleted: true)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .tint(.green)
                }
            }
        }
    }

    private func bottomActionContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private func undoButton(for reminder: ReminderRecord) -> some View {
        Button("撤销打卡", systemImage: "arrow.uturn.backward.circle") {
            undoTodayCheckIn(reminder)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(.orange)
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

    private func undoTodayCheckIn(_ reminder: ReminderRecord) {
        Task {
            await viewModel.undoTodayCheckIn(reminder)
        }
    }
}

struct ReminderMakeUpRequest: Identifiable {
    let reminder: ReminderRecord
    let date: Date

    var id: String {
        "\(reminder.id)-\(ReminderRecord.dateKey(for: date))"
    }
}

struct ReminderMakeUpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var note = ""

    let request: ReminderMakeUpRequest
    let viewModel: RemindersViewModel

    var body: some View {
        Form {
            Section("补签") {
                LabeledContent(
                    "日期",
                    value: AppFormatters.date(
                        milliseconds: RemindersViewModel.milliseconds(for: request.date),
                        locale: locale
                    )
                )

                TextField("补签备注", text: $note, axis: .vertical)
                    .lineLimit(3...)
            }
        }
        .navigationTitle("补签")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    save()
                }
            }
        }
    }

    private func save() {
        let normalizedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)

        Task {
            await viewModel.makeUp(
                request.reminder,
                date: request.date,
                note: normalizedNote.isEmpty ? nil : normalizedNote
            )
            dismiss()
        }
    }
}

private struct ReminderCompletionRecordRow: View {
    @Environment(\.locale) private var locale

    let completion: ReminderCheckInCompletion

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Label(
                    AppLocalization.string(completion.kind.title),
                    systemImage: completion.kind.systemImage
                )
                .font(.subheadline.weight(.semibold))

                Spacer(minLength: 12)

                Text(dateText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let note = completion.note {
                Text(note)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var dateText: String {
        guard let date = ReminderRecord.date(fromDateKey: completion.dateKey) else {
            return completion.dateKey
        }

        return AppFormatters.date(
            milliseconds: RemindersViewModel.milliseconds(for: date),
            locale: locale
        )
    }
}
