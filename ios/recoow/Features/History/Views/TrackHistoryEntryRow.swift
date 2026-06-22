import SwiftUI

struct TrackHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let track: Track
    let pointCount: Int
    let isActive: Bool
    let activeElapsedSeconds: Int64
    let activeDistanceMeters: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(track.name)
                    .font(.headline)
                    .lineLimit(2)

                Spacer(minLength: 8)

                HistoryTypeTag(route: .locationTracker)
            }

            if let note = track.note, note.isEmpty == false {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Text(AppFormatters.dateTime(milliseconds: track.startedAt, locale: locale))
                .font(.footnote)
                .foregroundStyle(.secondary)

            MetadataLineView {
                MetadataItemView(
                    title: AppFormatters.distance(displayDistance),
                    systemImage: "point.topleft.down.curvedto.point.bottomright.up"
                )
                MetadataItemView(
                    title: AppFormatters.duration(displayDuration),
                    systemImage: "timer"
                )
                MetadataItemView(title: "\(pointCount)", systemImage: "number")
            }

            if isActive {
                MetadataItemView(titleKey: "记录中", systemImage: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(.green)
            } else if track.status == .paused {
                MetadataItemView(titleKey: "已暂停", systemImage: "pause.circle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 4)
    }

    private var displayDistance: Double {
        isActive ? activeDistanceMeters : track.distanceMeters
    }

    private var displayDuration: Int64 {
        isActive ? activeElapsedSeconds : track.durationSeconds
    }
}
