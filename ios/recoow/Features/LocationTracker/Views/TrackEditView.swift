import SwiftUI

struct TrackEditView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppContainer.self) private var container
    @State private var name: String
    @State private var note: String
    @State private var errorMessage: String?
    @State private var isSaving = false

    let track: Track
    let onSaved: (Track) -> Void

    init(track: Track, onSaved: @escaping (Track) -> Void) {
        self.track = track
        self.onSaved = onSaved
        _name = State(initialValue: track.name)
        _note = State(initialValue: track.note ?? "")
    }

    var body: some View {
        Form {
            Section("基础信息") {
                TextField("名称", text: $name)

                TextField("备注", text: $note, axis: .vertical)
                    .lineLimit(3...)
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("编辑轨迹")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", action: cancel)
                    .disabled(isSaving)
            }

            ToolbarItem(placement: .confirmationAction) {
                Button("保存", action: save)
                    .disabled(trimmedName.isEmpty || isSaving)
            }
        }
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var normalizedNote: String? {
        let value = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func cancel() {
        dismiss()
    }

    private func save() {
        guard trimmedName.isEmpty == false, isSaving == false else { return }

        isSaving = true
        errorMessage = nil

        Task {
            do {
                guard let updatedTrack = try container.trackRepository.updateTrackDetails(
                    id: track.id,
                    name: trimmedName,
                    note: normalizedNote
                ) else {
                    errorMessage = AppLocalization.string("轨迹不存在")
                    isSaving = false
                    return
                }

                container.locationTrackerViewModel.applyUpdatedTrackDetails(updatedTrack)
                await container.syncEngine.enqueueScan()
                onSaved(updatedTrack)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                isSaving = false
            }
        }
    }
}

#Preview {
    NavigationStack {
        TrackEditView(
            track: Track.makeNew(accuracy: .tenMeters, deviceID: "preview"),
            onSaved: { _ in }
        )
        .environment(AppContainer.preview)
    }
}
