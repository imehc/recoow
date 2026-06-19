import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var historyViewModel: HistoryViewModel?
    @State private var trackViewModel: TrackHistoryViewModel?
    @State private var decisionHistoryViewModel: DecisionChoiceHistoryViewModel?
    @State private var itemLocatorViewModel: ItemLocatorViewModel?
    @State private var remindersViewModel: RemindersViewModel?
    @State private var billsViewModel: BillsViewModel?
    @State private var anniversariesViewModel: AnniversariesViewModel?
    @Namespace private var choiceRecordImageTransition
    @Namespace private var itemImageTransition
    @Namespace private var reminderImageTransition
    @Namespace private var billImageTransition

    var body: some View {
        Group {
            if let historyViewModel,
               let trackViewModel,
               let decisionHistoryViewModel,
               let itemLocatorViewModel,
               let remindersViewModel,
               let billsViewModel,
               let anniversariesViewModel {
                TrackHistoryContent(
                    historyViewModel: historyViewModel,
                    viewModel: trackViewModel,
                    decisionHistoryViewModel: decisionHistoryViewModel,
                    itemLocatorViewModel: itemLocatorViewModel,
                    remindersViewModel: remindersViewModel,
                    billsViewModel: billsViewModel,
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
                ProgressView("正在加载")
            }
        }
        .navigationTitle("历史")
        .navigationDestination(for: TrackDetailRoute.self) { route in
            TrackDetailView(trackID: route.id)
                .toolbar(.hidden, for: .tabBar)
        }
        .navigationDestination(for: DecisionChoiceRecordRoute.self) { route in
            DecisionChoiceRecordDetailView(
                recordID: route.id,
                choiceRecordImageTransition: imageTransition(for: route)
            )
            .toolbar(.hidden, for: .tabBar)
        }
        .navigationDestination(for: StoredItemRoute.self) { route in
            if let itemLocatorViewModel {
                StoredItemDetailView(
                    viewModel: itemLocatorViewModel,
                    itemID: route.id,
                    itemImageTransition: itemImageTransition
                )
                .toolbar(.hidden, for: .tabBar)
            }
        }
        .navigationDestination(for: ReminderRoute.self) { route in
            if let remindersViewModel {
                ReminderDetailView(
                    viewModel: remindersViewModel,
                    reminderID: route.id,
                    reminderImageTransition: imageTransition(for: route)
                )
                .toolbar(.hidden, for: .tabBar)
            }
        }
        .navigationDestination(for: BillRoute.self) { route in
            if let billsViewModel {
                BillDetailView(
                    viewModel: billsViewModel,
                    billID: route.id,
                    billImageTransition: imageTransition(for: route)
                )
                .toolbar(.hidden, for: .tabBar)
            }
        }
        .navigationDestination(for: AnniversaryRoute.self) { route in
            if let anniversariesViewModel {
                AnniversaryDetailView(
                    viewModel: anniversariesViewModel,
                    anniversaryID: route.id
                )
                .toolbar(.hidden, for: .tabBar)
            }
        }
        .task {
            if historyViewModel == nil {
                historyViewModel = HistoryViewModel(repository: container.historyRepository)
            }

            if trackViewModel == nil {
                let model = TrackHistoryViewModel(
                    repository: container.trackRepository,
                    syncEngine: container.syncEngine
                )
                trackViewModel = model
            }

            if decisionHistoryViewModel == nil {
                let model = DecisionChoiceHistoryViewModel(
                    repository: container.decisionRepository,
                    syncEngine: container.syncEngine
                )
                decisionHistoryViewModel = model
            }

            if itemLocatorViewModel == nil {
                let model = ItemLocatorViewModel(
                    repository: container.itemLocatorRepository,
                    syncEngine: container.syncEngine
                )
                itemLocatorViewModel = model
            }

            if remindersViewModel == nil {
                let model = RemindersViewModel(
                    repository: container.reminderRepository,
                    notificationService: container.reminderNotificationService,
                    syncEngine: container.syncEngine
                )
                remindersViewModel = model
            }

            if billsViewModel == nil {
                let model = BillsViewModel(
                    repository: container.billRepository,
                    syncEngine: container.syncEngine
                )
                billsViewModel = model
            }

            if anniversariesViewModel == nil {
                let model = AnniversariesViewModel(
                    repository: container.anniversaryRepository,
                    notificationService: container.anniversaryNotificationService,
                    syncEngine: container.syncEngine
                )
                anniversariesViewModel = model
            }
        }
    }

    private func imageTransition(for route: DecisionChoiceRecordRoute) -> Namespace.ID? {
        guard case .decisionChoice(let record) = historyViewModel?.entry(id: "decisionChoice:\(route.id)"),
              record.optionImageData != nil else {
            return nil
        }

        return choiceRecordImageTransition
    }

    private func imageTransition(for route: ReminderRoute) -> Namespace.ID? {
        guard case .reminder(let reminder) = historyViewModel?.entry(id: "reminder:\(route.id)"),
              reminder.imageData != nil else {
            return nil
        }

        return reminderImageTransition
    }

    private func imageTransition(for route: BillRoute) -> Namespace.ID? {
        guard case .bill(let bill) = historyViewModel?.entry(id: "bill:\(route.id)"),
              bill.imageData != nil else {
            return nil
        }

        return billImageTransition
    }
}

