import SwiftUI
import UIKit

struct ReminderFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var note: String
    @State private var memoryIcon: String
    @State private var imageData: Data?
    @State private var scheduledDate: Date
    @State private var endDate: Date
    @State private var reminderTime: Date
    @State private var scheduleKind: ReminderScheduleKind
    @State private var selectedWeekdays: Set<Int>
    @State private var continuousDays: Int
    @State private var importedCompletedDays: Int
    @State private var leadTime: ReminderLeadTime
    @State private var isEnabled: Bool
    @State private var photoInputCoordinator = EditablePhotoInputCoordinator()
    @FocusState private var focusedField: String?

    let reminder: ReminderRecord?
    let viewModel: RemindersViewModel

    init(reminder: ReminderRecord?, viewModel: RemindersViewModel) {
        self.reminder = reminder
        self.viewModel = viewModel
        _title = State(initialValue: reminder?.title ?? "")
        _note = State(initialValue: reminder?.note ?? "")
        _memoryIcon = State(initialValue: reminder?.memoryIcon ?? ReminderMemoryIcon.bell.rawValue)
        _imageData = State(initialValue: reminder?.imageData)
        _scheduledDate = State(initialValue: reminder?.scheduledDate ?? Date().addingTimeInterval(3600))
        _endDate = State(initialValue: reminder?.endDate ?? Calendar.current.date(byAdding: .day, value: 6, to: reminder?.scheduledDate ?? Date().addingTimeInterval(3600)) ?? Date().addingTimeInterval(6 * 86_400))
        _reminderTime = State(initialValue: reminder?.scheduledDate ?? Date().addingTimeInterval(3600))
        _scheduleKind = State(initialValue: reminder?.scheduleKind ?? .dailyGoal)
        _selectedWeekdays = State(initialValue: Set(Self.initialWeekdays(for: reminder)))
        _continuousDays = State(initialValue: max(1, reminder?.continuousDays ?? 30))
        _importedCompletedDays = State(initialValue: max(0, reminder?.importedCompletedDays ?? 0))
        _leadTime = State(initialValue: reminder?.leadTime ?? .none)
        _isEnabled = State(initialValue: reminder?.isEnabled ?? true)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                LabeledContent("标题") {
                    TextField("请输入标题", text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                ReminderIconPickerRow(selection: $memoryIcon)

                VStack(alignment: .leading, spacing: 6) {
                    Text("备注")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入备注", text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }

            Section("打卡类型") {
                Picker("类型", selection: $scheduleKind) {
                    ForEach(ReminderScheduleKind.allCases) { kind in
                        Label(kind.titleKey, systemImage: kind.systemImage)
                            .tag(kind)
                    }
                }

                if scheduleKind == .single {
                    DatePicker(
                        "时间",
                        selection: $scheduledDate,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                } else {
                    DatePicker(
                        "开始日期",
                        selection: $scheduledDate,
                        displayedComponents: [.date]
                    )

                    if scheduleKind == .dateRange {
                        DatePicker(
                            "结束日期",
                            selection: $endDate,
                            displayedComponents: [.date]
                        )
                    }

                    if scheduleKind == .weekly {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("每周")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)

                            weekdayPicker
                        }
                    }

                    DatePicker(
                        "提醒时间",
                        selection: $reminderTime,
                        displayedComponents: [.hourAndMinute]
                    )
                }

                if supportsProgressImport {
                    if supportsEditableTargetDays {
                        LabeledContent("目标总天数") {
                            dayNumberField(value: $continuousDays, range: 1...9999)
                        }
                    }

                    LabeledContent("已打卡天数") {
                        dayNumberField(value: $importedCompletedDays, range: 0...progressTotalDays)
                    }

                    Text(AppLocalization.format("剩余 %d 天", remainingProgressDays))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Picker("提前提醒", selection: $leadTime) {
                    ForEach(ReminderLeadTime.allCases) { leadTime in
                        Text(leadTime.titleKey).tag(leadTime)
                    }
                }

                Toggle("启用通知", isOn: $isEnabled)
            }

            EditablePhotoInputSection(
                imageData: $imageData,
                placeholderSystemImage: "bell.fill",
                coordinator: photoInputCoordinator
            )
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(AppLocalization.string(reminder == nil ? "添加打卡任务" : "编辑打卡任务"))
        .navigationBarTitleDisplayMode(.inline)
        .editablePhotoInputPresentation(
            coordinator: photoInputCoordinator,
            imageData: $imageData
        )
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(isSaveDisabled)
            }
        }
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedMemoryIcon: String {
        let value = memoryIcon.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let firstCharacter = value.first else {
            return ReminderMemoryIcon.bell.rawValue
        }

        return String(firstCharacter)
    }

    private var isSaveDisabled: Bool {
        if trimmedTitle.isEmpty {
            return true
        }

        if scheduleKind == .weekly && selectedWeekdays.isEmpty {
            return true
        }

        if scheduleKind == .dateRange && calendar.startOfDay(for: endDate) < calendar.startOfDay(for: scheduledDate) {
            return true
        }

        if supportsEditableTargetDays && continuousDays < 1 {
            return true
        }

        if supportsProgressImport && (importedCompletedDays < 0 || importedCompletedDays > progressTotalDays) {
            return true
        }

        let record = draftRecord()
        if isEnabled && hasOpenOccurrence == false && record.isCompleted == false && scheduleKind != .continuousDays {
            return true
        }

        return false
    }

    private var hasOpenOccurrence: Bool {
        let record = draftRecord()
        return record.canCheckIn() || record.occurrenceDates(maxCount: 1).isEmpty == false
    }

    private var calendar: Calendar {
        .current
    }

    private var supportsProgressImport: Bool {
        progressTotalDays > 0
    }

    private var supportsEditableTargetDays: Bool {
        switch scheduleKind {
        case .weekdays, .weekly, .continuousDays, .dailyGoal:
            return true
        case .single, .dateRange:
            return false
        }
    }

    private var progressTotalDays: Int {
        switch scheduleKind {
        case .dateRange:
            return numberOfDays(from: scheduledDate, through: endDate)
        case .weekdays, .weekly, .continuousDays, .dailyGoal:
            return max(1, continuousDays)
        case .single:
            return 1
        }
    }

    private var remainingProgressDays: Int {
        max(0, progressTotalDays - normalizedImportedCompletedDays)
    }

    private var weekdayPicker: some View {
        HStack(spacing: 8) {
            ForEach(1...7, id: \.self) { weekday in
                Button {
                    toggleWeekday(weekday)
                } label: {
                    Text(ReminderRecord.weekdayTitle(weekday))
                        .font(.footnote)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(selectedWeekdays.contains(weekday) ? .purple : .secondary)
            }
        }
    }

    private func dayNumberField(value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        TrailingIntegerTextField(
            value: value,
            range: range,
            placeholder: AppLocalization.string("天数")
        )
            .frame(width: 96, alignment: .trailing)
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        let record = draftRecord()

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }

    private func draftRecord() -> ReminderRecord {
        var record = reminder ?? viewModel.makeReminder(
            title: trimmedTitle,
            note: normalizedNote,
            memoryIcon: normalizedMemoryIcon,
            imageData: imageData,
            scheduledDate: scheduledDate,
            leadTime: leadTime
        )

        let normalizedStartDate = normalizedScheduledDate

        record.title = trimmedTitle
        record.note = normalizedNote
        record.memoryIcon = normalizedMemoryIcon
        record.imageData = imageData
        record.scheduledAt = RemindersViewModel.milliseconds(for: normalizedStartDate)
        record.endAt = normalizedEndDate.map(RemindersViewModel.milliseconds)
        record.reminderTimeMinutes = ReminderRecord.minutesSinceStartOfDay(for: reminderTime)
        record.scheduleKindRawValue = scheduleKind.rawValue
        record.weekdaysRawValue = normalizedWeekdaysRawValue
        record.continuousDays = max(1, continuousDays)
        record.importedCompletedDays = normalizedImportedCompletedDays
        record.leadTimeMinutes = leadTime.rawValue
        record.isEnabled = isEnabled
        return record
    }

    private var normalizedScheduledDate: Date {
        if scheduleKind == .single {
            return scheduledDate
        }

        return combinedDate(day: scheduledDate, time: reminderTime) ?? scheduledDate
    }

    private var normalizedEndDate: Date? {
        guard scheduleKind == .dateRange else { return nil }
        return combinedDate(day: endDate, time: reminderTime) ?? endDate
    }

    private var normalizedWeekdaysRawValue: String? {
        guard scheduleKind == .weekly else { return nil }
        return selectedWeekdays.sorted().map(String.init).joined(separator: ",")
    }

    private var normalizedImportedCompletedDays: Int {
        guard supportsProgressImport else { return 0 }
        return min(progressTotalDays, max(0, importedCompletedDays))
    }

    private func combinedDate(day: Date, time: Date) -> Date? {
        let timeComponents = calendar.dateComponents([.hour, .minute], from: time)
        return calendar.date(
            bySettingHour: timeComponents.hour ?? 0,
            minute: timeComponents.minute ?? 0,
            second: 0,
            of: day
        )
    }

    private func toggleWeekday(_ weekday: Int) {
        if selectedWeekdays.contains(weekday) {
            selectedWeekdays.remove(weekday)
        } else {
            selectedWeekdays.insert(weekday)
        }
    }

    private func numberOfDays(from startDate: Date, through endDate: Date) -> Int {
        let startDay = calendar.startOfDay(for: startDate)
        let endDay = calendar.startOfDay(for: endDate)
        let days = calendar.dateComponents([.day], from: startDay, to: endDay).day ?? 0
        return max(1, days + 1)
    }

    private static func initialWeekdays(for reminder: ReminderRecord?) -> [Int] {
        if let weekdays = reminder?.selectedWeekdays, weekdays.isEmpty == false {
            return weekdays
        }

        return [Calendar.current.component(.weekday, from: reminder?.scheduledDate ?? Date())]
    }
}

private struct TrailingIntegerTextField: UIViewRepresentable {
    @Binding var value: Int
    let range: ClosedRange<Int>
    let placeholder: String

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UITextField {
        let textField = TrailingCaretTextField()
        textField.delegate = context.coordinator
        textField.keyboardType = .numberPad
        textField.textAlignment = .right
        textField.font = .preferredFont(forTextStyle: .body)
        textField.adjustsFontForContentSizeCategory = true
        textField.placeholder = placeholder
        textField.text = String(value)
        textField.inputAccessoryView = context.coordinator.makeAccessoryView()
        textField.addTarget(
            context.coordinator,
            action: #selector(Coordinator.textDidChange(_:)),
            for: .editingChanged
        )
        return textField
    }

    func updateUIView(_ textField: UITextField, context: Context) {
        context.coordinator.parent = self
        textField.placeholder = placeholder

        if context.coordinator.isEditing == false {
            let text = String(value)
            if textField.text != text {
                textField.text = text
            }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: TrailingIntegerTextField
        var isEditing = false
        private var isMovingCaret = false

        init(parent: TrailingIntegerTextField) {
            self.parent = parent
        }

        func makeAccessoryView() -> UIView {
            let toolbar = UIToolbar()
            toolbar.sizeToFit()
            toolbar.items = [
                UIBarButtonItem(systemItem: .flexibleSpace),
                UIBarButtonItem(
                    title: AppLocalization.string("完成"),
                    style: .done,
                    target: self,
                    action: #selector(doneEditing)
                )
            ]
            return toolbar
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            isEditing = true
            moveCaretToEnd(textField)
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            isEditing = false
            let clampedValue = clampedValue(for: textField.text)
            parent.value = clampedValue
            textField.text = String(clampedValue)
        }

        func textFieldDidChangeSelection(_ textField: UITextField) {
            guard isEditing, isMovingCaret == false else { return }

            DispatchQueue.main.async { [weak self, weak textField] in
                guard let self, let textField, self.isEditing else { return }
                self.moveCaretToEnd(textField)
            }
        }

        func textField(
            _ textField: UITextField,
            shouldChangeCharactersIn range: NSRange,
            replacementString string: String
        ) -> Bool {
            if string.rangeOfCharacter(from: CharacterSet.decimalDigits.inverted) != nil {
                return false
            }

            let currentText = textField.text ?? ""
            guard let textRange = Range(range, in: currentText) else {
                return false
            }

            let nextText = currentText.replacingCharacters(in: textRange, with: string)
            guard nextText.isEmpty == false else {
                return true
            }

            guard let nextValue = Int(nextText) else {
                return false
            }

            return parent.range.contains(nextValue)
        }

        @objc func textDidChange(_ textField: UITextField) {
            guard let text = textField.text, text.isEmpty == false else {
                return
            }

            parent.value = clampedValue(for: text)
            moveCaretToEnd(textField)
        }

        @objc private func doneEditing() {
            UIApplication.shared.sendAction(
                #selector(UIResponder.resignFirstResponder),
                to: nil,
                from: nil,
                for: nil
            )
        }

        private func clampedValue(for text: String?) -> Int {
            let value = Int(text ?? "") ?? parent.range.lowerBound
            return min(max(value, parent.range.lowerBound), parent.range.upperBound)
        }

        private func moveCaretToEnd(_ textField: UITextField) {
            guard isMovingCaret == false else { return }

            isMovingCaret = true
            textField.selectedTextRange = textField.textRange(
                from: textField.endOfDocument,
                to: textField.endOfDocument
            )
            isMovingCaret = false
        }
    }
}

private final class TrailingCaretTextField: UITextField {
    override func closestPosition(to point: CGPoint) -> UITextPosition? {
        endOfDocument
    }
}
