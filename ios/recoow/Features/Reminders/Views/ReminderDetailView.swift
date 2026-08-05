import SwiftUI

struct ReminderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: RemindersViewModel
    @State private var reminderForEditing: ReminderRecord?
    @State private var reminderForCopying: ReminderRecord?
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
        .sheet(item: $reminderForCopying) { reminder in
            NavigationStack {
                ReminderFormView(reminder: nil, copying: reminder, viewModel: viewModel)
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
        if reminder.hasImage, let reminderImageTransition {
            form(for: reminder)
                .navigationTransition(.zoom(sourceID: reminderID, in: reminderImageTransition))
        } else {
            form(for: reminder)
        }
    }

    private func form(for reminder: ReminderRecord) -> some View {
        List {
            if reminder.hasImage {
                Section("图片") {
                    PhotoSquareImageView(imageData: reminder.resolvedImageData, systemImage: "bell.fill")
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
                if let endedEarlyAt = reminder.endedEarlyAt, reminder.isEndedEarly {
                    LabeledContent(
                        "提前结束时间",
                        value: AppFormatters.dateTime(milliseconds: endedEarlyAt, locale: locale)
                    )
                }
                if let earlyEndReason = reminder.earlyEndReason, reminder.isEndedEarly {
                    LabeledContent("提前结束原因", value: earlyEndReason)
                }
                if let progressText = reminder.progressText {
                    LabeledContent("进度", value: progressText)
                }
                if let progressRemainingDays = reminder.progressRemainingDays, reminder.isEndedEarly == false {
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

            if reminder.hasImage == false || reminder.note != nil {
                Section("记忆") {
                    if reminder.hasImage == false {
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
                Button("编辑", systemImage: "square.and.pencil") {
                    reminderForEditing = reminder
                }

                Menu {
                    if let missedDate = reminder.firstMissedCheckInDate() {
                        Button("补签", systemImage: "calendar.badge.plus") {
                            makeUpRequest = ReminderMakeUpRequest(reminder: reminder, date: missedDate)
                        }
                    }

                    Button("复制", systemImage: "doc.on.doc") {
                        reminderForCopying = reminder
                    }

                    Button("删除", systemImage: "trash", role: .destructive) {
                        reminderPendingDeletion = reminder
                    }
                } label: {
                    Label("更多", systemImage: "ellipsis.circle")
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
        reminder.isTodayCompleted || reminder.canRestoreCompletion || reminder.canCheckIn()
    }

    @ViewBuilder
    private func bottomActions(for reminder: ReminderRecord) -> some View {
        bottomActionContainer {
            if reminder.isTodayCompleted {
                undoButton(for: reminder)
            } else if reminder.canRestoreCompletion {
                Button("恢复", systemImage: "arrow.uturn.backward") {
                    setCompleted(reminder, isCompleted: false)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            } else {
                if reminder.canCheckIn() {
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

struct ReminderEarlyEndRequest: Identifiable {
    let reminder: ReminderRecord

    var id: String { reminder.id }
}

struct ReminderEarlyEndSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""
    @State private var isConfirmingEarlyEnd = false
    @FocusState private var isReasonFocused: Bool

    let request: ReminderEarlyEndRequest
    let viewModel: RemindersViewModel

    var body: some View {
        Form {
            Section {
                LabeledContent("任务", value: request.reminder.title)

                TextField("请输入提前结束原因", text: $reason, axis: .vertical)
                    .lineLimit(3...6)
                    .focused($isReasonFocused)
            } header: {
                Text("提前结束连续挑战")
            } footer: {
                Text("结束后将停止后续打卡和提醒，已有打卡记录会保留。")
            }
        }
        .navigationTitle("提前结束")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            isReasonFocused = true
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("提前结束", role: .destructive) {
                    isConfirmingEarlyEnd = true
                }
                .disabled(normalizedReason.isEmpty)
            }
        }
        .alert("确认提前结束？", isPresented: $isConfirmingEarlyEnd) {
            Button("提前结束", role: .destructive) {
                endEarly()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("提前结束后无法恢复，已有打卡记录会保留。")
        }
    }

    private var normalizedReason: String {
        reason.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func endEarly() {
        Task {
            await viewModel.endEarly(request.reminder, reason: normalizedReason)
            dismiss()
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
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
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