private struct TrackHistoryContent: View {
    @Environment(\.editMode) private var editMode
    @Environment(\.locale) private var locale
    @Bindable var historyViewModel: HistoryViewModel
    @Bindable var viewModel: TrackHistoryViewModel
    @Bindable var decisionHistoryViewModel: DecisionChoiceHistoryViewModel
    @Bindable var itemLocatorViewModel: ItemLocatorViewModel
    @Bindable var remindersViewModel: RemindersViewModel
    @Bindable var billsViewModel: BillsViewModel
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
                        switch entry {
                        case .track(let track):
                            NavigationLink(value: TrackDetailRoute(id: track.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: pointCount(for: track),
                                    isActiveTrack: isActive(track),
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: "",
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                if isActive(track) == false {
                                    Button {
                                        requestDeleteEntries([entry])
                                    } label: {
                                        Label("删除", systemImage: "trash")
                                    }
                                    .tint(.red)
                                }
                            }

                        case .decisionChoice(let record):
                            NavigationLink(value: DecisionChoiceRecordRoute(id: record.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: 0,
                                    isActiveTrack: false,
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: "",
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }

                        case .storedItem(let item):
                            NavigationLink(value: StoredItemRoute(id: item.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: 0,
                                    isActiveTrack: false,
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: historyViewModel.itemCategoryName(for: item),
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }

                        case .reminder(let reminder):
                            NavigationLink(value: ReminderRoute(id: reminder.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: 0,
                                    isActiveTrack: false,
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: "",
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }

                        case .bill(let bill):
                            NavigationLink(value: BillRoute(id: bill.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: 0,
                                    isActiveTrack: false,
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: "",
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                        case .anniversary(let anniversary):
                            NavigationLink(value: AnniversaryRoute(id: anniversary.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: 0,
                                    isActiveTrack: false,
                                    choiceRecordImageTransition: choiceRecordImageTransition,
                                    itemImageTransition: itemImageTransition,
                                    reminderImageTransition: reminderImageTransition,
                                    billImageTransition: billImageTransition,
                                    itemCategoryName: "",
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
                            .task {
                                await historyViewModel.loadMoreIfNeeded(currentEntry: entry)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button {
                                    requestDeleteEntries([entry])
                                } label: {
                                    Label("删除", systemImage: "trash")
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
                    Button("删除", systemImage: "trash", action: requestDeleteSelectedEntries)
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
        .alert(item: $deletionConfirmation) { confirmation in
            Alert(
                title: Text(deletionConfirmationTitle(for: confirmation)),
                message: Text(deletionConfirmationMessage(for: confirmation)),
                primaryButton: .destructive(Text(deletionConfirmationButtonTitle(for: confirmation))) {
                    confirmDeleteEntries(confirmation.entries)
                },
                secondaryButton: .cancel(Text("取消"), action: clearPendingDeletion)
            )
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

    private func isActive(_ track: Track) -> Bool {
        isRecording && activeTrackID == track.id
    }

    private func pointCount(for track: Track) -> Int {
        if isActive(track) {
            return max(activePointCount, historyViewModel.pointCount(for: track.id))
        }

        return historyViewModel.pointCount(for: track.id)
    }

    private var isEditing: Bool {
        editMode?.wrappedValue.isEditing == true
    }

    private var selectedEntries: [HistoryEntry] {
        historyViewModel.entries.filter { selectedEntryIDs.contains($0.id) }
    }

    private var selectedDeletableEntries: [HistoryEntry] {
        selectedEntries.filter { entry in
            switch entry {
            case .track(let track):
                isActive(track) == false
            case .decisionChoice, .storedItem, .reminder, .bill, .anniversary:
                true
            }
        }
    }

    private func requestDeleteSelectedEntries() {
        requestDeleteEntries(selectedEntries)
    }

    private func requestDeleteEntries(_ selectedEntries: [HistoryEntry]) {
        let deletableEntries = selectedEntries.filter { entry in
            switch entry {
            case .track(let track):
                isActive(track) == false
            case .decisionChoice, .storedItem, .reminder, .bill, .anniversary:
                true
            }
        }

        if deletableEntries.count != selectedEntries.count {
            viewModel.reportCannotDeleteActiveTrack()
        }

        guard deletableEntries.isEmpty == false else { return }

        deletionConfirmation = HistoryDeletionConfirmation(entries: deletableEntries)
    }

    private func deletionConfirmationTitle(for confirmation: HistoryDeletionConfirmation) -> String {
        guard confirmation.entries.count != 1 else {
            return AppLocalization.format("删除“%@”？", entryTitle(confirmation.entries[0]))
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
        let names = confirmation.entries.map(entryTitle)

        if names.count <= 1 {
            return AppLocalization.string("删除后该记录会从历史中移除。")
        }

        return AppLocalization.format(
            "将删除：%@。删除后这些记录会从历史中移除。",
            names.joined(separator: AppLocalization.string("列表分隔符"))
        )
    }

    private func entryTitle(_ entry: HistoryEntry) -> String {
        switch entry {
        case .track(let track):
            track.name
        case .decisionChoice(let record):
            record.optionTitle
        case .storedItem(let item):
            item.title
        case .reminder(let reminder):
            reminder.title
        case .bill(let bill):
            bill.title
        case .anniversary(let anniversary):
            anniversary.title
        }
    }

    private func confirmDeleteEntries(_ entries: [HistoryEntry]) {
        clearPendingDeletion()

        let trackIDs = entries.compactMap { entry in
            if case .track(let track) = entry {
                return track.id
            }
            return nil
        }
        let decisionRecordIDs = entries.compactMap { entry in
            if case .decisionChoice(let record) = entry {
                return record.id
            }
            return nil
        }
        let itemIDs = entries.compactMap { entry in
            if case .storedItem(let item) = entry {
                return item.id
            }
            return nil
        }
        let reminderIDs = entries.compactMap { entry in
            if case .reminder(let reminder) = entry {
                return reminder.id
            }
            return nil
        }
        let billIDs = entries.compactMap { entry in
            if case .bill(let bill) = entry {
                return bill.id
            }
            return nil
        }
        let anniversaryIDs = entries.compactMap { entry in
            if case .anniversary(let anniversary) = entry {
                return anniversary.id
            }
            return nil
        }

        Task {
            await viewModel.deleteTracks(ids: trackIDs)
            await decisionHistoryViewModel.deleteRecords(ids: decisionRecordIDs)
            for itemID in itemIDs {
                await itemLocatorViewModel.deleteItem(id: itemID)
            }
            await remindersViewModel.deleteReminders(ids: reminderIDs)
            await billsViewModel.deleteBills(ids: billIDs)
            await anniversariesViewModel.deleteAnniversaries(ids: anniversaryIDs)
            historyViewModel.removeEntries(ids: entries.map(\.id))
            await reloadHistoryEntries()
            await refreshHistoryCounts()
        }
        selectedEntryIDs.subtract(entries.map(\.id))
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

    private func matches(_ entry: HistoryEntry, filter: HistoryFilter) -> Bool {
        if let route = filter.route, entry.route != route {
            return false
        }

        if let dateInterval = filter.dateInterval {
            return dateInterval.contains(entry.date)
        }

        return true
    }

    private func matchesRouteFilter(_ entry: HistoryEntry) -> Bool {
        guard let selectedRouteFilter else { return true }
        return entry.route == selectedRouteFilter
    }

    private func matchesSearch(_ entry: HistoryEntry) -> Bool {
        let query = normalizedSearchText
        guard query.isEmpty == false else { return true }

        return searchableText(for: entry)
            .localizedCaseInsensitiveContains(query)
    }

    private var normalizedSearchText: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func searchableText(for entry: HistoryEntry) -> String {
        let values: [String?] = switch entry {
        case .track(let track):
            [
                track.name,
                track.note,
                AppLocalization.string(entry.route.title)
            ]
        case .decisionChoice(let record):
            [
                record.collectionTitle,
                record.optionTitle,
                record.optionDetail,
                record.optionCustomInfo,
                AppLocalization.string(entry.route.title)
            ]
        case .storedItem(let item):
            [
                item.title,
                item.location,
                item.note,
                item.tags,
                item.searchKeywords,
                itemLocatorViewModel.categoryName(for: item),
                AppLocalization.string(entry.route.title)
            ]
        case .reminder(let reminder):
            [
                reminder.title,
                reminder.note,
                reminder.memoryIcon,
                AppLocalization.string(reminder.scheduleKind.title),
                reminder.scheduleTitle(locale: locale),
                AppLocalization.string(reminder.isCompleted ? "已完成" : reminder.isTodayCompleted ? "今日已打卡" : "待打卡"),
                AppLocalization.string(entry.route.title)
            ]
        case .bill(let bill):
            [
                bill.title,
                bill.note,
                bill.billType.localizedTitle,
                bill.billType == .expense ? bill.billCategory.localizedTitle : bill.billIncomeCategory.localizedTitle,
                bill.billPaymentMethod.localizedTitle,
                AppFormatters.money(cents: bill.finalAmountCents),
                AppLocalization.string(entry.route.title)
            ]
        case .anniversary(let anniversary):
            [
                anniversary.title,
                anniversary.note,
                anniversary.category.localizedTitle,
                AppLocalization.string(anniversary.isYearly ? "每年" : "不重复"),
                anniversary.leadTime.localizedTitle,
                AppLocalization.string(entry.route.title)
            ]
        }

        return values.compactMap(\.self).joined(separator: " ")
    }
}

#Preview {
    NavigationStack {
        TrackHistoryView()
            .environment(AppContainer.preview)
    }
}
