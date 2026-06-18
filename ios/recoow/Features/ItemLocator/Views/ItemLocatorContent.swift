import SwiftUI

struct ItemLocatorContent: View {
    @Bindable var viewModel: ItemLocatorViewModel
    @State private var isShowingNewItem = false
    @State private var isShowingCategories = false
    @State private var editingItem: StoredItem?
    @State private var pendingDeletionItem: StoredItem?
    @State private var isShowingDeletionConfirmation = false
    let itemImageTransition: Namespace.ID

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section("筛选") {
                Picker("分类", selection: $viewModel.selectedCategoryID) {
                    Text("全部").tag(nil as String?)

                    ForEach(viewModel.categories) { category in
                        Text(category.name).tag(Optional(category.id))
                    }
                }
            }

            Section("物品") {
                if viewModel.items.isEmpty {
                    ContentUnavailableView {
                        Label("还没有物品记录", systemImage: "shippingbox")
                    } description: {
                        Text("添加物品的位置、照片和备注，之后可以快速找回。")
                    } actions: {
                        Button("添加物品", systemImage: "plus", action: showNewItem)
                    }
                } else if viewModel.filteredItems.isEmpty {
                    ContentUnavailableView.search
                } else {
                    ForEach(viewModel.filteredItems) { item in
                        NavigationLink(value: StoredItemRoute(id: item.id)) {
                            StoredItemRow(
                                item: item,
                                categoryName: viewModel.categoryName(for: item),
                                itemImageTransition: itemImageTransition
                            )
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                requestDelete(item)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }

                            Button {
                                editingItem = item
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $viewModel.searchText, prompt: "搜索物品、位置、备注")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("分类", systemImage: "folder", action: showCategories)
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button("添加物品", systemImage: "plus", action: showNewItem)
            }
        }
        .sheet(isPresented: $isShowingNewItem) {
            NavigationStack {
                StoredItemFormView(item: nil, viewModel: viewModel)
            }
        }
        .sheet(item: $editingItem) { item in
            NavigationStack {
                StoredItemFormView(item: item, viewModel: viewModel)
            }
        }
        .sheet(isPresented: $isShowingCategories) {
            NavigationStack {
                ItemCategoriesView(viewModel: viewModel)
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后这条物品位置记录会从列表中移除。")
        }
    }

    private var deletionTitle: String {
        guard let item = pendingDeletionItem else {
            return "删除物品记录？"
        }

        return "删除“\(item.title)”？"
    }

    private func showNewItem() {
        isShowingNewItem = true
    }

    private func showCategories() {
        isShowingCategories = true
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
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionItem = nil
        isShowingDeletionConfirmation = false
    }
}
