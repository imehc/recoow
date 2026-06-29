import SwiftUI

struct FoodEntryDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: FoodJournalViewModel
    @Bindable var billsViewModel: BillsViewModel
    @State private var entryForEditing: FoodEntry?
    @State private var isConfirmingDeletion = false
    @State private var previewAttachment: MediaAttachment?

    let entryID: String
    let billImageTransition: Namespace.ID

    var body: some View {
        Group {
            if let entry {
                content(for: entry)
            } else {
                ContentUnavailableView(AppLocalization.string("饮食不存在"), systemImage: "fork.knife")
            }
        }
        .navigationTitle(AppLocalization.string("饮食详情"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $entryForEditing) { entry in
            NavigationStack {
                FoodEntryFormView(
                    entry: entry,
                    attachments: attachments,
                    viewModel: viewModel,
                    billsViewModel: billsViewModel
                )
            }
        }
        .fullScreenCover(item: $previewAttachment) { attachment in
            MediaAttachmentPreviewView(
                attachment: attachment,
                attachments: photoAttachments
            )
        }
        .task(id: entryID) {
            await viewModel.loadEntryIfNeeded(id: entryID)
        }
        .task(id: linkedBillIDsKey) {
            await loadLinkedBillIfNeeded()
        }
    }

    private func content(for entry: FoodEntry) -> some View {
        List {
            Section(AppLocalization.string("记录信息")) {
                LabeledContent(AppLocalization.string("食物"), value: entry.title)
                LabeledContent(AppLocalization.string("餐别"), value: entry.foodMealKind.localizedTitle)
                LabeledContent(AppLocalization.string("时间"), value: AppFormatters.dateTime(milliseconds: entry.occurredAt))

                if let portion = entry.normalizedPortion {
                    LabeledContent(AppLocalization.string("份量"), value: portion)
                }

                if let note = entry.normalizedNote {
                    LabeledContent(AppLocalization.string("备注")) {
                        Text(note)
                            .multilineTextAlignment(.trailing)
                    }
                }
            }

            if photoAttachments.isEmpty == false {
                Section(AppLocalization.string("照片")) {
                    FoodEntryReadonlyPhotoStrip(
                        photos: photoAttachments,
                        onPreview: showAttachmentPreview
                    )
                }
            }

            if entry.hasLinkedBills {
                Section(AppLocalization.string("关联账单")) {
                    ForEach(entry.billIDs, id: \.self) { billID in
                        if let linkedBill = billsViewModel.bill(id: billID) {
                            NavigationLink {
                                BillDetailView(
                                    viewModel: billsViewModel,
                                    billID: linkedBill.id,
                                    billImageTransition: imageTransition(for: linkedBill)
                                )
                            } label: {
                                FoodSelectedBillRow(
                                    bill: linkedBill,
                                    billImageTransition: billImageTransition
                                )
                            }
                        } else {
                            Label(AppLocalization.string("账单同步中"), systemImage: "receipt")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(AppLocalization.string("删除"), systemImage: "trash", role: .destructive) {
                    isConfirmingDeletion = true
                }
                .tint(.red)

                Button(AppLocalization.string("编辑"), systemImage: "square.and.pencil") {
                    entryForEditing = entry
                }
            }
        }
        .alert(AppLocalization.format("删除“%@”？", entry.title), isPresented: $isConfirmingDeletion) {
            Button(AppLocalization.string("删除"), role: .destructive) {
                deleteEntry()
            }
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("删除后该记录会从历史中移除。"))
        }
    }

    private var entry: FoodEntry? {
        viewModel.entry(id: entryID)
    }

    private var attachments: [MediaAttachment] {
        viewModel.attachments(for: entryID)
    }

    private var photoAttachments: [MediaAttachment] {
        attachments.filter { $0.kind == .photo }
    }

    private var linkedBillIDsKey: String {
        entry?.billIDs.joined(separator: "|") ?? ""
    }

    private func imageTransition(for bill: BillRecord) -> Namespace.ID? {
        bill.hasImage ? billImageTransition : nil
    }

    private func loadLinkedBillIfNeeded() async {
        guard let entry else { return }
        for id in entry.billIDs {
            await billsViewModel.loadBillIfNeeded(id: id)
        }
    }

    private func deleteEntry() {
        Task {
            await viewModel.deleteEntry(id: entryID)
            dismiss()
        }
    }

    private func showAttachmentPreview(_ attachment: MediaAttachment) {
        previewAttachment = attachment
    }
}

private struct FoodEntryReadonlyPhotoStrip: View {
    let photos: [MediaAttachment]
    let onPreview: (MediaAttachment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(photos) { photo in
                    Button {
                        onPreview(photo)
                    } label: {
                        PhotoThumbnailView(
                            imageData: photo.resolvedData,
                            systemImage: "photo.fill",
                            size: AppDesign.largeThumbnailSize
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(AppLocalization.string("预览照片"))
                }
            }
            .padding(.vertical, 4)
        }
    }
}
