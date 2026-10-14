import SwiftUI
import UIKit

struct MediaAssetLibraryPickerView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let repository: MediaAssetRepository
    let onSelect: (MediaAsset) -> Void
    let onClose: () -> Void

    @State private var assets: [MediaAsset] = []
    @State private var errorMessage: String?
    @State private var searchText = ""
    @FocusState private var isSearchFieldFocused: Bool

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: dynamicTypeSize.isAccessibilitySize ? 160 : 96), spacing: 10, alignment: .top)]
    }

    private var filteredAssets: [MediaAsset] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return assets }
        return assets.filter { asset in
            let title = MediaAsset.normalizedTitle(asset.title) ?? container.mediaAssetTitle(for: asset.id)
            return title?.localizedCaseInsensitiveContains(query) == true
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            searchField

            Group {
                if assets.isEmpty, errorMessage == nil {
                    ContentUnavailableView(
                        AppLocalization.string("暂无素材"),
                        systemImage: "rectangle.stack",
                        description: Text(AppLocalization.string("从相册或拍照添加后，会出现在这里"))
                    )
                } else if filteredAssets.isEmpty, errorMessage == nil {
                    ContentUnavailableView(
                        AppLocalization.string("没有匹配素材"),
                        systemImage: "magnifyingglass",
                        description: Text(AppLocalization.string("试试其他标题关键词"))
                    )
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(filteredAssets) { asset in
                                Button {
                                    onSelect(asset)
                                } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        MediaAssetThumbnailView(asset: asset, repository: repository)
                                        MediaAssetTitleView(assetID: asset.id, fallback: AppLocalization.string("未命名素材"))
                                    }
                                    .contentShape(.rect)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(AppLocalization.format("选择素材：%@", asset.displayTitle))
                            }
                        }
                        .padding(12)
                    }
                    .scrollDismissesKeyboard(.interactively)
                }
            }
        }
        .appPageStyle()
        .navigationTitle(AppLocalization.string("素材"))
        .appNavigationStyle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                }
                .tint(.primary)
                .accessibilityLabel(AppLocalization.string("关闭"))
            }
        }
        .overlay(alignment: .bottom) {
            if let errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 12)
            }
        }
        .task {
            loadAssets()
        }
    }


    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.body)
                .foregroundStyle(AppPalette.secondaryInk)

            TextField(AppLocalization.string("搜索素材标题"), text: $searchText)
                .textFieldStyle(.plain)
                .focused($isSearchFieldFocused)
                .submitLabel(.search)
                .accessibilityIdentifier("mediaAssetTitleSearch")

            if searchText.isEmpty == false {
                Button {
                    searchText = ""
                    isSearchFieldFocused = true
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.body)
                        .foregroundStyle(AppPalette.secondaryInk)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(AppLocalization.string("清除搜索"))
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 36)
        .background(Color(.secondarySystemFill), in: .rect(cornerRadius: 10))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(AppPalette.background)
    }

    private func loadAssets() {
        do {
            assets = try repository.fetchImageAssets()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct MediaAssetThumbnailView: View {
    let asset: MediaAsset
    let repository: MediaAssetRepository

    @State private var thumbnail: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(.secondarySystemFill)

                if let thumbnail {
                    Image(uiImage: thumbnail)
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
        .task(id: asset.id) {
            thumbnail = await Task.detached(priority: .utility) {
                guard let data = repository.data(for: asset),
                      let image = UIImage(data: data) else { return nil }
                return image.preparingThumbnail(of: CGSize(width: 360, height: 360))
            }.value
        }
    }
}
