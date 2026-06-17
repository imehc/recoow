import SwiftUI

struct StoredItemDetailView: View {
    @Bindable var viewModel: ItemLocatorViewModel
    @State private var editingItem: StoredItem?

    let itemID: String

    var body: some View {
        Group {
            if let item = viewModel.item(id: itemID) {
                Form {
                    Section {
                        HStack {
                            Spacer()
                            PhotoThumbnailView(imageData: item.imageData, systemImage: "shippingbox", size: 180)
                            Spacer()
                        }
                        .padding(.vertical, 8)
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
    }

}
