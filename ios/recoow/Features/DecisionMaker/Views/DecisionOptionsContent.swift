import SwiftUI

struct DecisionOptionsContent: View {
    @Bindable var viewModel: DecisionOptionsViewModel
    @State private var isShowingNewOption = false
    @State private var editingOption: DecisionOption?
    @State private var pendingDeletionOption: DecisionOption?
    @State private var pendingDeletionRecord: DecisionChoiceRecord?
    @State private var isShowingDeletionConfirmation = false
    @State private var isShowingHistoryDeletionConfirmation = false
    let choiceRecordImageTransition: Namespace.ID

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button("随机选择", systemImage: "shuffle", action: chooseRandom)
                    .disabled(viewModel.enabledOptions.isEmpty)
            } footer: {
                Text("只会从启用的候选项中随机，权重越高越容易被选中。")
            }

            if let selectedOption = viewModel.selectedOption {
                Section("结果") {
                    DecisionResultView(option: selectedOption)
                }
            }

            if viewModel.choiceRecords.isEmpty == false {
                Section("历史记录") {
                    ForEach(viewModel.choiceRecords.prefix(10)) { record in
                        NavigationLink(value: DecisionChoiceRecordRoute(id: record.id)) {
                            DecisionChoiceRecordRow(
                                record: record,
                                showsCollectionTitle: false,
                                choiceRecordImageTransition: choiceRecordImageTransition
                            )
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                requestDeleteHistory(record)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                }
            }

            Section("候选项") {
                if viewModel.options.isEmpty {
                    ContentUnavailableView {
                        Label("还没有候选项", systemImage: "square.stack.3d.up")
                    } description: {
                        Text("添加至少一个选项后即可随机选择。")
                    } actions: {
                        Button("添加选项", systemImage: "plus", action: showNewOption)
                    }
                } else {
                    ForEach(viewModel.options) { option in
                        DecisionOptionRow(option: option)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    requestDelete(option)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }

                                Button {
                                    editingOption = option
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
            Button("添加选项", systemImage: "plus", action: showNewOption)
        }
        .sheet(isPresented: $isShowingNewOption) {
            NavigationStack {
                DecisionOptionFormView(option: nil, viewModel: viewModel)
            }
        }
        .sheet(item: $editingOption) { option in
            NavigationStack {
                DecisionOptionFormView(option: option, viewModel: viewModel)
            }
        }
        .alert(deletionTitle, isPresented: $isShowingDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后该候选项会从当前集合中移除。")
        }
        .alert(historyDeletionTitle, isPresented: $isShowingHistoryDeletionConfirmation) {
            Button("删除", role: .destructive, action: confirmDeleteHistory)
            Button("取消", role: .cancel, action: clearPendingHistoryDeletion)
        } message: {
            Text("删除后这条选择结果会从历史记录中移除。")
        }
    }

    private var deletionTitle: String {
        guard let option = pendingDeletionOption else {
            return "删除选项？"
        }

        return "删除“\(option.title)”？"
    }

    private func showNewOption() {
        isShowingNewOption = true
    }

    private func chooseRandom() {
        Task {
            await viewModel.chooseRandomOption()
        }
    }

    private func requestDelete(_ option: DecisionOption) {
        pendingDeletionOption = option
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let option = pendingDeletionOption else { return }
        clearPendingDeletion()

        Task {
            await viewModel.delete(id: option.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionOption = nil
        isShowingDeletionConfirmation = false
    }

    private var historyDeletionTitle: String {
        guard let record = pendingDeletionRecord else {
            return "删除选择记录？"
        }

        return "删除“\(record.optionTitle)”？"
    }

    private func requestDeleteHistory(_ record: DecisionChoiceRecord) {
        pendingDeletionRecord = record
        isShowingHistoryDeletionConfirmation = true
    }

    private func confirmDeleteHistory() {
        guard let record = pendingDeletionRecord else { return }
        clearPendingHistoryDeletion()

        Task {
            await viewModel.deleteChoiceRecord(id: record.id)
        }
    }

    private func clearPendingHistoryDeletion() {
        pendingDeletionRecord = nil
        isShowingHistoryDeletionConfirmation = false
    }
}
