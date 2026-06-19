import SwiftUI

struct AnniversaryFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var note: String
    @State private var category: AnniversaryCategory
    @State private var occurredDate: Date
    @State private var dateCalendar: AnniversaryDateCalendar
    @State private var reminderTime: Date
    @State private var isYearly: Bool
    @State private var leadTime: ReminderLeadTime
    @State private var isEnabled: Bool
    @FocusState private var focusedField: String?

    let anniversary: AnniversaryRecord?
    let viewModel: AnniversariesViewModel

    init(anniversary: AnniversaryRecord?, viewModel: AnniversariesViewModel) {
        self.anniversary = anniversary
        self.viewModel = viewModel
        _title = State(initialValue: anniversary?.title ?? "")
        _note = State(initialValue: anniversary?.note ?? "")
        _category = State(initialValue: anniversary?.category ?? .other)
        _occurredDate = State(initialValue: anniversary?.occurredDate ?? Date())
        _dateCalendar = State(initialValue: anniversary?.dateCalendar ?? .gregorian)
        _reminderTime = State(initialValue: Self.initialReminderTime(for: anniversary))
        _isYearly = State(initialValue: anniversary?.isYearly ?? true)
        _leadTime = State(initialValue: anniversary?.leadTime ?? .none)
        _isEnabled = State(initialValue: anniversary?.isEnabled ?? true)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                LabeledContent("标题") {
                    TextField("请输入标题", text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                Picker("分类", selection: $category) {
                    ForEach(AnniversaryCategory.allCases) { category in
                        Label(category.titleKey, systemImage: category.systemImage)
                            .tag(category)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("备注")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField("请输入备注", text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }

            Section("日期") {
                Picker("日期类型", selection: $dateCalendar) {
                    ForEach(AnniversaryDateCalendar.allCases) { calendar in
                        Text(calendar.titleKey).tag(calendar)
                    }
                }
                .pickerStyle(.segmented)

                DatePicker("日期", selection: $occurredDate, displayedComponents: .date)
                    .id(dateCalendar)
                    .environment(\.calendar, dateCalendar.calendar)

                Toggle("每年重复", isOn: $isYearly)
            }

            Section("提醒") {
                DatePicker("提醒时间", selection: $reminderTime, displayedComponents: .hourAndMinute)

                Picker("提前提醒", selection: $leadTime) {
                    ForEach(ReminderLeadTime.allCases) { leadTime in
                        Text(leadTime.titleKey).tag(leadTime)
                    }
                }

                Toggle("启用通知", isOn: $isEnabled)
            }
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(AppLocalization.string(anniversary == nil ? "添加纪念日" : "编辑纪念日"))
        .navigationBarTitleDisplayMode(.inline)
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

    private var isSaveDisabled: Bool {
        trimmedTitle.isEmpty
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

    private func draftRecord() -> AnniversaryRecord {
        var record = anniversary ?? viewModel.makeAnniversary(
            title: trimmedTitle,
            note: normalizedNote,
            category: category,
            occurredDate: occurredDate,
            dateCalendar: dateCalendar,
            isYearly: isYearly,
            leadTime: leadTime,
            isEnabled: isEnabled,
            reminderTimeMinutes: AnniversaryRecord.minutesSinceStartOfDay(for: reminderTime)
        )

        record.title = trimmedTitle
        record.note = normalizedNote
        record.categoryRawValue = category.rawValue
        record.occurredAt = AnniversariesViewModel.milliseconds(for: occurredDate)
        record.dateCalendarRawValue = dateCalendar.rawValue
        record.isYearly = isYearly
        record.leadTimeMinutes = leadTime.rawValue
        record.isEnabled = isEnabled
        record.reminderTimeMinutes = AnniversaryRecord.minutesSinceStartOfDay(for: reminderTime)
        return record
    }

    private static func initialReminderTime(for anniversary: AnniversaryRecord?) -> Date {
        let minutes = anniversary?.reminderTimeMinutes ?? 9 * 60
        return Calendar.current.date(
            bySettingHour: minutes / 60,
            minute: minutes % 60,
            second: 0,
            of: Date()
        ) ?? Date()
    }
}
