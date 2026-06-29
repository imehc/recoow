import SwiftUI

struct DiaryLinkRow: View {
    let link: DiaryLink

    var body: some View {
        HStack(spacing: 12) {
            AppIconTileView(
                systemImage: link.sourceIcon,
                tint: link.type.tint,
                size: AppDesign.compactIconSize,
                backgroundOpacity: 0.12
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(link.sourceTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                if let subtitle = link.sourceSubtitle, subtitle.isEmpty == false {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text(AppLocalization.string(link.type.title))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 8)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct DiaryLinkedRecordRow: View {
    let record: DiaryLinkedRecord
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            AppIconTileView(
                systemImage: record.systemImage,
                tint: record.sourceType.tint,
                size: AppDesign.compactIconSize,
                backgroundOpacity: 0.12
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(record.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                if let subtitle = record.subtitle, subtitle.isEmpty == false {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.blue)
                    .imageScale(.large)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct DiaryBillLinkRow: View {
    let link: DiaryLink
    let bill: BillRecord
    let billImageTransition: Namespace.ID?

    var body: some View {
        HStack(spacing: 12) {
            thumbnail

            VStack(alignment: .leading, spacing: 4) {
                Text(link.sourceTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                if let subtitle = link.sourceSubtitle, subtitle.isEmpty == false {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text(AppLocalization.string(link.type.title))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 8)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var thumbnail: some View {
        if bill.hasImage {
            if let billImageTransition {
                PhotoThumbnailView(
                    imageData: bill.resolvedImageData,
                    systemImage: "receipt.fill",
                    size: AppDesign.compactIconSize
                )
                .matchedTransitionSource(id: bill.id, in: billImageTransition)
            } else {
                PhotoThumbnailView(
                    imageData: bill.resolvedImageData,
                    systemImage: "receipt.fill",
                    size: AppDesign.compactIconSize
                )
            }
        } else {
            AppIconTileView(
                systemImage: link.sourceIcon,
                tint: link.type.tint,
                size: AppDesign.compactIconSize,
                backgroundOpacity: 0.12
            )
        }
    }
}
