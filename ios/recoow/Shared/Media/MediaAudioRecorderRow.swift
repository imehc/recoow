import SwiftUI

struct MediaAudioRecorderRow: View {
    @Bindable var recorder: MediaAudioRecorder
    let onToggleRecording: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            FormRowIconView(systemImage: recorder.isRecording ? "waveform" : "mic.fill", tint: tint)

            if recorder.isRecording {
                AudioRecordingWaveformView(levels: recorder.meterLevels)
                    .frame(height: 26)
                    .frame(minWidth: 72, maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(1)
                    .accessibilityLabel(AppLocalization.string("实时录音波形"))
            } else {
                VStack(alignment: .leading, spacing: 3) {
                    Text(AppLocalization.string("录音"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Text(AppLocalization.string("添加语音附件"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .layoutPriority(1)
            }

            if recorder.isRecording == false {
                Spacer(minLength: 8)
            }

            if recorder.isRecording {
                recordingTrailingContent
            } else {
                FormRowIconButton(
                    systemImage: "mic.fill",
                    tint: .blue,
                    accessibilityLabel: AppLocalization.string("录制语音"),
                    action: onToggleRecording
                )
            }
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
        .animation(.snappy(duration: 0.18), value: recorder.isRecording)
    }

    private var tint: Color {
        recorder.isRecording ? .red : .blue
    }

    private var recordingTrailingContent: some View {
        HStack(spacing: 6) {
            durationLabel
            recordingButtons
        }
    }

    private var durationLabel: some View {
        Text(MediaAttachment.formatDuration(recorder.elapsedSeconds))
            .font(.footnote.monospacedDigit().weight(.semibold))
            .foregroundStyle(.red)
            .frame(minWidth: 38, alignment: .trailing)
            .lineLimit(1)
            .accessibilityLabel(AppLocalization.string("录音时长"))
    }

    private var recordingButtons: some View {
        HStack(spacing: 0) {
            FormRowIconButton(
                systemImage: "xmark",
                tint: Color(.systemGray),
                accessibilityLabel: AppLocalization.string("取消录音"),
                action: recorder.cancelRecording
            )

            FormRowIconButton(
                systemImage: "stop.fill",
                tint: .red,
                accessibilityLabel: AppLocalization.string("完成录音"),
                action: onToggleRecording
            )
        }
    }
}

private struct AudioRecordingWaveformView: View {
    let levels: [Double]

    var body: some View {
        GeometryReader { proxy in
            let metrics = barMetrics(in: proxy.size.width)

            HStack(alignment: .center, spacing: metrics.spacing) {
                ForEach(Array(levels.enumerated()), id: \.offset) { index, level in
                    Capsule()
                        .fill(Color.red.opacity(0.78))
                        .frame(
                            width: metrics.width,
                            height: barHeight(level: level, index: index, maxHeight: proxy.size.height)
                        )
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .leading)
        }
        .frame(maxWidth: .infinity)
        .clipped()
        .animation(.interactiveSpring(response: 0.16, dampingFraction: 0.78), value: levels)
        .accessibilityHidden(true)
    }

    private func barMetrics(in availableWidth: CGFloat) -> (width: CGFloat, spacing: CGFloat) {
        guard levels.isEmpty == false else { return (3, 3) }

        let spacing: CGFloat = availableWidth < 240 ? 2 : 3
        let spacingWidth = spacing * CGFloat(max(0, levels.count - 1))
        let availableBarWidth = max(0, availableWidth - spacingWidth)
        let width = max(2, min(8, availableBarWidth / CGFloat(levels.count)))
        return (width, spacing)
    }

    private func barHeight(level: Double, index: Int, maxHeight: CGFloat) -> CGFloat {
        let shapeBias = 0.76 + 0.24 * sin(Double(index) * 0.82)
        let scaledLevel = min(1, max(0.04, level * shapeBias))
        return min(maxHeight, max(5, 5 + CGFloat(scaledLevel) * (maxHeight - 5)))
    }
}
