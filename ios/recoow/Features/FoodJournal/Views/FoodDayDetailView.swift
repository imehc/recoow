import SwiftUI

struct FoodDayDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: FoodJournalViewModel
    @Bindable var billsViewModel: BillsViewModel
    @State private var presentedSheet: Sheet?
    @State private var entryPendingDeletion: FoodEntry?
    @State private var isConfirmingDayDeletion = false

    let dayStart: Date
    let billImageTransition: Namespace.ID

    private enum Sheet: Identifiable {
        case addEntry(Date)
        case editEntry(FoodEntry)
        case editDayTitle(Date)

        var id: String {
            switch self {
            case .addEntry(let date):
                "add:\(date.timeIntervalSince1970)"
            case .editEntry(let entry):
                "edit:\(entry.id)"
            case .editDayTitle(let date):
                "title:\(date.timeIntervalSince1970)"
            }
        }
    }

    var body: some View {
        Group {
            if let group = viewModel.dayGroup(for: dayStart) {
                list(for: group)
            } else {
                ContentUnavailableView(AppLocalization.string("当天没有饮食记录"), systemImage: "fork.knife")
            }
        }
        .navigationTitle(dayTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(AppLocalization.string("删除"), systemImage: "trash", role: .destructive) {
                    isConfirmingDayDeletion = true
                }
                .disabled(viewModel.entries(for: dayStart).isEmpty)
                .tint(.red)

                Button(AppLocalization.string("添加饮食"), systemImage: "plus") {
                    presentedSheet = .addEntry(dayStart)
                }
            }
        }
        .sheet(item: $presentedSheet) { sheet in
            switch sheet {
            case .addEntry(let date):
                NavigationStack {
                    FoodEntryFormView(
                        entry: nil,
                        attachments: [],
                        viewModel: viewModel,
                        billsViewModel: billsViewModel,
                        initialDate: date
                    )
                }
            case .editEntry(let entry):
                NavigationStack {
                    FoodEntryFormView(
                        entry: entry,
                        attachments: viewModel.attachments(for: entry.id),
                        viewModel: viewModel,
                        billsViewModel: billsViewModel
                    )
                }
            case .editDayTitle(let date):
                NavigationStack {
                    FoodDayTitleFormView(
                        dayStart: date,
                        currentTitle: viewModel.dayTitle(for: date),
                        viewModel: viewModel
                    )
                }
                .presentationDetents([.height(220), .medium])
                .presentationDragIndicator(.visible)
            }
        }
        .task(id: linkedBillIDsKey) {
            await loadLinkedBillsIfNeeded()
        }
        .alert(AppLocalization.string("删除这一天的饮食记录？"), isPresented: $isConfirmingDayDeletion) {
            Button(AppLocalization.string("删除"), role: .destructive, action: deleteDayEntries)
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("删除后该日期下的饮食条目都会从历史中移除。"))
        }
        .alert(
            entryPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
            isPresented: .isPresent($entryPendingDeletion),
            presenting: entryPendingDeletion
        ) { entry in
            Button(AppLocalization.string("删除"), role: .destructive) {
                deleteEntry(entry)
            }
            Button(AppLocalization.string("取消"), role: .cancel) {
                entryPendingDeletion = nil
            }
        } message: { _ in
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private func list(for group: FoodDayGroup) -> some View {
        List {
            Section(AppLocalization.string("概览")) {
                LabeledContent(AppLocalization.string("标题")) {
                    Button {
                        presentedSheet = .editDayTitle(group.date)
                    } label: {
                        HStack(spacing: 6) {
                            Text(group.title ?? AppLocalization.string("设置标题"))
                                .lineLimit(1)

                            Image(systemName: "square.and.pencil")
                                .font(.footnote.weight(.semibold))
                        }
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.brown)
                }

                if group.title != nil {
                    LabeledContent(AppLocalization.string("日期"), value: dateTitle)
                }

                LabeledContent(AppLocalization.string("记录数"), value: AppLocalization.format("%d 条", group.entryCount))

                if mealKindsText.isEmpty == false {
                    LabeledContent(AppLocalization.string("餐别")) {
                        Text(mealKindsText)
                            .multilineTextAlignment(.trailing)
                    }
                }

                let photoCount = viewModel.attachmentCount(for: group)
                if photoCount > 0 {
                    LabeledContent(AppLocalization.string("照片"), value: AppLocalization.format("%d 张照片", photoCount))
                }

                let billCount = linkedBillCount(for: group)
                if billCount > 0 {
                    LabeledContent(AppLocalization.string("关联账单"), value: AppLocalization.format("%d 个账单", billCount))
                }
            }

            ForEach(group.mealKinds) { kind in
                let entries = group.entries(for: kind)

                if entries.isEmpty == false {
                    Section(AppLocalization.string(kind.title)) {
                        ForEach(entries) { entry in
                            NavigationLink {
                                FoodEntryDetailView(
                                    viewModel: viewModel,
                                    billsViewModel: billsViewModel,
                                    entryID: entry.id,
                                    billImageTransition: billImageTransition
                                )
                            } label: {
                                FoodEntryRow(
                                    entry: entry,
                                    attachments: viewModel.attachments(for: entry.id),
                                    linkedBills: linkedBills(for: entry)
                                )
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    entryPendingDeletion = entry
                                } label: {
                                    Label(AppLocalization.string("删除"), systemImage: "trash")
                                }
                                .tint(.red)

                                Button {
                                    presentedSheet = .editEntry(entry)
                                } label: {
                                    Label(AppLocalization.string("编辑"), systemImage: "square.and.pencil")
                                }
                                .tint(.blue)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var dateTitle: String {
        dayStart.formatted(
            Date.FormatStyle(date: .abbreviated, time: .omitted)
                .locale(locale)
        )
    }

    private var dayTitle: String {
        viewModel.dayTitle(for: dayStart) ?? dateTitle
    }

    private var mealKindsText: String {
        guard let group = viewModel.dayGroup(for: dayStart) else { return "" }
        return group.mealKinds
            .map(\.localizedTitle)
            .joined(separator: AppLocalization.string("列表分隔符"))
    }

    private var linkedBillIDsKey: String {
        viewModel.entries(for: dayStart)
            .flatMap(\.billIDs)
            .sorted()
            .joined(separator: "|")
    }

    private func linkedBills(for entry: FoodEntry) -> [BillRecord] {
        entry.billIDs.compactMap { billsViewModel.bill(id: $0) }
    }

    private func linkedBillCount(for group: FoodDayGroup) -> Int {
        Set(group.entries.flatMap(\.billIDs)).count
    }

    private func loadLinkedBillsIfNeeded() async {
        for id in Set(viewModel.entries(for: dayStart).flatMap(\.billIDs)) {
            await billsViewModel.loadBillIfNeeded(id: id)
        }
    }

    private func deleteDayEntries() {
        Task {
            await viewModel.deleteDay(dayStart: dayStart)
            dismiss()
        }
    }

    private func deleteEntry(_ entry: FoodEntry) {
        entryPendingDeletion = nil

        Task {
            await viewModel.deleteEntry(id: entry.id)
        }
    }
}

private struct FoodDayTitleFormView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title: String

    let dayStart: Date
    let currentTitle: String?
    let viewModel: FoodJournalViewModel

    init(dayStart: Date, currentTitle: String?, viewModel: FoodJournalViewModel) {
        self.dayStart = dayStart
        self.currentTitle = currentTitle
        self.viewModel = viewModel
        _title = State(initialValue: currentTitle ?? "")
    }

    var body: some View {
        Form {
            Section(AppLocalization.string("当天标题")) {
                TextField(AppLocalization.string("请输入标题"), text: $title)
            }
        }
        .navigationTitle(AppLocalization.string(currentTitle == nil ? "设置标题" : "编辑标题"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消"), action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("保存"), action: save)
                    .disabled(hasChanges == false)
            }
        }
    }

    private var originalTitle: String {
        currentTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var hasChanges: Bool {
        trimmedTitle != originalTitle
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        guard hasChanges else {
            dismiss()
            return
        }

        Task {
            if await viewModel.saveDayTitle(dayStart: dayStart, title: trimmedTitle) {
                dismiss()
            }
        }
    }
}
