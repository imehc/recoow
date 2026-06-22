import CoreGraphics
import Observation

@MainActor
@Observable
final class MediaAttachmentDragCoordinator {
    var deleteTargetState = DragDeleteTargetState()
    var previewState = MediaAttachmentDragPreviewState()

    func updatePreview(
        attachment: MediaAttachment?,
        frame: CGRect,
        translation: CGSize,
        isActive: Bool
    ) {
        let nextState = MediaAttachmentDragPreviewState(
            attachment: attachment,
            frame: frame,
            translation: translation,
            isActive: isActive
        )

        guard previewState != nextState else { return }
        previewState = nextState
    }

    func updateDeleteTarget(isActive: Bool, isTargeted: Bool) {
        guard deleteTargetState.isActive != isActive
            || deleteTargetState.isTargeted != isTargeted else {
            return
        }

        deleteTargetState.isActive = isActive
        deleteTargetState.isTargeted = isTargeted
    }

    func reset() {
        updatePreview(attachment: nil, frame: .zero, translation: .zero, isActive: false)
        updateDeleteTarget(isActive: false, isTargeted: false)
    }
}
