import SwiftUI
import UIKit

struct MediaAttachmentInputSection: View {
    let ownerType: MediaAttachmentOwnerType
    let ownerID: String
    let deviceID: String
    @Binding var attachments: [MediaAttachment]
    let dragCoordinator: MediaAttachmentDragCoordinator?
    let onPhotoSourceRequest: () -> Void
    let onPreviewAttachment: (MediaAttachment) -> Void

    @State private var recorder = MediaAudioRecorder()

    var body: some View {
        Section(AppLocalization.string("附件")) {
            MediaAttachmentPhotoGridView(
                photos: photoAttachments,
                onPreview: onPreviewAttachment,
                onRemove: remove,
                onSwap: swapPhoto,
                dragCoordinator: dragCoordinator,
                onAdd: onPhotoSourceRequest
            )

            MediaAudioRecorderRow(
                recorder: recorder,
                onToggleRecording: toggleRecording
            )

            if audioAttachments.isEmpty == false {
                ForEach(audioAttachments) { attachment in
                    MediaAttachmentRow(attachment: attachment) { attachment in
                        onPreviewAttachment(attachment)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            remove(attachment)
                        } label: {
                            Label(AppLocalization.string("删除"), systemImage: "trash")
                        }
                    }
                }
            }

            if let errorMessage = recorder.errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private var photoAttachments: [MediaAttachment] {
        attachments.filter { $0.kind == .photo }
    }

    private var audioAttachments: [MediaAttachment] {
        attachments.filter { $0.kind == .audio }
    }

    private func toggleRecording() {
        if recorder.isRecording {
            if let attachment = recorder.stopRecording(ownerType: ownerType, ownerID: ownerID, deviceID: deviceID) {
                attachments.append(attachment)
            }
        } else {
            Task {
                await recorder.startRecording()
            }
        }
    }

    private func remove(_ attachment: MediaAttachment) {
        attachments.removeAll { $0.id == attachment.id }
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
        attachments = attachments.map { attachment in
            guard attachment.kind == .photo, remainingPhotos.isEmpty == false else {
                return attachment
            }

            return remainingPhotos.removeFirst()
        }
    }
}

struct MediaAttachmentPhotoGridView: View {
    let photos: [MediaAttachment]
    let onPreview: (MediaAttachment) -> Void
    var onRemove: ((MediaAttachment) -> Void)? = nil
    var onSwap: ((MediaAttachment, MediaAttachment) -> Void)? = nil
    var dragCoordinator: MediaAttachmentDragCoordinator? = nil
    var onAdd: (() -> Void)? = nil

    @State private var tileFrames: [String: CGRect] = [:]
    @State private var dragState: PhotoDragState?
    @State private var lastSwapTargetID: String?

    private let dragActivationDistance: CGFloat = 12

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
    }

    var body: some View {
        VStack(spacing: 10) {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(photos) { photo in
                    photoTile(for: photo)
                }

                if let onAdd {
                    Button(action: onAdd) {
                        ZStack {
                            RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                                .fill(Color(.tertiarySystemFill))

                            RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                                .strokeBorder(Color(.separator), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))

                            Image(systemName: "plus")
                                .font(.title2.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                        .aspectRatio(1, contentMode: .fit)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(AppLocalization.string("添加照片"))
                }
            }

        }
        .padding(.vertical, 4)
        .onPreferenceChange(MediaAttachmentPhotoFramePreferenceKey.self) { frames in
            guard tileFrames != frames else { return }
            tileFrames = frames
        }
        .onChange(of: photos.map(\.id)) { _, photoIDs in
            if let dragID = dragState?.id, photoIDs.contains(dragID) == false {
                resetDrag()
            }
        }
        .sensoryFeedback(.warning, trigger: isDraggingOverDelete) { wasOverDelete, isOverDelete in
            wasOverDelete == false && isOverDelete
        }
        .animation(.interactiveSpring(response: 0.22, dampingFraction: 0.86), value: photos.map(\.id))
    }

    private var isDraggingOverDelete: Bool {
        dragState?.isActive == true && dragState?.isOverDelete == true
    }

    private var canDragPhotos: Bool {
        onRemove != nil || onSwap != nil
    }

    private var canDeletePhotos: Bool {
        onRemove != nil
    }

    @ViewBuilder
    private func photoTile(for photo: MediaAttachment) -> some View {
        let state = dragState
        let isDragging = state?.id == photo.id && state?.isActive == true
        let isDropTarget = state?.targetID == photo.id
        let tile = MediaAttachmentPhotoTile(
            attachment: photo,
            onPreview: onPreview,
            isEditable: canDragPhotos,
            isDropTarget: isDropTarget
        )
        .opacity(isDragging ? 0.16 : 1)
        .background(
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MediaAttachmentPhotoFramePreferenceKey.self,
                    value: [photo.id: proxy.frame(in: .global)]
                )
            }
        )

