import SwiftUI

struct DecisionChoiceHistorySection: View {
    @Bindable var viewModel: DecisionChoiceHistoryViewModel
    @State private var pendingDeletionRecord: DecisionChoiceRecord?
    @State private var isShowingDeletionConfirmation = false

    var body: some View {
        if let errorMessage = viewModel.errorMessage {
            Section {
                Label(errorMessage, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }
        }

        Section("选什么") {
            if viewModel.records.isEmpty {
                ContentUnavailableView("暂无选择记录", systemImage: "shuffle")
            } else {
                ForEach(viewModel.records) { record in
                    NavigationLink(value: DecisionChoiceRecordRoute(id: record.id)) {
                        DecisionChoiceRecordRow(record: record)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            requestDelete(record)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }
        }
        .confirmationDialog(
            deletionTitle,
            isPresented: $isShowingDeletionConfirmation,
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive, action: confirmDelete)
            Button("取消", role: .cancel, action: clearPendingDeletion)
        } message: {
            Text("删除后这条选择结果会从历史记录中移除。")
        }
    }

    private var deletionTitle: String {
        guard let record = pendingDeletionRecord else {
            return "删除选择记录？"
        }

        return "删除“\(record.optionTitle)”？"
    }

    private func requestDelete(_ record: DecisionChoiceRecord) {
        pendingDeletionRecord = record
        isShowingDeletionConfirmation = true
    }

    private func confirmDelete() {
        guard let record = pendingDeletionRecord else { return }
        clearPendingDeletion()

        Task {
            await viewModel.deleteRecord(id: record.id)
        }
    }

    private func clearPendingDeletion() {
        pendingDeletionRecord = nil
        isShowingDeletionConfirmation = false
    }
}
