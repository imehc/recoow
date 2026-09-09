import SwiftUI

struct FoodDayDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Bindable var viewModel: FoodJournalViewModel
    @Bindable var billsViewModel: BillsViewModel
    @State private var presentedSheet: Sheet?
    @State private var entryPendingDeletion: FoodEntry?
    @State private var mealCopyRequest: FoodMealCopyRequest?
    @State private var copiedMealCount = 0
    @State private var isConfirmingDayDeletion = false

    let dayStart: Date
    let billImageTransition: Namespace.ID

    private enum Sheet: Identifiable {
        case addEntry(Date)
        case copyEntry(FoodEntry)
        case editDayTitle(Date)

        var id: String {
            switch self {
            case .addEntry(let date):
                "add:\(date.timeIntervalSince1970)"
            case .copyEntry(let entry):
                "copy:\(entry.id)"
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
        .appNavigationStyle()
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(AppLocalization.string("添加饮食"), systemImage: "plus") {
                    presentedSheet = .addEntry(dayStart)
                }

                Menu {
                    Button(AppLocalization.string("删除"), systemImage: "trash", role: .destructive) {
                        isConfirmingDayDeletion = true
                    }
                    .disabled(viewModel.entries(for: dayStart).isEmpty)
                } label: {
                    Label(AppLocalization.string("更多"), systemImage: "ellipsis.circle")
                }
            }
        }
        .appSheet(item: $presentedSheet) { sheet in
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
            case .copyEntry(let entry):
                NavigationStack {
                    FoodEntryFormView(
                        entry: nil,
                        attachments: [],
                        viewModel: viewModel,
                        billsViewModel: billsViewModel,
                        copying: entry
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
        .alert(
            mealCopyRequest.map { AppLocalization.format("复制“%@”到今天？", $0.kind.localizedTitle) } ?? "",
            isPresented: .isPresent($mealCopyRequest),
            presenting: mealCopyRequest
        ) { request in
            Button(AppLocalization.string("复制")) {
                copyMeal(request)
            }
            Button(AppLocalization.string("取消"), role: .cancel) {
                mealCopyRequest = nil
            }
        } message: { request in
            Text(AppLocalization.format("将复制 %d 条记录到今天，时间保持不变。", request.entries.count))
        }
        // 复制结果落在今天，当前页面看不到变化，用触感反馈确认已完成
        .sensoryFeedback(.success, trigger: copiedMealCount)
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
                    Section {
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
                                    presentedSheet = .copyEntry(entry)
                                } label: {
                                    Label(AppLocalization.string("复制"), systemImage: "doc.on.doc")
                                }
                                .tint(AppPalette.accent)

                                Button {
                                    entryPendingDeletion = entry
                                } label: {
                                    Label(AppLocalization.string("删除"), systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    } header: {
                        FoodMealSectionHeader(
                            kind: kind,
                            entryCount: entries.count,
                            onCopyToToday: canCopyToToday ? { requestMealCopy(kind: kind, entries: entries) } : nil
                        )
                    }
                }
            }
        }
        .appGroupedListStyle()
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

    /// 只有查看过去的日期时才提供“复制到今天”，今天本身没有复制目标。
    private var canCopyToToday: Bool {
        Calendar.current.isDateInToday(dayStart) == false
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

    private func requestMealCopy(kind: FoodMealKind, entries: [FoodEntry]) {
        mealCopyRequest = FoodMealCopyRequest(kind: kind, entries: entries)
    }

    private func copyMeal(_ request: FoodMealCopyRequest) {
        mealCopyRequest = nil

        Task {
            if await viewModel.duplicateEntries(request.entries) {
                copiedMealCount += 1
            }
        }
    }
}

private struct FoodMealCopyRequest: Identifiable {
    let kind: FoodMealKind
    let entries: [FoodEntry]

    var id: String { kind.rawValue }
}

private struct FoodMealSectionHeader: View {
    let kind: FoodMealKind
    let entryCount: Int
    let onCopyToToday: (() -> Void)?

    var body: some View {
        HStack(spacing: 8) {
            Text(AppLocalization.string(kind.title))

            if entryCount > 1 {
                Text(AppLocalization.format("%d 条", entryCount))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            if let onCopyToToday {
                Button {
                    onCopyToToday()
                } label: {
                    MetadataItemView(titleKey: "复制到今天", systemImage: "doc.on.doc")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.brown)
                .accessibilityLabel(AppLocalization.format("复制“%@”到今天", kind.localizedTitle))
            }
        }
        .textCase(nil)
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
        .appFormStyle()
        .navigationTitle(AppLocalization.string(currentTitle == nil ? "设置标题" : "编辑标题"))
        .appNavigationStyle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(AppLocalization.string("取消"), action: cancel)
            }

            ToolbarItem(placement: .confirmationAction) {
                // 保存按钮不随输入启用/禁用：输入首字时切换 disabled 会重建键盘工具栏，中文输入的拼写会被打断
                Button(AppLocalization.string("保存"), action: save)
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
