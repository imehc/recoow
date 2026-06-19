import SwiftUI

struct TrackHistoryRow: View {
    let track: Track
    let pointCount: Int
    let isActive: Bool
    let activeElapsedSeconds: Int64
    let activeDistanceMeters: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                Text(track.name)
                    .font(.headline)
                    .lineLimit(2)

                Spacer(minLength: 8)

                if isActive {
                    MetadataItemView(titleKey: "记录中", systemImage: "dot.radiowaves.left.and.right")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            }

            if let note = track.note, note.isEmpty == false {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

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
        }
        .padding(.vertical, 6)
    }

    private var displayDistance: Double {
        isActive ? activeDistanceMeters : track.distanceMeters
    }

    private var displayDuration: Int64 {
        isActive ? activeElapsedSeconds : track.durationSeconds
    }
}
