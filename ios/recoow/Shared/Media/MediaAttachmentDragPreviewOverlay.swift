import SwiftUI
import UIKit

struct MediaAttachmentDragPreviewState: Equatable {
    var attachment: MediaAttachment?
    var frame = CGRect.zero
    var translation = CGSize.zero
    var isActive = false

    var center: CGPoint {
        CGPoint(
            x: frame.midX + translation.width,
            y: frame.midY + translation.height
        )
    }

    static func == (lhs: MediaAttachmentDragPreviewState, rhs: MediaAttachmentDragPreviewState) -> Bool {
        lhs.attachment?.id == rhs.attachment?.id
            && lhs.frame == rhs.frame
            && lhs.translation == rhs.translation
            && lhs.isActive == rhs.isActive
    }
}

struct MediaAttachmentDragLayer: View {
    @Bindable var coordinator: MediaAttachmentDragCoordinator

    var body: some View {
        GeometryReader { proxy in
            let globalOrigin = proxy.frame(in: .global).origin

            ZStack {
                VStack {
                    Spacer(minLength: 0)
                    DragDeleteTargetOverlay(state: $coordinator.deleteTargetState)
                }
                .zIndex(1)

                MediaAttachmentDragPreviewOverlay(
                    state: coordinator.previewState,
                    coordinateOrigin: globalOrigin
                )
                .zIndex(2)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
    }
}

struct MediaAttachmentDragPreviewOverlay: View {
    let state: MediaAttachmentDragPreviewState
    var coordinateOrigin = CGPoint.zero

    var body: some View {
        Group {
            if state.isActive,
               let attachment = state.attachment {
                MediaAttachmentDragPreviewTile(attachment: attachment)
                    .frame(width: state.frame.width, height: state.frame.height)
                    .scaleEffect(1.04)
                    .shadow(color: Color.black.opacity(0.22), radius: 14, x: 0, y: 8)
                    .position(localCenter)
                    .transition(.opacity)
            }
        }
        .allowsHitTesting(false)
    }

    private var localCenter: CGPoint {
        CGPoint(
            x: state.center.x - coordinateOrigin.x,
            y: state.center.y - coordinateOrigin.y
        )
    }
}

private struct MediaAttachmentDragPreviewTile: View {
    let attachment: MediaAttachment

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(.secondarySystemFill)

                if let image = UIImage(data: attachment.resolvedData) {
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
        .clipShape(.rect(cornerRadius: AppDesign.iconCornerRadius))
    }
}
