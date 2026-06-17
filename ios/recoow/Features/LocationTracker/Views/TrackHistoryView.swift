import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var trackViewModel: TrackHistoryViewModel?
    @State private var decisionHistoryViewModel: DecisionChoiceHistoryViewModel?

    var body: some View {
        Group {
            if let trackViewModel, let decisionHistoryViewModel {
                TrackHistoryContent(
                    viewModel: trackViewModel,
                    decisionHistoryViewModel: decisionHistoryViewModel,
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
            DecisionChoiceRecordDetailView(recordID: route.id)
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
        }
    }
}

private struct TrackHistoryContent: View {
    @Environment(\.editMode) private var editMode
    @Bindable var viewModel: TrackHistoryViewModel
    @Bindable var decisionHistoryViewModel: DecisionChoiceHistoryViewModel
    @State private var selectedEntryIDs = Set<String>()
    @State private var deletionConfirmation: HistoryDeletionConfirmation?

    let activeTrackID: String?
    let activeElapsedSeconds: Int64
    let activePointCount: Int
    let activeDistanceMeters: Double
    let isRecording: Bool

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

                if historyEntries.isEmpty {
                    ContentUnavailableView("暂无历史记录", systemImage: "clock")
                } else {
                    ForEach(historyEntries) { entry in
                        switch entry {
                        case .track(let track):
                            NavigationLink(value: TrackDetailRoute(id: track.id)) {
                                HistoryEntryRow(
                                    entry: entry,
                                    pointCount: pointCount(for: track),
                                    isActiveTrack: isActive(track),
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
                    confirmDeleteTracks(confirmation.entries)
                },
                secondaryButton: .cancel(Text("取消"), action: clearPendingDeletion)
            )
        }
        .onChange(of: isEditing) { _, newValue in
            if newValue == false {
                selectedEntryIDs = []
            }
        }
    }

    private var historyEntries: [HistoryEntry] {
        let trackEntries = viewModel.tracks.map(HistoryEntry.track)
        let decisionEntries = decisionHistoryViewModel.records.map(HistoryEntry.decisionChoice)
        return (trackEntries + decisionEntries).sorted { $0.timestamp > $1.timestamp }
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
            case .decisionChoice:
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
            case .decisionChoice:
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
        }
    }

    private func confirmDeleteTracks(_ entries: [HistoryEntry]) {
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

        Task {
            await viewModel.deleteTracks(ids: trackIDs)
            await decisionHistoryViewModel.deleteRecords(ids: decisionRecordIDs)
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
