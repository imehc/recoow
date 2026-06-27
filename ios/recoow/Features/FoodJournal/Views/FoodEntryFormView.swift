import SwiftUI
import UIKit

struct FoodEntryFormView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var mealKind: FoodMealKind
    @State private var portion: String
    @State private var note: String
    @State private var occurredDate: Date
    @State private var selectedAttachments: [MediaAttachment]
    @State private var selectedBillID: String?
    @State private var presentedSheet: PresentedSheet?
    @State private var previewAttachment: MediaAttachment?
    @State private var isShowingPhotoPicker = false
    @State private var photoErrorMessage: String?
    @State private var attachmentDragCoordinator = MediaAttachmentDragCoordinator()
    @State private var draftID: String
    @FocusState private var focusedField: String?

    let entry: FoodEntry?
    let viewModel: FoodJournalViewModel
    let billsViewModel: BillsViewModel

    private enum PresentedSheet: Identifiable {
        case billSelection

        var id: String {
            switch self {
            case .billSelection:
                "billSelection"
            }
        }
    }

    init(
        entry: FoodEntry?,
        attachments: [MediaAttachment],
        viewModel: FoodJournalViewModel,
        billsViewModel: BillsViewModel,
        initialDate: Date = Date()
    ) {
        self.entry = entry
        self.viewModel = viewModel
        self.billsViewModel = billsViewModel

        let initialOccurredDate = entry?.occurredDate ?? Self.defaultOccurredDate(for: initialDate)
        _title = State(initialValue: entry?.title ?? "")
        _mealKind = State(initialValue: entry?.foodMealKind ?? Self.defaultMealKind(for: initialOccurredDate))
        _portion = State(initialValue: entry?.portion ?? "")
        _note = State(initialValue: entry?.note ?? "")
        _occurredDate = State(initialValue: initialOccurredDate)
        _selectedAttachments = State(initialValue: attachments)
        _selectedBillID = State(initialValue: entry?.billID)
        _draftID = State(initialValue: entry?.id ?? UUID().uuidString)
    }

    var body: some View {
        Form {
            let suggestions = viewModel.recentFoodSuggestions(excluding: entry?.id)

            if suggestions.isEmpty == false {
                Section(AppLocalization.string("常用饮食")) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(suggestions) { suggestion in
                                Button {
                                    applySuggestion(suggestion)
                                } label: {
                                    FoodEntrySuggestionChip(suggestion: suggestion)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            Section(AppLocalization.string("基础信息")) {
                LabeledContent(AppLocalization.string("食物")) {
                    TextField(AppLocalization.string("请输入食物"), text: $title)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "title")
                }

                Picker(AppLocalization.string("餐别"), selection: $mealKind) {
                    ForEach(FoodMealKind.allCases) { kind in
                        Label(AppLocalization.string(kind.title), systemImage: kind.systemImage)
                            .tag(kind)
                    }
                }

                DatePicker(
                    AppLocalization.string("时间"),
                    selection: $occurredDate,
                    displayedComponents: [.date, .hourAndMinute]
                )
            }

            Section(AppLocalization.string("份量与备注")) {
                LabeledContent(AppLocalization.string("份量")) {
                    TextField(AppLocalization.string("请输入份量"), text: $portion)
                        .multilineTextAlignment(.trailing)
                        .focused($focusedField, equals: "portion")
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(AppLocalization.string("备注"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField(AppLocalization.string("请输入备注"), text: $note, axis: .vertical)
                        .lineLimit(3...)
                        .focused($focusedField, equals: "note")
                }
            }

            Section(AppLocalization.string("照片")) {
                MediaAttachmentPhotoGridView(
                    photos: photoAttachments,
                    onPreview: showAttachmentPreview,
                    onRemove: removeAttachment,
                    onSwap: swapPhoto,
                    dragCoordinator: attachmentDragCoordinator,
                    onAdd: showPhotoPicker
                )
            }

            Section {
                if let selectedBill {
                    FoodSelectedBillRow(bill: selectedBill, isSelected: true)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                selectedBillID = nil
                            } label: {
                                Label(AppLocalization.string("移除"), systemImage: "minus.circle")
                            }
                            .tint(.red)
                        }
                        .onTapGesture {
                            showBillSelection()
                        }
                } else if selectedBillID != nil {
                    HStack(spacing: 12) {
                        AppIconTileView(
                            systemImage: "receipt",
                            tint: .teal,
                            size: AppDesign.compactIconSize,
                            backgroundOpacity: 0.12
                        )

                        Text(AppLocalization.string("账单同步中"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        Spacer(minLength: 8)

                        Button(AppLocalization.string("移除"), systemImage: "minus.circle", role: .destructive) {
                            selectedBillID = nil
                        }
                        .labelStyle(.iconOnly)
                    }
                } else {
                    Button(AppLocalization.string("选择账单"), systemImage: "link") {
                        showBillSelection()
                    }
                }
            } header: {
                HStack(spacing: 8) {
                    Text(AppLocalization.string("关联账单"))

                    if selectedBillID != nil {
                        Text(AppLocalization.string("已关联"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    Spacer(minLength: 12)
                }
                .textCase(nil)
            }
        }
        .overlay {
            MediaAttachmentDragLayer(coordinator: attachmentDragCoordinator)
        }
        .dismissesKeyboardOnTap(focusedField: $focusedField)
        .navigationTitle(AppLocalization.string(entry == nil ? "添加饮食" : "编辑饮食"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消"), action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存"), action: save)
                    .disabled(isSaveDisabled)
            }
        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .billSelection:
                NavigationStack {
                    FoodBillSelectionView(
                        billsViewModel: billsViewModel,
                        selectedBillID: $selectedBillID
                    )
                }
                .presentationDragIndicator(.visible)
            }
        }
        .fullScreenCover(item: $previewAttachment) { attachment in
            MediaAttachmentPreviewView(
                attachment: attachment,
                attachments: photoAttachments
            )
        }
        .fullScreenCover(isPresented: $isShowingPhotoPicker) {
            PhotoSourcePickerView(
                onPhotoPicked: addPhotoAttachment,
                mediaAssetRepository: container.mediaAssetRepository,
                onMediaAssetPicked: addPhotoAttachment,
                onClose: closePhotoPicker
            )
        }
        .alert(AppLocalization.string("无法添加照片"), isPresented: .isPresent($photoErrorMessage)) {
            Button(AppLocalization.string("确定"), role: .cancel) {
                photoErrorMessage = nil
            }
        } message: {
            Text(photoErrorMessage ?? "")
        }
        .task(id: selectedBillID) {
            await loadSelectedBillIfNeeded()
        }
    }

    private var foodEntryID: String {
        entry?.id ?? draftID
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedPortion: String? {
        let value = portion.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private var isSaveDisabled: Bool {
        trimmedTitle.isEmpty
    }

    private var photoAttachments: [MediaAttachment] {
        selectedAttachments.filter { $0.kind == .photo }
    }

    private var selectedBill: BillRecord? {
        guard let selectedBillID else { return nil }
        return billsViewModel.bill(id: selectedBillID)
    }

    private func cancel() {
        dismiss()
    }

    private func applySuggestion(_ suggestion: FoodEntrySuggestion) {
        title = suggestion.title
        mealKind = suggestion.mealKind
        portion = suggestion.portion ?? ""
    }

    private func showAttachmentPreview(_ attachment: MediaAttachment) {
        previewAttachment = attachment
    }

    private func showPhotoPicker() {
        isShowingPhotoPicker = true
    }

    private func closePhotoPicker() {
        isShowingPhotoPicker = false
    }

    private func showBillSelection() {
        presentedSheet = .billSelection
    }

    private func addPhotoAttachment(_ data: Data) {
        let image = UIImage(data: data)
        selectedAttachments.append(
            MediaAttachment.makeNew(
                ownerType: .foodEntry,
                ownerID: foodEntryID,
                kind: .photo,
                title: nil,
                data: data,
                mimeType: "image/jpeg",
                width: image.map { Int($0.size.width) },
                height: image.map { Int($0.size.height) },
                deviceID: viewModel.deviceID
            )
        )
    }

    private func addPhotoAttachment(_ asset: MediaAsset) {
        selectedAttachments.append(
            MediaAttachment.makeNew(
                ownerType: .foodEntry,
                ownerID: foodEntryID,
                kind: .photo,
                title: nil,
                mimeType: asset.mimeType,
                width: asset.width,
                height: asset.height,
                assetID: asset.id,
                deviceID: viewModel.deviceID
            )
        )
    }

    private func removeAttachment(_ attachment: MediaAttachment) {
        selectedAttachments.removeAll { $0.id == attachment.id }
    }

    private func swapPhoto(_ source: MediaAttachment, with target: MediaAttachment) {
        guard source.id != target.id else { return }

        var photos = photoAttachments
        guard let sourceIndex = photos.firstIndex(where: { $0.id == source.id }),
              let targetIndex = photos.firstIndex(where: { $0.id == target.id }) else {
            return
        }

        photos.swapAt(sourceIndex, targetIndex)
        replacePhotoAttachments(with: photos)
    }

    private func replacePhotoAttachments(with photos: [MediaAttachment]) {
        guard photos.count == photoAttachments.count else { return }

        var remainingPhotos = photos
        selectedAttachments = selectedAttachments.map { attachment in
            guard attachment.kind == .photo, remainingPhotos.isEmpty == false else {
                return attachment
            }

            return remainingPhotos.removeFirst()
        }
    }

    private func loadSelectedBillIfNeeded() async {
        guard let selectedBillID else { return }
        await billsViewModel.loadBillIfNeeded(id: selectedBillID)
    }

    private func save() {
        var record = entry ?? viewModel.makeEntry(
            title: trimmedTitle,
            mealKind: mealKind,
            portion: normalizedPortion,
            note: normalizedNote,
            billID: selectedBillID,
            occurredDate: occurredDate
        )

        record.id = foodEntryID
        record.title = trimmedTitle
        record.mealKind = mealKind.rawValue
        record.portion = normalizedPortion
        record.note = normalizedNote
        record.billID = selectedBillID
        record.occurredAt = FoodJournalViewModel.milliseconds(for: occurredDate)

        Task {
            if await viewModel.save(record, attachments: selectedAttachments) {
                dismiss()
            }
        }
    }

    private static func defaultOccurredDate(for date: Date) -> Date {
        let calendar = Calendar.current

        if calendar.isDateInToday(date) {
            return Date()
        }

        let timeComponents = calendar.dateComponents([.hour, .minute], from: Date())
        return calendar.date(
            bySettingHour: timeComponents.hour ?? 12,
            minute: timeComponents.minute ?? 0,
            second: 0,
            of: date
        ) ?? date
    }

    private static func defaultMealKind(for date: Date) -> FoodMealKind {
        let hour = Calendar.current.component(.hour, from: date)

        switch hour {
        case 5..<10:
            return .breakfast
        case 10..<15:
            return .lunch
        case 15..<18:
            return .snack
        case 18..<22:
            return .dinner
        case 22...23, 0..<5:
            return .lateNightSnack
        default:
            return .other
        }
    }
}

private struct FoodEntrySuggestionChip: View {
    let suggestion: FoodEntrySuggestion

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Label {
                Text(suggestion.title)
                    .lineLimit(1)
            } icon: {
                Image(systemName: suggestion.mealKind.systemImage)
                    .foregroundStyle(suggestion.mealKind.tint)
            }
            .font(.subheadline.weight(.semibold))

            HStack(spacing: 6) {
                if let portion = suggestion.portion {
                    Text(portion)
                }

                Text(AppLocalization.format("%d 次", suggestion.useCount))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 160, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(Color(.separator).opacity(0.4), lineWidth: 1)
        }
    }
}
