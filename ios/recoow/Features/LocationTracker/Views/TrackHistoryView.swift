import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var historyViewModel: HistoryViewModel?
    @State private var trackViewModel: TrackHistoryViewModel?
    @State private var decisionHistoryViewModel: DecisionChoiceHistoryViewModel?
    @State private var itemLocatorViewModel: ItemLocatorViewModel?
    @State private var remindersViewModel: RemindersViewModel?
    @State private var billsViewModel: BillsViewModel?
    @State private var foodJournalViewModel: FoodJournalViewModel?
    @State private var diaryViewModel: DiaryViewModel?
    @State private var anniversariesViewModel: AnniversariesViewModel?
    @Namespace private var choiceRecordImageTransition
    @Namespace private var itemImageTransition
    @Namespace private var reminderImageTransition
    @Namespace private var billImageTransition

    var body: some View {
        let language = container.appPreferences.language

        Group {
            if let historyViewModel,
               let trackViewModel,
               let decisionHistoryViewModel,
               let itemLocatorViewModel,
               let remindersViewModel,
               let billsViewModel,
               let foodJournalViewModel,
               let diaryViewModel,
               let anniversariesViewModel {
                TrackHistoryContent(
                    historyViewModel: historyViewModel,
                    viewModel: trackViewModel,
                    decisionHistoryViewModel: decisionHistoryViewModel,
                    itemLocatorViewModel: itemLocatorViewModel,
                    remindersViewModel: remindersViewModel,
                    billsViewModel: billsViewModel,
                    foodJournalViewModel: foodJournalViewModel,
                    diaryViewModel: diaryViewModel,
                    anniversariesViewModel: anniversariesViewModel,
                    choiceRecordImageTransition: choiceRecordImageTransition,
                    itemImageTransition: itemImageTransition,
                    reminderImageTransition: reminderImageTransition,
                    billImageTransition: billImageTransition,
                    activeTrackID: container.locationTrackerViewModel.currentTrackID,
                    activeElapsedSeconds: container.locationTrackerViewModel.elapsedSeconds,
                    activePointCount: container.locationTrackerViewModel.pointCount,
                    activeDistanceMeters: container.locationTrackerViewModel.currentDistanceMeters,
                    isRecording: container.locationTrackerViewModel.isRecording,
                    filterRequest: container.historyFilterRequest,
                    clearFilter: {
                        container.historyFilterRequest = nil
                    }
                )
            } else {
                ProgressView(AppLocalization.string("正在加载"))
            }
        }
        .navigationTitle(AppLocalization.string("历史", language: language))
        .navigationDestination(for: HistoryDetailRoute.self) { route in
            historyDestination(for: route)
                .toolbar(.hidden, for: .tabBar)
        }
        .task {
            if historyViewModel == nil {
                historyViewModel = container.makeHistoryViewModel()
            }

            if trackViewModel == nil {
                let model = container.makeTrackHistoryViewModel()
                trackViewModel = model
            }

            if decisionHistoryViewModel == nil {
                let model = container.makeDecisionChoiceHistoryViewModel()
                decisionHistoryViewModel = model
            }

            if itemLocatorViewModel == nil {
                let model = container.makeItemLocatorViewModel()
                model.startObserving()
                itemLocatorViewModel = model
            }

            if remindersViewModel == nil {
                let model = container.makeRemindersViewModel()
                remindersViewModel = model
            }

            if billsViewModel == nil {
                let model = container.makeBillsViewModel()
                model.startObserving()
                billsViewModel = model
            }

            if foodJournalViewModel == nil {
                let model = container.makeFoodJournalViewModel()
                model.startObserving()
                foodJournalViewModel = model
            }

            if diaryViewModel == nil {
                let model = container.makeDiaryViewModel()
                diaryViewModel = model
            }

            if anniversariesViewModel == nil {
                let model = container.makeAnniversariesViewModel()
                anniversariesViewModel = model
            }
        }
    }

    @ViewBuilder
    private func historyDestination(for route: HistoryDetailRoute) -> some View {
        switch route {
        case .track(let id):
            TrackDetailView(trackID: id)
        case .decisionChoice(let id):
            DecisionChoiceRecordDetailView(
                recordID: id,
                choiceRecordImageTransition: imageTransition(for: route)
            )
        case .storedItem(let id):
            if let itemLocatorViewModel {
                StoredItemDetailView(
                    viewModel: itemLocatorViewModel,
                    itemID: id,
                    itemImageTransition: itemImageTransition
                )
            }
        case .reminder(let id):
            if let remindersViewModel {
                ReminderDetailView(
                    viewModel: remindersViewModel,
                    reminderID: id,
                    reminderImageTransition: imageTransition(for: route)
                )
            }
        case .bill(let id):
            if let billsViewModel {
                BillDetailView(
                    viewModel: billsViewModel,
                    billID: id,
                    billImageTransition: imageTransition(for: route)
                )
            }
        case .foodDay(let dayStart):
            if let foodJournalViewModel, let billsViewModel {
                FoodDayDetailView(
                    viewModel: foodJournalViewModel,
                    billsViewModel: billsViewModel,
                    dayStart: dayStart
                )
            }
        case .diary(let id):
            if let diaryViewModel {
                DiaryDetailView(
                    viewModel: diaryViewModel,
                    diaryID: id
                )
            }
        case .anniversary(let id):
            if let anniversariesViewModel {
                AnniversaryDetailView(
                    viewModel: anniversariesViewModel,
                    anniversaryID: id
                )
            }
        }
    }

    private func imageTransition(for route: HistoryDetailRoute) -> Namespace.ID? {
        switch route {
        case .decisionChoice(let id):
            guard case .decisionChoice(let record) = historyViewModel?.entry(id: "decisionChoice:\(id)"),
                  record.optionImageData != nil else {
                return nil
            }
            return choiceRecordImageTransition

        case .reminder(let id):
            let reminderRecord = historyViewModel?.entries.compactMap { entry -> ReminderHistoryRecord? in
                guard case .reminder(let record) = entry, record.reminderID == id else { return nil }
                return record
            }.first

            guard reminderRecord?.reminder.imageData != nil else {
                return nil
            }
            return reminderImageTransition

        case .bill(let id):
            guard case .bill(let bill) = historyViewModel?.entry(id: "bill:\(id)"),
                  bill.imageData != nil else {
                return nil
            }
            return billImageTransition

        case .track, .storedItem, .foodDay, .diary, .anniversary:
            return nil
        }
    }
}