        if canDragPhotos {
            tile.highPriorityGesture(photoDragGesture(for: photo))
        } else {
            tile
        }
    }

    private func photoDragGesture(for photo: MediaAttachment) -> some Gesture {
        LongPressGesture(minimumDuration: 0.25)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .global))
            .onChanged { value in
                switch value {
                case .first(true):
                    beginDrag(photo)
                case .second(true, let drag?):
                    updateDrag(photo, drag: drag)
                default:
                    break
                }
            }
            .onEnded { _ in
                finishDrag(photo)
            }
    }

    private func beginDrag(_ photo: MediaAttachment) {
        guard dragState?.id != photo.id else { return }

        withAnimation(.spring(response: 0.24, dampingFraction: 0.86)) {
            dragState = PhotoDragState(
                id: photo.id,
                startFrame: tileFrames[photo.id] ?? .zero,
                isActive: false,
                isOverDelete: false,
                targetID: nil
            )
        }
    }

    private func updateDrag(_ photo: MediaAttachment, drag: DragGesture.Value) {
        let previousState = dragState
        let startFrame = previousState?.startFrame ?? tileFrames[photo.id] ?? .zero
        let translationDistance = hypot(drag.translation.width, drag.translation.height)
        let isActive = previousState?.isActive == true || translationDistance >= dragActivationDistance
        let previewCenter = CGPoint(
            x: startFrame.midX + drag.translation.width,
            y: startFrame.midY + drag.translation.height
        )
        let targetFrame = dragCoordinator?.deleteTargetState.targetFrame ?? .zero
        let isOverDelete = isActive
            && canDeletePhotos
            && targetFrame != .zero
            && targetFrame.insetBy(dx: -10, dy: -10).contains(previewCenter)
        let target = isActive && isOverDelete == false ? targetPhoto(at: previewCenter, excluding: photo.id) : nil
        let targetID = target?.id

        let nextDragState = PhotoDragState(
            id: photo.id,
            startFrame: startFrame,
            isActive: isActive,
            isOverDelete: isOverDelete,
            targetID: targetID
        )

        if dragState != nextDragState {
            dragState = nextDragState
        }

        dragCoordinator?.updatePreview(
            attachment: photo,
            frame: startFrame,
            translation: drag.translation,
            isActive: isActive
        )
        dragCoordinator?.updateDeleteTarget(isActive: isActive, isTargeted: isOverDelete)

        guard isActive else { return }

        guard let target else {
            lastSwapTargetID = nil
            return
        }

        if target.id != lastSwapTargetID {
            lastSwapTargetID = target.id
            withAnimation(.interactiveSpring(response: 0.22, dampingFraction: 0.86)) {
                onSwap?(photo, target)
            }
        }
    }

    private func finishDrag(_ photo: MediaAttachment) {
        guard let state = dragState, state.id == photo.id else {
            resetDrag()
            return
        }

        let shouldDelete = state.isOverDelete
        let dropLocation = dragCoordinator?.previewState.center ?? state.startFrame.center
        let target = state.isActive
            ? state.targetID.flatMap { targetID in
                photos.first { $0.id == targetID }
            } ?? targetPhoto(at: dropLocation, excluding: photo.id)
            : nil
        let didSwapTargetID = lastSwapTargetID

        resetDrag()

        if state.isActive, shouldDelete {
            onRemove?(photo)
        } else if let target, target.id != didSwapTargetID {
            withAnimation(.interactiveSpring(response: 0.22, dampingFraction: 0.86)) {
                onSwap?(photo, target)
            }
        }
    }

    private func resetDrag() {
        dragState = nil
        lastSwapTargetID = nil
        dragCoordinator?.reset()
    }

    private func targetPhoto(at location: CGPoint, excluding photoID: String) -> MediaAttachment? {
        photos.first { photo in
            guard photo.id != photoID, let frame = tileFrames[photo.id] else {
                return false
            }

            return frame.insetBy(dx: -6, dy: -6).contains(location)
        }
    }

}

private struct MediaAttachmentPhotoTile: View {
    let attachment: MediaAttachment
    let onPreview: (MediaAttachment) -> Void
    let isEditable: Bool
    let isDropTarget: Bool

    var body: some View {
        previewButton
            .overlay {
                if isDropTarget {
                    RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                        .strokeBorder(Color.accentColor, lineWidth: 2)
                }
            }
    }

    private var previewButton: some View {
        Button {
            onPreview(attachment)
        } label: {
            squareThumbnail
        }
        .buttonStyle(.plain)
        .accessibilityLabel(AppLocalization.string("预览照片"))
        .accessibilityHint(isEditable ? AppLocalization.string("长按拖动可排序或删除") : "")
    }

    private var squareThumbnail: some View {
        GeometryReader { proxy in
            ZStack {
                Color(.secondarySystemFill)

                if let image = UIImage(data: attachment.data) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                } else {
                    Image(systemName: "photo")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: AppDesign.iconCornerRadius))
    }
}

private struct PhotoDragState: Equatable {
    let id: String
    var startFrame: CGRect
    var isActive: Bool
    var isOverDelete: Bool
    var targetID: String?
}

private struct MediaAttachmentPhotoFramePreferenceKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

private extension CGRect {
    var center: CGPoint {
        CGPoint(x: midX, y: midY)
    }
}
