import SwiftUI

struct DiaryRow: View {
    let entry: DiaryEntry
    let tagTitles: [String]
    let links: [DiaryLink]
    let attachments: [MediaAttachment]

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            DiaryMoodIconView(mood: entry.diaryMood)

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(entry.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(AppFormatters.date(milliseconds: entry.occurredAt))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Text(entry.previewText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                DiaryMetadataLine(
                    entry: entry,
                    tagTitles: tagTitles,
                    links: links,
                    attachments: attachments
                )
            }
        }
        .padding(.vertical, 4)
    }
}

struct DiaryMoodIconView: View {
    let mood: DiaryMood
    var size: CGFloat = AppDesign.listIconSize

    var body: some View {
        AppIconTileView(
            systemImage: mood.systemImage,
            tint: mood.tint,
            size: size,
            backgroundOpacity: 0.12
        )
    }
}

struct DiaryMetadataLine: View {
    let entry: DiaryEntry
    let tagTitles: [String]
    let links: [DiaryLink]
    let attachments: [MediaAttachment]

    var body: some View {
        MetadataLineView {
            MetadataItemView(
                title: AppLocalization.string(entry.diaryMood.title),
                systemImage: entry.diaryMood.systemImage
            )

            if tagTitles.isEmpty == false {
                MetadataItemView(
                    title: tagTitles.prefix(2).joined(separator: AppLocalization.string("列表分隔符")),
                    systemImage: "tag"
                )
            }

            if links.isEmpty == false {
                MetadataItemView(
                    title: AppLocalization.format("%d 个关联", links.count),
                    systemImage: "link"
                )
            }

            if attachments.isEmpty == false {
                MetadataItemView(
                    title: AppLocalization.format("%d 个附件", attachments.count),
                    systemImage: "paperclip"
                )
            }
        }
    }
}
