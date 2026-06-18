import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var trackViewModel: TrackHistoryViewModel?
    @State private var decisionHistoryViewModel: DecisionChoiceHistoryViewModel?
    @State private var itemLocatorViewModel: ItemLocatorViewModel?
    @State private var remindersViewModel: RemindersViewModel?
    @State private var billsViewModel: BillsViewModel?
    @Namespace private var choiceRecordImageTransition
    @Namespace private var itemImageTransition
    @Namespace private var reminderImageTransition
    @Namespace private var billImageTransition

    var body: some View {
        Group {
            if let trackViewModel, let decisionHistoryViewModel, let itemLocatorViewModel, let remindersViewModel, let billsViewModel {
                TrackHistoryContent(
                    viewModel: trackViewModel,
                    decisionHistoryViewModel: decisionHistoryViewModel,
                    itemLocatorViewModel: itemLocatorViewModel,
                    remindersViewModel: remindersViewModel,
                    billsViewModel: billsViewModel,
                    choiceRecordImageTransition: choiceRecordImageTransition,
                    itemImageTransition: itemImageTransition,
                    reminderImageTransition: reminderImageTransition,
                    billImageTransition: billImageTransition,
                    activeTrackID: container.locationTrackerViewModel.currentTrackID,
                    activeElapsedSeconds: container.locationTrackerViewModel.elapsedSeconds,
                    activePointCount: container.locationTrackerViewModel.pointCount,
                    activeDistanceMeters: container.locationTrackerViewModel.currentDistanceMeters,
                    isRecording: container.locationTrackerViewModel.isRecording
                )
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("历史")
        .navigationDestination(for: TrackDetailRoute.self) { route in
            TrackDetailView(trackID: route.id)
        }
        .navigationDestination(for: DecisionChoiceRecordRoute.self) { route in
            DecisionChoiceRecordDetailView(
                recordID: route.id,
                choiceRecordImageTransition: imageTransition(for: route)
            )
        }
        .navigationDestination(for: StoredItemRoute.self) { route in
            if let itemLocatorViewModel {
                StoredItemDetailView(
                    viewModel: itemLocatorViewModel,
                    itemID: route.id,
                    itemImageTransition: itemImageTransition
                )
            }
        }
        .navigationDestination(for: ReminderRoute.self) { route in
            if let remindersViewModel {
                ReminderDetailView(
                    viewModel: remindersViewModel,
                    reminderID: route.id,
                    reminderImageTransition: imageTransition(for: route)
                )
            }
        }
        .navigationDestination(for: BillRoute.self) { route in
            if let billsViewModel {
                BillDetailView(
                    viewModel: billsViewModel,
                    billID: route.id,
                    billImageTransition: imageTransition(for: route)
                )
            }
        }
        .task {
            if trackViewModel == nil {
                let model = TrackHistoryViewModel(
                    repository: container.trackRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                trackViewModel = model
            }

            if decisionHistoryViewModel == nil {
                let model = DecisionChoiceHistoryViewModel(
                    repository: container.decisionRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                decisionHistoryViewModel = model
            }

            if itemLocatorViewModel == nil {
                let model = ItemLocatorViewModel(
                    repository: container.itemLocatorRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                itemLocatorViewModel = model
            }

            if remindersViewModel == nil {
                let model = RemindersViewModel(
                    repository: container.reminderRepository,
                    notificationService: container.reminderNotificationService,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                remindersViewModel = model
            }

            if billsViewModel == nil {
                let model = BillsViewModel(
                    repository: container.billRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                billsViewModel = model
            }
        }
    }

    private func imageTransition(for route: DecisionChoiceRecordRoute) -> Namespace.ID? {
        guard decisionHistoryViewModel?.records.contains(where: { record in
            record.id == route.id && record.optionImageData != nil
        }) == true else {
            return nil
        }

        return choiceRecordImageTransition
    }

    private func imageTransition(for route: ReminderRoute) -> Namespace.ID? {
        guard remindersViewModel?.reminders.contains(where: { reminder in
            reminder.id == route.id && reminder.imageData != nil
        }) == true else {
            return nil
        }

        return reminderImageTransition
    }

    private func imageTransition(for route: BillRoute) -> Namespace.ID? {
        guard billsViewModel?.bills.contains(where: { bill in
            bill.id == route.id && bill.imageData != nil
        }) == true else {
            return nil
        }

        return billImageTransition
    }
}

private struct TrackHistoryContent: View {
    @Environment(\.editMode) private var editMode
    @Bindable var viewModel: TrackHistoryViewModel
    @Bindable var decisionHistoryViewModel: DecisionChoiceHistoryViewModel
    @Bindable var itemLocatorViewModel: ItemLocatorViewModel
    @Bindable var remindersViewModel: RemindersViewModel
    @Bindable var billsViewModel: BillsViewModel
    @State private var selectedEntryIDs = Set<String>()
    @State private var deletionConfirmation: HistoryDeletionConfirmation?
    @State private var selectedDate = Date()
    @State private var weekAnchorDate = Date()

    let choiceRecordImageTransition: Namespace.ID
    let itemImageTransition: Namespace.ID
    let reminderImageTransition: Namespace.ID
    let billImageTransition: Namespace.ID
    let activeTrackID: String?
    let activeElapsedSeconds: Int64
    let activePointCount: Int
    let activeDistanceMeters: Double
    let isRecording: Bool

    private let calendar = Calendar.current

    var body: some View {
        ZStack {
            List(selection: isEditing ? $selectedEntryIDs : nil) {
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

                if let notificationMessage = remindersViewModel.notificationMessage {
                    Section {
                        Label(notificationMessage, systemImage: "bell.slash")
                            .foregroundStyle(.orange)
                    }
                }

                Section {
                    CalendarWeekStrip(
                        selectedDate: $selectedDate,
                        weekAnchorDate: $weekAnchorDate,
                        entryCountsByDay: entryCountsByDay
                    )
                }

                if historyEntries.isEmpty {
                    ContentUnavailableView(emptyHistoryTitle, systemImage: emptyHistorySystemImage)
                } else {
                    ForEach(historyEntries) { entry in
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
                                    itemCategoryName: itemLocatorViewModel.categoryName(for: item),
                                    activeElapsedSeconds: activeElapsedSeconds,
                                    activeDistanceMeters: activeDistanceMeters
                                )
                            }
                            .tag(entry.id)
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
                }
            }
            .listStyle(.insetGrouped)
        }
        .toolbar {
            if historyEntries.isEmpty == false {
                ToolbarItem(placement: .topBarLeading) {
                    if isEditing {
                        Button("删除", systemImage: "trash", action: requestDeleteSelectedEntries)
                            .disabled(selectedDeletableEntries.isEmpty)
                            .tint(.red)
                    }
                }

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
        }
    }

    private var allHistoryEntries: [HistoryEntry] {
        let trackEntries = viewModel.tracks.map(HistoryEntry.track)
        let decisionEntries = decisionHistoryViewModel.records.map(HistoryEntry.decisionChoice)
        let itemEntries = itemLocatorViewModel.items.map(HistoryEntry.storedItem)
        let reminderEntries = remindersViewModel.reminders.map(HistoryEntry.reminder)
        let billEntries = billsViewModel.bills.map(HistoryEntry.bill)
        return (trackEntries + decisionEntries + itemEntries + reminderEntries + billEntries).sorted { $0.timestamp > $1.timestamp }
    }

    private var historyEntries: [HistoryEntry] {
        allHistoryEntries.filter { entry in
            entry.isOnSameDay(as: selectedDate, calendar: calendar)
        }
    }

    private var entryCountsByDay: [Date: Int] {
        allHistoryEntries.reduce(into: [:]) { counts, entry in
            counts[calendar.startOfDay(for: entry.date), default: 0] += 1
        }
    }

    private var emptyHistoryTitle: String {
        AppLocalization.string(allHistoryEntries.isEmpty ? "暂无历史记录" : "当天暂无记录")
    }

    private var emptyHistorySystemImage: String {
        allHistoryEntries.isEmpty ? "clock" : "calendar"
    }

    private func isActive(_ track: Track) -> Bool {
        isRecording && activeTrackID == track.id
    }

    private func pointCount(for track: Track) -> Int {
        if isActive(track) {
            return max(activePointCount, viewModel.pointCount(for: track.id))
        }

        return viewModel.pointCount(for: track.id)
    }

    private var isEditing: Bool {
        editMode?.wrappedValue.isEditing == true
    }

    private var selectedEntries: [HistoryEntry] {
        historyEntries.filter { selectedEntryIDs.contains($0.id) }
    }

    private var selectedDeletableEntries: [HistoryEntry] {
        selectedEntries.filter { entry in
            switch entry {
            case .track(let track):
                isActive(track) == false
            case .decisionChoice, .storedItem, .reminder, .bill:
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
            case .decisionChoice, .storedItem, .reminder, .bill:
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
            return "删除“\(entryTitle(confirmation.entries[0]))”？"
        }

        return "删除 \(confirmation.entries.count) 条历史记录？"
    }

    private func deletionConfirmationButtonTitle(for confirmation: HistoryDeletionConfirmation) -> String {
        if confirmation.entries.count > 1 {
            return "删除 \(confirmation.entries.count) 条"
        }

        return "删除"
    }

    private func deletionConfirmationMessage(for confirmation: HistoryDeletionConfirmation) -> String {
        let names = confirmation.entries.map(entryTitle)

        if names.count <= 1 {
            return "删除后该记录会从历史中移除。"
        }

        return "将删除：\(names.joined(separator: "、"))。删除后这些记录会从历史中移除。"
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

        Task {
            await viewModel.deleteTracks(ids: trackIDs)
            await decisionHistoryViewModel.deleteRecords(ids: decisionRecordIDs)
            for itemID in itemIDs {
                await itemLocatorViewModel.deleteItem(id: itemID)
            }
            await remindersViewModel.deleteReminders(ids: reminderIDs)
            await billsViewModel.deleteBills(ids: billIDs)
        }
        selectedEntryIDs.subtract(entries.map(\.id))
    }

    private func clearPendingDeletion() {
        deletionConfirmation = nil
    }
}

#Preview {
    NavigationStack {
        TrackHistoryView()
            .environment(AppContainer.preview)
    }
}
