import SwiftUI

struct ItemCategoriesView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: ItemLocatorViewModel
    @State private var isShowingNewCategory = false
    @State private var editingCategory: ItemCategory?
    @State private var pendingDeletionCategory: ItemCategory?
    @State private var isShowingDeletionConfirmation = false

    var body: some View {
        List {
            if viewModel.categories.isEmpty {
                ContentUnavailableView {
                    Label("还没有分类", systemImage: "folder")
                } description: {
                    Text("添加分类后，可以按类别筛选物品。")
                } actions: {
                    Button("添加分类", systemImage: "plus", action: showNewCategory)
                }
            } else {
                ForEach(viewModel.categories) { category in
                    Label {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(category.name)
                                .font(.headline)

                            if let note = category.note, note.isEmpty == false {
                                Text(note)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }
                    } icon: {
                        Image(systemName: "folder")
                            .foregroundStyle(.blue)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            requestDelete(category)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }

                        Button {
                            editingCategory = category
                        } label: {
                            Label("编辑", systemImage: "pencil")
                        }
                        .tint(.blue)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("分类")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("完成", action: close)
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button("添加分类", systemImage: "plus", action: showNewCategory)
            }
        }
        .sheet(isPresented: $isShowingNewCategory) {
            NavigationStack {
                ItemCategoryFormView(category: nil, viewModel: viewModel)
            }
        }
        .sheet(item: $editingCategory) { category in
            NavigationStack {
                ItemCategoryFormView(category: category, viewModel: viewModel)
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除分类不会删除物品记录。")
        }
    }

    private var deletionTitle: String {
        guard let category = pendingDeletionCategory else {
            return "删除分类？"
        }

        return "删除“\(category.name)”？"
    }

    private func close() {
        dismiss()
    }

    private func showNewCategory() {
        isShowingNewCategory = true
    }

    private func requestDelete(_ category: ItemCategory) {
        pendingDeletionCategory = category
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let category = pendingDeletionCategory else { return }
        clearPendingDeletion()

        Task {
            await viewModel.deleteCategory(id: category.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionCategory = nil
        isShowingDeletionConfirmation = false
    }
}
