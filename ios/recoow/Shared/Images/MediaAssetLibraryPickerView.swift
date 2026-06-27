import SwiftUI
import UIKit

struct MediaAssetLibraryPickerView: View {
    let repository: MediaAssetRepository
    let onSelect: (MediaAsset) -> Void

    @State private var assets: [MediaAsset] = []
    @State private var errorMessage: String?

    private let columns = [
        GridItem(.adaptive(minimum: 96), spacing: 10)
    ]

    var body: some View {
        Group {
            if assets.isEmpty, errorMessage == nil {
                ContentUnavailableView(
                    AppLocalization.string("暂无素材"),
                    systemImage: "rectangle.stack",
                    description: Text(AppLocalization.string("从相册或拍照添加后，会出现在这里"))
                )
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(assets) { asset in
                            Button {
                                onSelect(asset)
                            } label: {
                                MediaAssetThumbnailView(asset: asset, repository: repository)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(AppLocalization.string("选择素材"))
                        }
                    }
                    .padding(12)
                }
            }
        }
        .navigationTitle(AppLocalization.string("素材"))
        .navigationBarTitleDisplayMode(.inline)
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
