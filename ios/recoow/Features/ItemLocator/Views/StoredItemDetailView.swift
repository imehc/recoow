import SwiftUI

struct StoredItemDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: ItemLocatorViewModel
    @State private var editingItem: StoredItem?
    @State private var pendingDeletionItem: StoredItem?
    @State private var isShowingDeletionConfirmation = false

    let itemID: String
    let itemImageTransition: Namespace.ID?

    var body: some View {
        Group {
            if let item = viewModel.item(id: itemID) {
                content(for: item)
            } else {
                ContentUnavailableView("记录不存在", systemImage: "shippingbox")
            }
        }
        .navigationTitle(viewModel.item(id: itemID)?.title ?? "物品详情")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editingItem) { item in
            NavigationStack {
                StoredItemFormView(item: item, viewModel: viewModel)
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后这条物品位置记录会从列表中移除。")
        }
        .task(id: itemID) {
            await viewModel.loadCategoriesIfNeeded()
            await viewModel.loadItemIfNeeded(id: itemID)
        }
    }

    @ViewBuilder
    private func content(for item: StoredItem) -> some View {
        if item.imageData != nil, let itemImageTransition {
            form(for: item)
                .navigationTransition(.zoom(sourceID: itemID, in: itemImageTransition))
        } else {
            form(for: item)
        }
    }

    private func form(for item: StoredItem) -> some View {
        Form {
            if item.imageData != nil {
                Section {
                    PhotoSquareImageView(imageData: item.imageData, systemImage: "shippingbox")
                        .padding(.vertical, 8)
                }
            }

            Section("位置") {
                LabeledContent("物品", value: item.title)
                LabeledContent("分类", value: viewModel.categoryName(for: item))
                LabeledContent("位置说明", value: item.location)
            }

            Section("辅助记忆") {
                if let note = item.note, note.isEmpty == false {
                    LabeledContent("备注", value: note)
                }

                if let tags = item.tags, tags.isEmpty == false {
                    LabeledContent("标签", value: tags)
                }

                if let searchKeywords = item.searchKeywords, searchKeywords.isEmpty == false {
                    LabeledContent("关键词", value: searchKeywords)
                }

                LabeledContent("更新时间", value: AppFormatters.dateTime(milliseconds: item.updatedAt))
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("删除", systemImage: "trash", role: .destructive) {
                    requestDelete(item)
                }
                .tint(.red)

                Button("编辑", systemImage: "square.and.pencil") {
                    editingItem = item
                }
            }
        }
    }

    private var deletionTitle: String {
        guard let item = pendingDeletionItem else {
            return "删除物品记录？"
        }

        return "删除“\(item.title)”？"
    }

    private func requestDelete(_ item: StoredItem) {
        pendingDeletionItem = item
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let item = pendingDeletionItem else { return }
        clearPendingDeletion()

        Task {
            await viewModel.deleteItem(id: item.id)
            dismiss()
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionItem = nil
        isShowingDeletionConfirmation = false
    }
}
