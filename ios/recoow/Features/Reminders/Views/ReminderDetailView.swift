import SwiftUI

struct ReminderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: RemindersViewModel
    @State private var reminderForEditing: ReminderRecord?
    @State private var reminderForCopying: ReminderRecord?
    @State private var reminderPendingDeletion: ReminderRecord?
    @State private var makeUpRequest: ReminderMakeUpRequest?
    @State private var earlyEndRequest: ReminderEarlyEndRequest?
    @State private var pauseRequest: ReminderPauseRequest?

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
        .appNavigationStyle()
        .appSheet(item: $reminderForEditing) { reminder in
            NavigationStack {
                ReminderFormView(reminder: reminder, viewModel: viewModel)
            }
        }
        .appSheet(item: $reminderForCopying) { reminder in
            NavigationStack {
                ReminderFormView(reminder: nil, copying: reminder, viewModel: viewModel)
            }
        }
        .appSheet(item: $makeUpRequest) { request in
            NavigationStack {
                ReminderMakeUpSheet(request: request, viewModel: viewModel)
            }
        }
        .appSheet(item: $earlyEndRequest) { request in
            NavigationStack {
                ReminderEarlyEndSheet(request: request, viewModel: viewModel)
            }
        }
        .appSheet(item: $pauseRequest) { request in
            NavigationStack {
                ReminderPauseSheet(request: request, viewModel: viewModel)
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
                if reminder.isPaused, let pausedAt = reminder.pausedAt {
                    LabeledContent(
                        "暂停时间",
                        value: AppFormatters.dateTime(milliseconds: pausedAt, locale: locale)
                    )
                }
                if reminder.isPaused, let pauseReason = reminder.pauseReason {
                    LabeledContent("暂停原因", value: pauseReason)
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
        .appGroupedListStyle()
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if reminder.canCheckIn() {
                    Button("打卡", systemImage: "checkmark.circle") {
                        setCompleted(reminder, isCompleted: true)
                    }
                }

                Menu {
                    Button("编辑", systemImage: "square.and.pencil") {
                        reminderForEditing = reminder
                    }

                    if reminder.isTodayCompleted {
                        Button("撤销打卡", systemImage: "arrow.uturn.backward.circle") {
                            undoTodayCheckIn(reminder)
                        }
                    } else if reminder.canRestoreCompletion {
                        Button("恢复", systemImage: "arrow.uturn.backward") {
                            setCompleted(reminder, isCompleted: false)
                        }
                    }

                    if reminder.hasMissedCheckInDates {
                        Button("补签", systemImage: "calendar.badge.plus") {
                            makeUpRequest = ReminderMakeUpRequest(reminder: reminder)
                        }
                    }

                    if reminder.canPause {
                        Button("暂停", systemImage: "pause.circle") {
                            pauseRequest = ReminderPauseRequest(reminder: reminder)
                        }
                    } else if reminder.canResume {
                        Button("继续", systemImage: "play.circle") {
                            resumeReminder(reminder)
                        }
                    }

                    if reminder.canEndEarly {
                        Button("提前结束", systemImage: "stop.circle") {
                            earlyEndRequest = ReminderEarlyEndRequest(reminder: reminder)
                        }
                    }

                    Button("复制", systemImage: "doc.on.doc") {
                        reminderForCopying = reminder
                    }

                    Divider()

                    Button("删除", systemImage: "trash") {
                        reminderPendingDeletion = reminder
                    }
                } label: {
                    Label("更多", systemImage: "ellipsis.circle")
                }
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

    private func resumeReminder(_ reminder: ReminderRecord) {
        Task {
            await viewModel.resume(reminder)
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
                Text("提前结束打卡任务")
            } footer: {
                Text("结束后将停止后续打卡和提醒，已有打卡记录会保留。")
            }
        }
        .appFormStyle()
        .navigationTitle("提前结束")
        .appNavigationStyle()
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

struct ReminderPauseRequest: Identifiable {
    let reminder: ReminderRecord

    var id: String { reminder.id }
}

struct ReminderPauseSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""
    @State private var isConfirmingPause = false
    @FocusState private var isReasonFocused: Bool

    let request: ReminderPauseRequest
    let viewModel: RemindersViewModel

    var body: some View {
        Form {
            Section {
                LabeledContent("任务", value: request.reminder.title)

                TextField("请输入暂停原因", text: $reason, axis: .vertical)
                    .lineLimit(3...6)
                    .focused($isReasonFocused)
            } header: {
                Text("暂停坚持目标")
            } footer: {
                Text("暂停期间将停止后续打卡和提醒，已有打卡记录会保留。")
            }
        }
        .appFormStyle()
        .navigationTitle("暂停")
        .appNavigationStyle()
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
                Button("暂停") {
                    isConfirmingPause = true
                }
                .disabled(normalizedReason.isEmpty)
            }
        }
        .alert("确认暂停？", isPresented: $isConfirmingPause) {
            Button("暂停") {
                pauseGoal()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("暂停后可以随时继续，已有打卡记录会保留。")
        }
    }

    private var normalizedReason: String {
        reason.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func pauseGoal() {
        Task {
            await viewModel.pause(request.reminder, reason: normalizedReason)
            dismiss()
        }
    }
}

struct ReminderMakeUpRequest: Identifiable {
    let reminder: ReminderRecord

    var id: String { reminder.id }
}

struct ReminderMakeUpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var selectedKeys: Set<String> = []
    @State private var note = ""

    let request: ReminderMakeUpRequest
    let viewModel: RemindersViewModel

    private let months: [MakeUpMonth]

    init(request: ReminderMakeUpRequest, viewModel: RemindersViewModel) {
        self.request = request
        self.viewModel = viewModel
        self.months = MakeUpMonth.grouping(request.reminder.missedCheckInDates())
    }

    var body: some View {
        Form {
            Section {
                ForEach(months) { month in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(month.title(locale: locale))
                            .appSectionHeaderStyle()

                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 56), spacing: AppDesign.gridSpacing)],
                            spacing: AppDesign.gridSpacing
                        ) {
                            ForEach(month.days) { day in
                                dayChip(day)
                            }
                        }
                    }
                }
            } header: {
                Text("补签日期")
            } footer: {
                Text("可选范围为任务起始日到昨天。")
            }

            Section {
                TextField("补签备注", text: $note, axis: .vertical)
                    .lineLimit(3...)
            }
        }
        .appFormStyle()
        .navigationTitle(navigationTitleText)
        .appNavigationStyle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") {
                    dismiss()
                }
            }

            ToolbarItem(placement: .primaryAction) {
                Button(AppLocalization.string(isAllSelected ? "取消全选" : "全选")) {
                    toggleAllSelection()
                }
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    save()
                }
                .disabled(selectedKeys.isEmpty)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var navigationTitleText: String {
        selectedKeys.isEmpty
            ? AppLocalization.string("补签")
            : AppLocalization.format("已选择 %d 天", selectedKeys.count)
    }

    private var allKeys: [String] {
        months.flatMap { $0.days.map(\.key) }
    }

    private var isAllSelected: Bool {
        allKeys.isEmpty == false && selectedKeys.count == allKeys.count
    }

    private func toggleAllSelection() {
        selectedKeys = isAllSelected ? [] : Set(allKeys)
    }

    private func dayChip(_ day: MakeUpDay) -> some View {
        let isSelected = selectedKeys.contains(day.key)

        return Button {
            if isSelected {
                selectedKeys.remove(day.key)
            } else {
                selectedKeys.insert(day.key)
            }
        } label: {
            VStack(spacing: 2) {
                Text(String(day.dayNumber))
                    .font(AppDesign.summaryValueFont)
                    .monospacedDigit()
                    .foregroundStyle(isSelected ? AppPalette.accent : AppPalette.ink)

                Text(day.date.formatted(.dateTime.weekday(.narrow).locale(locale)))
                    .font(AppDesign.summaryTitleFont)
                    .foregroundStyle(isSelected ? AppPalette.accent : AppPalette.secondaryInk)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(isSelected ? AppPalette.accent : AppPalette.secondaryInk)
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                    .stroke(AppPalette.accent, lineWidth: 2)
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func save() {
        let normalizedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let dates = months.flatMap(\.days).filter { selectedKeys.contains($0.key) }.map(\.date)

        Task {
            await viewModel.makeUp(
                request.reminder,
                dates: dates,
                note: normalizedNote.isEmpty ? nil : normalizedNote
            )
            dismiss()
        }
    }
}

private struct MakeUpDay: Identifiable {
    let date: Date
    let key: String
    let dayNumber: Int

    var id: String { key }
}

private struct MakeUpMonth: Identifiable {
    let id: String
    let titleDate: Date
    var days: [MakeUpDay]

    func title(locale: Locale) -> String {
        titleDate.formatted(.dateTime.year().month(.wide).locale(locale))
    }

    /// 候选日期按起始日升序且逐日连续，因此只需和上一组比较年月。
    static func grouping(_ dates: [Date], calendar: Calendar = .current) -> [MakeUpMonth] {
        var months: [MakeUpMonth] = []

        for date in dates {
            let components = calendar.dateComponents([.year, .month, .day], from: date)
            let year = components.year ?? 0
            let month = components.month ?? 0
            let id = "\(year)-\(month)"
            let day = MakeUpDay(
                date: date,
                key: ReminderRecord.dateKey(for: date, calendar: calendar),
                dayNumber: components.day ?? 0
            )

            if months.last?.id == id {
                months[months.count - 1].days.append(day)
            } else {
                months.append(MakeUpMonth(id: id, titleDate: date, days: [day]))
            }
        }

        return months
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
