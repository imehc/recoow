import SwiftUI
import UIKit

struct MediaAssetLibraryManagementView: View {
    let repository: MediaAssetRepository

    @State private var items: [MediaAssetLibraryItem] = []
    @State private var photoPickerPurpose: PhotoPickerPurpose?
    @State private var editingAsset: MediaAsset?
    @State private var deletionRequest: MediaAssetDeletionRequest?
    @State private var previewRequest: MediaAssetPreviewRequest?
    @State private var errorMessage: String?
    @State private var successMessage: String?
    @State private var isSelectionMode = false
    @State private var selectedItemIDs = Set<String>()

    private let columns = [
        GridItem(.adaptive(minimum: 112), spacing: 12)
    ]

    var body: some View {
        Group {
            if items.isEmpty {
                emptyView
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(items) { item in
                            MediaAssetManagementTile(
                                item: item,
                                repository: repository,
                                isSelectionMode: isSelectionMode,
                                isSelected: selectedItemIDs.contains(item.id),
                                isSelectable: item.referenceCount == 0,
                                onPreview: { preview(item.asset) },
                                onToggleSelection: { toggleSelection(for: item) },
                                onUnavailableSelection: { showReferencedSelectionMessage() },
                                onEdit: { editingAsset = item.asset },
                                onReplace: { photoPickerPurpose = .replace(item.asset) },
                                onDelete: { requestDelete(item) }
                            )
                        }
                    }
                    .padding(12)
                }
            }
        }
        .navigationTitle(selectionTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isSelectionMode {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalization.string("取消"), action: exitSelectionMode)
                }

                ToolbarItemGroup(placement: .primaryAction) {
                    Button(
                        selectionToggleTitle,
                        systemImage: selectionToggleSystemImage,
                        action: toggleAllSelectableItems
                    )
                    .disabled(selectableItems.isEmpty)

                    Button(AppLocalization.string("删除"), systemImage: "trash", role: .destructive, action: requestDeleteSelectedItems)
                        .disabled(selectedItemIDs.isEmpty)
                }
            } else {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button(AppLocalization.string("选择"), systemImage: "checklist", action: enterSelectionMode)
                        .disabled(selectableItems.isEmpty)

                    Button {
                        photoPickerPurpose = .add
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel(AppLocalization.string("添加素材"))
                }
            }
        }
        .task {
            loadItems()
        }
        .fullScreenCover(item: $photoPickerPurpose) { purpose in
            PhotoSourcePickerView(
                onPhotoPicked: { data in
                    handlePickedPhoto(data, purpose: purpose)
                },
                onClose: {
                    photoPickerPurpose = nil
                }
            )
        }
        .fullScreenCover(item: $previewRequest) { request in
            PhotoPreviewView(items: request.items, initialID: request.initialID)
        }
        .navigationDestination(isPresented: editingPresence) {
            if let editingAsset,
               let data = repository.data(for: editingAsset),
               let image = UIImage(data: data) {
                PhotoEditorView(
                    image: image,
                    onCancel: { self.editingAsset = nil },
                    onSave: { data in
                        saveEditedPhoto(data, asset: editingAsset)
                    }
                )
            } else {
                ContentUnavailableView(AppLocalization.string("无法编辑当前图片，请重试"), systemImage: "photo")
            }
        }
        .alert(
            deletionRequest.map(deletionTitle) ?? "",
            isPresented: .isPresent($deletionRequest),
            presenting: deletionRequest
        ) { request in
            Button(AppLocalization.string("删除"), role: .destructive) {
                delete(request.items)
            }

            Button(AppLocalization.string("取消"), role: .cancel) {
                deletionRequest = nil
            }
        } message: { request in
            Text(deletionDetail(for: request))
        }
        .alert(AppLocalization.string("素材库操作失败"), isPresented: .isPresent($errorMessage)) {
            Button(AppLocalization.string("确定"), role: .cancel) {
                errorMessage = nil
            }
        } message: {
            Text(errorMessage ?? "")
        }
        .alert(AppLocalization.string("完成"), isPresented: .isPresent($successMessage)) {
            Button(AppLocalization.string("确定"), role: .cancel) {
                successMessage = nil
            }
        } message: {
            Text(successMessage ?? "")
        }
    }

    private var emptyView: some View {
        ContentUnavailableView {
            Label(AppLocalization.string("暂无素材"), systemImage: "rectangle.stack")
        } description: {
            Text(AppLocalization.string("添加后的图片可以被日记、饮食等附件引用，重复使用时不会再复制一份。"))
        } actions: {
            Button(AppLocalization.string("添加素材"), systemImage: "plus") {
                photoPickerPurpose = .add
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var editingPresence: Binding<Bool> {
        Binding {
            editingAsset != nil
        } set: { isPresented in
            if isPresented == false {
                editingAsset = nil
            }
        }
    }

    private var selectionTitle: String {
        guard isSelectionMode else {
            return AppLocalization.string("媒体素材库")
        }

        if selectedItemIDs.isEmpty {
            return AppLocalization.string("选择素材")
        }

        return AppLocalization.format("已选择 %d 个素材", selectedItemIDs.count)
    }

    private var selectableItems: [MediaAssetLibraryItem] {
        items.filter { $0.referenceCount == 0 }
    }

    private var selectableItemIDs: Set<String> {
        Set(selectableItems.map(\.id))
    }

    private var isAllSelectableSelected: Bool {
        selectableItemIDs.isEmpty == false && selectedItemIDs.isSuperset(of: selectableItemIDs)
    }

    private var selectionToggleTitle: String {
        AppLocalization.string(isAllSelectableSelected ? "取消全选" : "全选")
    }

    private var selectionToggleSystemImage: String {
        isAllSelectableSelected ? "xmark.square" : "checkmark.square"
    }

    private func loadItems() {
        do {
            let loadedItems = try repository.fetchImageAssetLibraryItems()
            items = loadedItems
            selectedItemIDs.formIntersection(Set(loadedItems.filter { $0.referenceCount == 0 }.map(\.id)))
            if isSelectionMode, loadedItems.contains(where: { $0.referenceCount == 0 }) == false {
                exitSelectionMode()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handlePickedPhoto(_ data: Data, purpose: PhotoPickerPurpose) {
        do {
            switch purpose {
            case .add:
                _ = try repository.createImageAsset(data: data)
                successMessage = AppLocalization.string("素材已添加。")
            case .replace(let asset):
                _ = try repository.replaceImageAsset(id: asset.id, data: data)
                successMessage = AppLocalization.string("素材已替换。")
            }

            loadItems()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func saveEditedPhoto(_ data: Data, asset: MediaAsset) {
        do {
            _ = try repository.replaceImageAsset(id: asset.id, data: data)
            editingAsset = nil
            successMessage = AppLocalization.string("素材已更新。")
            loadItems()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func preview(_ asset: MediaAsset) {
        let previewItems = items.compactMap { item -> PhotoPreviewItem? in
            guard let data = repository.data(for: item.asset) else { return nil }

            return PhotoPreviewItem(
                id: item.asset.id,
                imageData: data,
                title: AppLocalization.string("媒体素材")
            )
        }

        guard previewItems.contains(where: { $0.id == asset.id }) else {
            errorMessage = AppLocalization.string("无法预览图片")
            return
        }

        previewRequest = MediaAssetPreviewRequest(
            initialID: asset.id,
            items: previewItems
        )
    }

    private func requestDelete(_ item: MediaAssetLibraryItem) {
        guard item.referenceCount == 0 else {
            // 被业务记录引用的素材必须保留 asset_id 稳定性，只允许原地编辑或替换。
            errorMessage = AppLocalization.string("有引用的素材不能删除。")
            return
        }

        deletionRequest = MediaAssetDeletionRequest(items: [item])
    }

    private func delete(_ items: [MediaAssetLibraryItem]) {
        do {
            for item in items {
                try repository.deleteImageAsset(id: item.asset.id)
            }

            deletionRequest = nil
            selectedItemIDs.subtract(Set(items.map(\.id)))
            if isSelectionMode, selectedItemIDs.isEmpty {
                exitSelectionMode()
            }

            successMessage = deletedMessage(for: items.count)
            loadItems()
        } catch {
            deletionRequest = nil
            loadItems()
            errorMessage = error.localizedDescription
        }
    }

    private func enterSelectionMode() {
        selectedItemIDs.removeAll()
        isSelectionMode = true
    }

    private func exitSelectionMode() {
        selectedItemIDs.removeAll()
        isSelectionMode = false
    }

    private func toggleSelection(for item: MediaAssetLibraryItem) {
        guard isSelectionMode else { return }

        guard item.referenceCount == 0 else {
            showReferencedSelectionMessage()
            return
        }

        if selectedItemIDs.contains(item.id) {
            selectedItemIDs.remove(item.id)
        } else {
            selectedItemIDs.insert(item.id)
        }
    }

    private func toggleAllSelectableItems() {
        if isAllSelectableSelected {
            selectedItemIDs.removeAll()
        } else {
            selectedItemIDs = selectableItemIDs
        }
    }

    private func requestDeleteSelectedItems() {
        let selectedItems = items.filter { selectedItemIDs.contains($0.id) && $0.referenceCount == 0 }
        guard selectedItems.isEmpty == false else { return }

        deletionRequest = MediaAssetDeletionRequest(items: selectedItems)
    }

    private func showReferencedSelectionMessage() {
        errorMessage = AppLocalization.string("有引用的素材不能选择。")
    }

    private func deletionTitle(for request: MediaAssetDeletionRequest) -> String {
        request.items.count == 1
            ? AppLocalization.string("删除素材？")
            : AppLocalization.format("删除 %d 个素材？", request.items.count)
    }

    private func deletionDetail(for request: MediaAssetDeletionRequest) -> String {
        request.items.count == 1
            ? AppLocalization.string("删除后无法从素材库重新选择这张图片。")
            : AppLocalization.string("删除后无法从素材库重新选择这些图片。")
    }

    private func deletedMessage(for count: Int) -> String {
        count == 1
            ? AppLocalization.string("素材已删除。")
            : AppLocalization.format("已删除 %d 个素材。", count)
    }
}

private struct MediaAssetPreviewRequest: Identifiable {
    let initialID: String
    let items: [PhotoPreviewItem]

    var id: String { initialID }
}

private struct MediaAssetDeletionRequest: Identifiable {
    let items: [MediaAssetLibraryItem]

    var id: String {
        items.map(\.id).sorted().joined(separator: "-")
    }
}

private enum PhotoPickerPurpose: Identifiable {
    case add
    case replace(MediaAsset)

    var id: String {
        switch self {
        case .add:
            "add"
        case .replace(let asset):
            "replace-\(asset.id)"
        }
    }
}

private struct MediaAssetManagementTile: View {
    let item: MediaAssetLibraryItem
    let repository: MediaAssetRepository
    let isSelectionMode: Bool
    let isSelected: Bool
    let isSelectable: Bool
    let onPreview: () -> Void
    let onToggleSelection: () -> Void
    let onUnavailableSelection: () -> Void
    let onEdit: () -> Void
    let onReplace: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topTrailing) {
                Button(action: primaryAction) {
                    MediaAssetManagementThumbnail(asset: item.asset, repository: repository)
                        .overlay {
                            if isSelectionMode, isSelectable == false {
                                RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                                    .fill(Color.black.opacity(0.28))
                            }
                        }
                        .overlay(alignment: .topLeading) {
                            if isSelectionMode {
                                selectionBadge
                                    .padding(6)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(accessibilityLabel)

                if isSelectionMode == false {
                    Menu {
                        Button(AppLocalization.string("编辑"), systemImage: "slider.horizontal.3", action: onEdit)
                        Button(AppLocalization.string("替换"), systemImage: "arrow.triangle.2.circlepath", action: onReplace)
                        if item.referenceCount == 0 {
                            Button(AppLocalization.string("删除"), systemImage: "trash", role: .destructive, action: onDelete)
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .frame(width: 32, height: 32)
                            .background(.regularMaterial, in: Circle())
                    }
                    .padding(6)
                }
            }

            Text(referenceText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private var referenceText: String {
        item.referenceCount == 0
            ? AppLocalization.string("未引用")
            : AppLocalization.format("%d 个引用", item.referenceCount)
    }

    private var accessibilityLabel: String {
        if isSelectionMode, isSelectable == false {
            return AppLocalization.string("有引用的素材不能选择。")
        }

        if isSelectionMode {
            return isSelected
                ? AppLocalization.string("取消选择素材")
                : AppLocalization.string("选择素材")
        }

        return AppLocalization.string("预览图片")
    }

    private var selectionBadge: some View {
        Image(systemName: selectionBadgeSystemImage)
            .font(.headline)
            .foregroundStyle(selectionBadgeForegroundStyle)
            .frame(width: 26, height: 26)
            .background(.regularMaterial, in: Circle())
    }

    private var selectionBadgeSystemImage: String {
        if isSelectable == false {
            return "lock.fill"
        }

        return isSelected ? "checkmark.circle.fill" : "circle"
    }

    private var selectionBadgeForegroundStyle: Color {
        if isSelectable == false {
            return .secondary
        }

        return isSelected ? .accentColor : .secondary
    }

    private func primaryAction() {
        if isSelectionMode {
            if isSelectable {
                onToggleSelection()
            } else {
                onUnavailableSelection()
            }
        } else {
            onPreview()
        }
    }
}

private struct MediaAssetManagementThumbnail: View {
    let asset: MediaAsset
    let repository: MediaAssetRepository

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(.secondarySystemFill)

                if let data = repository.data(for: asset),
                   let image = UIImage(data: data) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                } else {
                    Image(systemName: "photo")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: AppDesign.iconCornerRadius))
    }
}
