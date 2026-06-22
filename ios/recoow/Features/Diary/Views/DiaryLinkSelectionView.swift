import SwiftUI
import UIKit

struct DiaryLinkSelectionView: View {
    static let minimumPresentationHeight: CGFloat = 320

    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var records: [DiaryLinkedRecord] = []
    @State private var searchText = ""
    @State private var errorMessage: String?

    let sourceType: DiaryLinkSourceType
    let diaryID: String
    let viewModel: DiaryViewModel
    @Binding var selectedLinks: [DiaryLink]

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if filteredRecords.isEmpty {
                ContentUnavailableView(
                    emptyTitle,
                    systemImage: sourceType.systemImage,
                    description: Text(AppLocalization.string("可关联的记录会显示在这里"))
                )
            } else {
                Section(sourceType.localizedTitle) {
                    ForEach(filteredRecords) { record in
                        Button {
                            toggle(record)
                        } label: {
                            DiaryLinkedRecordRow(
                                record: record,
                                isSelected: selectedKeys.contains(record.id)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(AppLocalization.format("关联%@", sourceType.localizedTitle))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: Text(AppLocalization.string("搜索记录")))
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(AppLocalization.string("完成"), action: done)
            }
        }
        .presentationDetents([.height(preferredPresentationHeight)])
        .presentationDragIndicator(.visible)
        .task {
            loadRecords()
        }
    }

    private var selectedKeys: Set<String> {
        Set(selectedLinks.map(\.sourceKey))
    }

    private var filteredRecords: [DiaryLinkedRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.isEmpty == false else { return records }

        return records.filter { record in
            [
                record.title,
                record.subtitle,
                record.sourceType.localizedTitle
            ]
            .compactMap(\.self)
            .joined(separator: " ")
            .localizedCaseInsensitiveContains(query)
        }
    }

    private var emptyTitle: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? AppLocalization.string("暂无可关联记录")
            : AppLocalization.string("没有匹配记录")
    }

    private var preferredPresentationHeight: CGFloat {
        let rowCount = max(1, min(filteredRecords.count, 6))
        let baseHeight: CGFloat = errorMessage == nil ? 180 : 240
        let fittingHeight = baseHeight + CGFloat(rowCount) * 58
        let maxHeight = max(Self.minimumPresentationHeight, UIScreen.main.bounds.height * 0.82)
        return min(max(Self.minimumPresentationHeight, fittingHeight), maxHeight)
    }

    @MainActor
    private func loadRecords() {
        do {
            records = try DiaryLinkSourceRegistry.provider(for: sourceType)?.fetchRecords(container) ?? []
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func toggle(_ record: DiaryLinkedRecord) {
        if let index = selectedLinks.firstIndex(where: { $0.sourceKey == record.id }) {
            selectedLinks.remove(at: index)
        } else {
            selectedLinks.append(viewModel.makeLink(diaryID: diaryID, record: record))
        }
    }

    private func done() {
        dismiss()
    }
}