private struct TrackHistoryContent: View {
    @Environment(\.editMode) private var editMode
    @Bindable var historyViewModel: HistoryViewModel
    @Bindable var viewModel: TrackHistoryViewModel
    @Bindable var decisionHistoryViewModel: DecisionChoiceHistoryViewModel
    @Bindable var itemLocatorViewModel: ItemLocatorViewModel
    @Bindable var remindersViewModel: RemindersViewModel
    @Bindable var billsViewModel: BillsViewModel
    @Bindable var foodJournalViewModel: FoodJournalViewModel
    @Bindable var diaryViewModel: DiaryViewModel
    @Bindable var anniversariesViewModel: AnniversariesViewModel
    @State private var selectedEntryIDs = Set<String>()
    @State private var deletionConfirmation: HistoryDeletionConfirmation?
    @State private var selectedDate = Date()
    @State private var weekAnchorDate = Date()
    @State private var activeFilter: HistoryFilter?
    @State private var searchText = ""
    @State private var selectedRouteFilter: ToolRoute?

    let choiceRecordImageTransition: Namespace.ID
    let itemImageTransition: Namespace.ID
    let reminderImageTransition: Namespace.ID
    let billImageTransition: Namespace.ID
    let activeTrackID: String?
    let activeElapsedSeconds: Int64
    let activePointCount: Int
    let activeDistanceMeters: Double
    let isRecording: Bool
    let filterRequest: HistoryFilter?
    let clearFilter: () -> Void

    private let calendar = Calendar.current

