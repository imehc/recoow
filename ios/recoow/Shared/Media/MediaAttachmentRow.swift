import AVFoundation
import SwiftUI
import UIKit

struct MediaAttachmentRow: View {
    let attachment: MediaAttachment
    let onPreview: (MediaAttachment) -> Void

    @State private var audioPlayer: AVAudioPlayer?
    @State private var isPlayingAudio = false
    @State private var audioErrorMessage: String?

    var body: some View {
        HStack(spacing: 12) {
            thumbnail

            VStack(alignment: .leading, spacing: 4) {
                Text(attachment.displayTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text(attachment.detailText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            trailingControl
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .alert(AppLocalization.string("无法播放语音"), isPresented: .isPresent($audioErrorMessage)) {
            Button(AppLocalization.string("确定"), role: .cancel) {
                audioErrorMessage = nil
            }
        } message: {
            Text(audioErrorMessage ?? "")
        }
    }

    @ViewBuilder
    private var thumbnail: some View {
        switch attachment.kind {
        case .photo:
            if let image = UIImage(data: attachment.data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: AppDesign.mediaAttachmentThumbnailSize, height: AppDesign.mediaAttachmentThumbnailSize)
                    .clipShape(.rect(cornerRadius: AppDesign.iconCornerRadius))
            } else {
                fallbackThumbnail
            }
        case .audio:
            fallbackThumbnail
        }
    }

    private var fallbackThumbnail: some View {
        Image(systemName: attachment.kind.systemImage)
            .font(.headline)
            .foregroundStyle(.blue)
            .frame(width: AppDesign.mediaAttachmentThumbnailSize, height: AppDesign.mediaAttachmentThumbnailSize)
            .background(Color.blue.opacity(0.12), in: .rect(cornerRadius: AppDesign.iconCornerRadius))
    }

    @ViewBuilder
    private var trailingControl: some View {
        switch attachment.kind {
        case .photo:
            Button {
                onPreview(attachment)
            } label: {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tertiary)
            .accessibilityLabel(AppLocalization.string("预览"))
        case .audio:
            Button {
                toggleAudio()
            } label: {
                Image(systemName: isPlayingAudio ? "pause.circle.fill" : "play.circle.fill")
                    .imageScale(.large)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(AppLocalization.string(isPlayingAudio ? "暂停语音" : "播放语音"))
        }
    }

    private func toggleAudio() {
        if isPlayingAudio {
            audioPlayer?.pause()
            isPlayingAudio = false
            return
        }

        do {
            let player = try AVAudioPlayer(data: attachment.data)
            player.prepareToPlay()
            player.play()
            audioPlayer = player
            isPlayingAudio = true
        } catch {
            audioErrorMessage = error.localizedDescription
        }
    }
}
