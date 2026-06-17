import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: TrackHistoryViewModel?

    var body: some View {
        Group {
            if let viewModel {
                TrackHistoryContent(
                    viewModel: viewModel,
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
        .navigationDestination(for: String.self) { trackID in
            TrackDetailView(trackID: trackID)
        }
        .task {
            if viewModel == nil {
                let model = TrackHistoryViewModel(
                    repository: container.trackRepository,
                    syncEngine: container.syncEngine
                )
                model.startObserving()
                viewModel = model
            }
        }
    }
}

private struct TrackHistoryContent: View {
    @Environment(\.editMode) private var editMode
    @Bindable var viewModel: TrackHistoryViewModel
    @State private var selectedTrackIDs = Set<String>()
    @State private var pendingDeletionTrackIDs: [String] = []
    @State private var isShowingDeletionConfirmation = false

    let activeTrackID: String?
    let activeElapsedSeconds: Int64
    let activePointCount: Int
    let activeDistanceMeters: Double
    let isRecording: Bool

    var body: some View {
        List(selection: $selectedTrackIDs) {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section("轨迹") {
                if viewModel.tracks.isEmpty {
                    ContentUnavailableView("暂无轨迹", systemImage: "map")
                } else {
                    ForEach(viewModel.tracks) { track in
                        NavigationLink(value: track.id) {
                            TrackHistoryRow(
                                track: track,
                                pointCount: pointCount(for: track),
                                isActive: isActive(track),
                                activeElapsedSeconds: activeElapsedSeconds,
                                activeDistanceMeters: activeDistanceMeters
                            )
                        }
                        .tag(track.id)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            if isActive(track) == false {
                                Button(role: .destructive) {
                                    requestDeleteTrack(track)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            if viewModel.tracks.isEmpty == false {
                ToolbarItem(placement: .topBarLeading) {
                    if isEditing {
                        Button("删除", systemImage: "trash", action: requestDeleteSelectedTracks)
                            .disabled(selectedDeletableTrackIDs.isEmpty)
                            .tint(.red)
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }
        }
        .confirmationDialog(
            deletionConfirmationTitle,
            isPresented: $isShowingDeletionConfirmation,
            titleVisibility: .visible
        ) {
            Button(role: .destructive, action: confirmDeleteTracks) {
                Text(deletionConfirmationButtonTitle)
            }

            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text(deletionConfirmationMessage)
        }
        .onChange(of: isEditing) { _, newValue in
            if newValue == false {
                selectedTrackIDs = []
            }
        }
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

    private var selectedTracks: [Track] {
        viewModel.tracks.filter { selectedTrackIDs.contains($0.id) }
    }

    private var selectedDeletableTrackIDs: [String] {
        selectedTracks
            .filter { isActive($0) == false }
            .map(\.id)
    }

    private func requestDeleteTrack(_ track: Track) {
        requestDeleteTracks([track])
    }

    private func requestDeleteSelectedTracks() {
        requestDeleteTracks(selectedTracks)
    }

    private func requestDeleteTracks(_ selectedTracks: [Track]) {
        let deletableTracks = selectedTracks.filter { isActive($0) == false }

        if deletableTracks.count != selectedTracks.count {
            viewModel.reportCannotDeleteActiveTrack()
        }

        let ids = deletableTracks.map(\.id)
        guard ids.isEmpty == false else { return }

        pendingDeletionTrackIDs = ids
        isShowingDeletionConfirmation = true
    }

    private var deletionConfirmationTitle: String {
        guard pendingDeletionTracks.count != 1 else {
            return "删除“\(pendingDeletionTracks[0].name)”？"
        }

        return "删除 \(pendingDeletionTracks.count) 条轨迹？"
    }

    private var deletionConfirmationButtonTitle: String {
        if pendingDeletionTracks.count > 1 {
            return "删除 \(pendingDeletionTracks.count) 条"
        }

        return "删除"
    }

    private var deletionConfirmationMessage: String {
        let names = pendingDeletionTracks.map(\.name)

        if names.count <= 1 {
            return "删除后该轨迹及采样点会从历史记录中移除。"
        }

        return "将删除：\(names.joined(separator: "、"))。删除后这些轨迹及采样点会从历史记录中移除。"
    }

    private var pendingDeletionTracks: [Track] {
        pendingDeletionTrackIDs.compactMap { trackID in
            viewModel.tracks.first { $0.id == trackID }
        }
    }

    private func confirmDeleteTracks() {
        let ids = pendingDeletionTrackIDs
        clearPendingDeletion()

        guard ids.isEmpty == false else { return }

        Task {
            await viewModel.deleteTracks(ids: ids)
        }
        selectedTrackIDs.subtract(ids)
    }

    private func clearPendingDeletion() {
        pendingDeletionTrackIDs = []
        isShowingDeletionConfirmation = false
    }
}

#Preview {
    NavigationStack {
        TrackHistoryView()
            .environment(AppContainer.preview)
    }
}
