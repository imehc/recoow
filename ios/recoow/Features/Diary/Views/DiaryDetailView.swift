import SwiftUI

struct DiaryDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: DiaryViewModel
    @State private var entryPendingDeletion: DiaryEntry?
    @State private var presentedSheet: PresentedSheet?
    @State private var previewPhotoAttachment: MediaAttachment?

    let diaryID: String

    private enum PresentedSheet: Identifiable {
        case edit(DiaryEntry)
        case attachmentPreview(MediaAttachment)

        var id: String {
            switch self {
            case .edit(let entry):
                "edit-\(entry.id)"
            case .attachmentPreview(let attachment):
                "attachment-\(attachment.id)"
            }
        }
    }

    var body: some View {
        Group {
            if let entry {
                List {
                    if let errorMessage = viewModel.errorMessage {
                        Section {
                            Label(errorMessage, systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.red)
                        }
                    }

                    Section(AppLocalization.string("内容")) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(entry.title)
                                .font(.title2.weight(.semibold))
                                .frame(maxWidth: .infinity, alignment: .leading)

                            Text(entry.previewText)
                                .font(.body)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 6)
                    }

                    if attachments.isEmpty == false {
                        MediaAttachmentListSection(
                            attachments: attachments,
                            onPreview: showAttachmentPreview
                        )
                    }

                    Section(AppLocalization.string("记录信息")) {
                        LabeledContent {
                            Text(AppFormatters.dateTime(milliseconds: entry.occurredAt))
                        } label: {
                            FormInfoLabelView(title: "日期", systemImage: "calendar", tint: .blue)
                        }

                        LabeledContent {
                            DiaryMoodChipView(mood: entry.diaryMood)
                        } label: {
                            FormInfoLabelView(title: "心情", systemImage: entry.diaryMood.systemImage, tint: entry.diaryMood.tint)
                        }

                        if resolvedTags.isEmpty == false {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(spacing: 12) {
                                    FormInfoLabelView(title: "标签", systemImage: "tag", tint: Color.accentColor)

                                    Spacer(minLength: 12)

                                    Text(AppLocalization.format("%d 个标签", resolvedTags.count))
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }

                                DiarySelectedTagChipsView(tags: resolvedTags)
                                    .padding(.leading, FormInfoLabelView.contentLeadingInset)
                            }
                        }

                        if let locationText = entry.locationText {
                            LabeledContent {
                                Text(locationText)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            } label: {
                                FormInfoLabelView(title: "位置", systemImage: "location", tint: .blue)
                            }
                        }
                    }

                    if links.isEmpty == false {
                        Section(AppLocalization.string("关联记录")) {
                            ForEach(links) { link in
                                if let route = HistoryDetailRoute(diaryLink: link) {
                                    NavigationLink(value: route) {
                                        DiaryLinkRow(link: link)
                                    }
                                } else {
                                    DiaryLinkRow(link: link)
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button(AppLocalization.string("删除"), systemImage: "trash") {
                            entryPendingDeletion = entry
                        }
                        .tint(.red)

                        Button(AppLocalization.string("编辑"), systemImage: "square.and.pencil") {
                            showEditSheet(entry)
                        }
                    }
                }
                .alert(
                    entryPendingDeletion.map { AppLocalization.format("删除“%@”？", $0.title) } ?? "",
                    isPresented: .isPresent($entryPendingDeletion),
                    presenting: entryPendingDeletion
                ) { entry in
                    Button(AppLocalization.string("删除"), role: .destructive) {
                        confirmDelete(entry)
                    }
                    Button(AppLocalization.string("取消"), role: .cancel) {
                        entryPendingDeletion = nil
                    }
                } message: { _ in
                    Text(AppLocalization.string("删除后该记录会从历史中移除。"))
                }
            } else {
                ContentUnavailableView(AppLocalization.string("日记不存在"), systemImage: "book.closed")
            }
        }
        .navigationTitle(AppLocalization.string("日记详情"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $presentedSheet) { sheet in
            presentedSheetView(sheet)
        }
        .fullScreenCover(item: $previewPhotoAttachment) { attachment in
            MediaAttachmentPreviewView(
                attachment: attachment,
                attachments: attachments
            )
        }
        .task {
            await viewModel.loadEntryIfNeeded(id: diaryID)
        }
    }

    @ViewBuilder
    private func presentedSheetView(_ sheet: PresentedSheet) -> some View {
        switch sheet {
        case .edit(let entry):
            NavigationStack {
                DiaryFormView(
                    entry: entry,
                    links: links,
                    attachments: attachments,
                    viewModel: viewModel
                )
            }
        case .attachmentPreview(let attachment):
            MediaAttachmentPreviewView(attachment: attachment)
        }
    }

    private var entry: DiaryEntry? {
        viewModel.entry(id: diaryID)
    }

    private var links: [DiaryLink] {
        viewModel.links(for: diaryID)
    }

    private var attachments: [MediaAttachment] {
        viewModel.attachments(for: diaryID)
    }

    private var resolvedTags: [DiaryTagReference] {
        guard let entry else { return [] }
        return viewModel.resolvedTagReferences(for: entry)
    }

    private func showEditSheet(_ entry: DiaryEntry) {
        presentedSheet = .edit(entry)
    }

    private func showAttachmentPreview(_ attachment: MediaAttachment) {
        if attachment.kind == .photo {
            previewPhotoAttachment = attachment
        } else {
            presentedSheet = .attachmentPreview(attachment)
        }
    }

    private func confirmDelete(_ entry: DiaryEntry) {
        entryPendingDeletion = nil

        Task {
            await viewModel.deleteEntry(id: entry.id)
            dismiss()
        }
    }
}

private struct DiaryMoodChipView: View {
    let mood: DiaryMood

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: mood.systemImage)
                .imageScale(.small)

            Text(AppLocalization.string(mood.title))
                .lineLimit(1)
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(mood.tint)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(mood.tint.opacity(0.12), in: Capsule())
        .overlay {
            Capsule()
                .strokeBorder(mood.tint.opacity(0.2), lineWidth: 1)
        }
    }
}
