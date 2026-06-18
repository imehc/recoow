import SwiftUI

struct DecisionCollectionsContent: View {
    @Bindable var viewModel: DecisionCollectionsViewModel
    @State private var isShowingNewCollection = false
    @State private var editingCollection: DecisionCollection?
    @State private var pendingDeletionCollection: DecisionCollection?
    @State private var isShowingDeletionConfirmation = false

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section("选择集合") {
                if viewModel.collections.isEmpty {
                    ContentUnavailableView {
                        Label("还没有选择集合", systemImage: "shuffle")
                    } description: {
                        Text("添加一个集合后，就可以维护候选项并随机选择。")
                    } actions: {
                        Button("添加集合", systemImage: "plus", action: showNewCollection)
                    }
                } else {
                    ForEach(viewModel.collections) { collection in
                        NavigationLink(value: DecisionCollectionRoute(id: collection.id)) {
                            DecisionCollectionRow(collection: collection)
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                requestDelete(collection)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }

                            Button {
                                editingCollection = collection
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
        .toolbar {
            Button("添加集合", systemImage: "plus", action: showNewCollection)
        }
        .sheet(isPresented: $isShowingNewCollection) {
            NavigationStack {
                DecisionCollectionFormView(collection: nil, viewModel: viewModel)
            }
        }
        .sheet(item: $editingCollection) { collection in
            NavigationStack {
                DecisionCollectionFormView(collection: collection, viewModel: viewModel)
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后该集合和其中的选项会从列表中移除。")
        }
    }

    private var deletionTitle: String {
        guard let collection = pendingDeletionCollection else {
            return "删除集合？"
        }

        return "删除“\(collection.title)”？"
    }

    private func showNewCollection() {
        isShowingNewCollection = true
    }

    private func requestDelete(_ collection: DecisionCollection) {
        pendingDeletionCollection = collection
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let collection = pendingDeletionCollection else { return }
        clearPendingDeletion()

        Task {
            await viewModel.delete(id: collection.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionCollection = nil
        isShowingDeletionConfirmation = false
    }
}