    var body: some View {
        ZStack {
            List(selection: isEditing ? $selectedEntryIDs : nil) {
                if let errorMessage = historyViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = viewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = decisionHistoryViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = itemLocatorViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = remindersViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = billsViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = foodJournalViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = diaryViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let errorMessage = anniversariesViewModel.errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }

                if let notificationMessage = remindersViewModel.notificationMessage {
                    Section {
                        Label(notificationMessage, systemImage: "bell.slash")
                            .foregroundStyle(.orange)
                    }
                }

                if let notificationMessage = anniversariesViewModel.notificationMessage {
                    Section {
                        Label(notificationMessage, systemImage: "bell.slash")
                            .foregroundStyle(.orange)
                    }
                }

                Section {
                    CalendarWeekStrip(
                        selectedDate: $selectedDate,
                        weekAnchorDate: $weekAnchorDate,
                        entryCountsByDay: historyViewModel.entryCountsByDay
                    )
                }

                if historyViewModel.isLoading {
                    Section {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                    }
                } else if historyViewModel.entries.isEmpty {
                    ContentUnavailableView(emptyHistoryTitle, systemImage: emptyHistorySystemImage)
                } else {
                    ForEach(historyViewModel.entries) { entry in
                        NavigationLink(value: entry.detailRoute) {
                            HistoryEntryRow(
                                entry: entry,
                                pointCount: pointCount(for: entry),
                                isActiveTrack: isActive(entry),
                                choiceRecordImageTransition: choiceRecordImageTransition,
                                itemImageTransition: itemImageTransition,
                                reminderImageTransition: reminderImageTransition,
                                billImageTransition: billImageTransition,
                                itemCategoryName: itemCategoryName(for: entry),
                                activeElapsedSeconds: activeElapsedSeconds,
                                activeDistanceMeters: activeDistanceMeters
                            )
                        }
                        .tag(entry.id)
                        .task {
                            await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            if canDelete(entry) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label(AppLocalization.string("删除"), systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        }
                    }

                    if historyViewModel.isLoadingMore {
                        HStack {
                            Spacer()
                            ProgressView()
                            Spacer()
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
        .searchable(text: $searchText, prompt: AppLocalization.string("搜索历史记录"))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if isEditing {
                    Button(AppLocalization.string("删除"), systemImage: "trash", action: requestDeleteSelectedEntries)
                        .disabled(selectedDeletableEntries.isEmpty)
                        .tint(.red)
                } else {
                    HistoryFilterMenu(
                        selectedRoute: $selectedRouteFilter,
                        activeFilter: activeFilter,
                        isFiltering: isFiltering,
                        clearFilter: clearActiveFilter
                    )
                }
            }

            if historyViewModel.entries.isEmpty == false {
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }
        }
        .alert(
            deletionConfirmation.map(deletionConfirmationTitle) ?? "",
            isPresented: .isPresent($deletionConfirmation),
            presenting: deletionConfirmation
        ) { confirmation in
            Button(deletionConfirmationButtonTitle(for: confirmation), role: .destructive) {
                confirmDeleteEntries(confirmation.entries)
            }
            Button(AppLocalization.string("取消"), role: .cancel, action: clearPendingDeletion)
        } message: { confirmation in
            Text(deletionConfirmationMessage(for: confirmation))
        }
        .onChange(of: isEditing) { _, newValue in
            if newValue == false {
                selectedEntryIDs = []
            }
        }
        .onChange(of: selectedDate) {
            selectedEntryIDs = []
            scheduleReloadHistory()
        }
        .onChange(of: searchText) {
            selectedEntryIDs = []
            scheduleReloadHistory()
        }
        .onChange(of: selectedRouteFilter) {
            selectedEntryIDs = []
            scheduleReloadHistory()
        }
        .onChange(of: weekAnchorDate) {
            scheduleRefreshHistoryCounts()
        }
        .onAppear {
            applyFilterRequest(filterRequest)
            scheduleReloadHistory()
            scheduleRefreshHistoryCounts()
        }
        .onChange(of: filterRequest) { _, newValue in
            applyFilterRequest(newValue)
            scheduleReloadHistory()
            scheduleRefreshHistoryCounts()
        }
    }

    private var emptyHistoryTitle: String {
        if historyViewModel.hasAnyEntries == false {
            return AppLocalization.string("暂无历史记录")
        }

        if activeFilter != nil || selectedRouteFilter != nil || normalizedSearchText.isEmpty == false {
            return AppLocalization.string("暂无匹配记录")
        }

        return AppLocalization.string("当天暂无记录")
    }

    private var emptyHistorySystemImage: String {
        historyViewModel.hasAnyEntries ? "calendar" : "clock"
    }

    private var isFiltering: Bool {
        activeFilter != nil || selectedRouteFilter != nil
    }

    private func isActive(_ entry: HistoryEntry) -> Bool {
        guard case .track(let track) = entry else { return false }
        return isRecording && activeTrackID == track.id
    }

    private func pointCount(for entry: HistoryEntry) -> Int {
        guard case .track(let track) = entry else { return 0 }

        if isActive(entry) {
            return max(activePointCount, historyViewModel.pointCount(for: track.id))
        }

        return historyViewModel.pointCount(for: track.id)
    }

    private func itemCategoryName(for entry: HistoryEntry) -> String {
        guard case .storedItem(let item) = entry else { return "" }
        return historyViewModel.itemCategoryName(for: item)
    }

    private func canDelete(_ entry: HistoryEntry) -> Bool {
        isActive(entry) == false
    }

    private var isEditing: Bool {
        editMode?.wrappedValue.isEditing == true
    }

    private var selectedEntries: [HistoryEntry] {
        historyViewModel.entries.filter { selectedEntryIDs.contains($0.id) }
    }

    private var selectedDeletableEntries: [HistoryEntry] {
        selectedEntries.filter(canDelete)
    }

    private func requestDeleteSelectedEntries() {
        requestDeleteEntries(selectedEntries)
    }

    private func requestDeleteEntries(_ selectedEntries: [HistoryEntry]) {
        let deletableEntries = selectedEntries.filter(canDelete)

        if deletableEntries.count != selectedEntries.count {
            viewModel.reportCannotDeleteActiveTrack()
        }

        guard deletableEntries.isEmpty == false else { return }

        deletionConfirmation = HistoryDeletionConfirmation(entries: deletableEntries)
    }

    private func deletionConfirmationTitle(for confirmation: HistoryDeletionConfirmation) -> String {
        guard confirmation.entries.count != 1 else {
            return AppLocalization.format("删除“%@”？", confirmation.entries[0].title)
        }

        return AppLocalization.format("删除 %d 条历史记录？", confirmation.entries.count)
    }

    private func deletionConfirmationButtonTitle(for confirmation: HistoryDeletionConfirmation) -> String {
        if confirmation.entries.count > 1 {
            return AppLocalization.format("删除 %d 条", confirmation.entries.count)
        }

        return AppLocalization.string("删除")
    }

    private func deletionConfirmationMessage(for confirmation: HistoryDeletionConfirmation) -> String {
        let names = confirmation.entries.map(\.title)

        if names.count <= 1 {
            return AppLocalization.string("删除后该记录会从历史中移除。")
        }

        return AppLocalization.format(
            "将删除：%@。删除后这些记录会从历史中移除。",
            names.joined(separator: AppLocalization.string("列表分隔符"))
        )
    }

    private func confirmDeleteEntries(_ entries: [HistoryEntry]) {
        clearPendingDeletion()
        let plan = HistoryDeletionPlan(entries: entries)

        Task {
            await viewModel.deleteTracks(ids: plan.trackIDs)
            await decisionHistoryViewModel.deleteRecords(ids: plan.decisionRecordIDs)
            for itemID in plan.itemIDs {
                await itemLocatorViewModel.deleteItem(id: itemID)
            }
            await remindersViewModel.deleteCompletionRecords(plan.reminderCompletionTargets)
            await billsViewModel.deleteBills(ids: plan.billIDs)
            await foodJournalViewModel.deleteEntries(ids: plan.foodEntryIDs)
            await diaryViewModel.deleteEntries(ids: plan.diaryIDs)
            await anniversariesViewModel.deleteAnniversaries(ids: plan.anniversaryIDs)
            historyViewModel.removeEntries(ids: plan.entryIDs)
            await reloadHistoryEntries()
            await refreshHistoryCounts()
        }
        selectedEntryIDs.subtract(plan.entryIDs)
    }

    private func clearPendingDeletion() {
        deletionConfirmation = nil
    }

    private func applyFilterRequest(_ filter: HistoryFilter?) {
        activeFilter = filter
        selectedRouteFilter = nil
        searchText = ""
        selectedEntryIDs = []

        guard let start = filter?.dateInterval?.start else { return }
        selectedDate = start
        weekAnchorDate = start
    }

    private func clearActiveFilter() {
        activeFilter = nil
        clearFilter()
    }

    private func scheduleReloadHistory() {
        Task {
            await reloadHistoryEntries()
        }
    }

    private func scheduleRefreshHistoryCounts() {
        Task {
            await refreshHistoryCounts()
        }
    }

    private func reloadHistoryEntries() async {
        await historyViewModel.reload(
            selectedDate: selectedDate,
            activeFilter: activeFilter,
            selectedRouteFilter: selectedRouteFilter,
            searchText: searchText
        )
    }

    private func refreshHistoryCounts() async {
        await historyViewModel.refreshCounts(weekAnchorDate: weekAnchorDate)
    }

    private var normalizedSearchText: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

#Preview {
    NavigationStack {
        TrackHistoryView()
            .environment(AppContainer.preview)
    }
}
