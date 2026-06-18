import SwiftUI

struct ReminderFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var note: String
    @State private var memoryIcon: String
    @State private var imageData: Data?
    @State private var scheduledDate: Date
    @State private var leadTime: ReminderLeadTime
    @State private var isEnabled: Bool
    @State private var photoInputCoordinator = EditablePhotoInputCoordinator()

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
        _leadTime = State(initialValue: reminder?.leadTime ?? .none)
        _isEnabled = State(initialValue: reminder?.isEnabled ?? true)
    }

    var body: some View {
        Form {
            Section("基础信息") {
                TextField("标题", text: $title)

                ReminderIconPickerRow(selection: $memoryIcon)

                TextField("备注", text: $note, axis: .vertical)
                    .lineLimit(3...)
            }

            Section("提醒时间") {
                DatePicker(
                    "时间",
                    selection: $scheduledDate,
                    displayedComponents: [.date, .hourAndMinute]
                )

                Picker("提前提醒", selection: $leadTime) {
                    ForEach(ReminderLeadTime.allCases) { leadTime in
                        Text(leadTime.title).tag(leadTime)
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
        .navigationTitle(reminder == nil ? "添加提醒" : "编辑提醒")
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
        trimmedTitle.isEmpty || (isEnabled && scheduledDate <= Date())
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        var record = reminder ?? viewModel.makeReminder(
            title: trimmedTitle,
            note: normalizedNote,
            memoryIcon: normalizedMemoryIcon,
            imageData: imageData,
            scheduledDate: scheduledDate,
            leadTime: leadTime
        )

        record.title = trimmedTitle
        record.note = normalizedNote
        record.memoryIcon = normalizedMemoryIcon
        record.imageData = imageData
        record.scheduledAt = RemindersViewModel.milliseconds(for: scheduledDate)
        record.leadTimeMinutes = leadTime.rawValue
        record.isEnabled = isEnabled

        Task {
            await viewModel.save(record)
            dismiss()
        }
    }
}
