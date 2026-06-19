import SwiftUI

struct StoredItemDetailView: View {
    @Bindable var viewModel: ItemLocatorViewModel
    @State private var editingItem: StoredItem?

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
            Button("编辑", systemImage: "pencil") {
                editingItem = item
            }
        }
    }
}
