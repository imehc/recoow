import SwiftUI

struct MediaAssetTitleEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let asset: MediaAsset
    let repository: MediaAssetRepository
    let onSave: () -> Void
    @State private var title: String
    @State private var errorMessage: String?
    @State private var contentHeight = AppDesign.largeThumbnailSize + AppDesign.touchIconSize + AppDesign.pagePadding * 4
    @State private var navigationHeight = AppDesign.touchIconSize

    init(asset: MediaAsset, repository: MediaAssetRepository, onSave: @escaping () -> Void) {
        self.asset = asset
        self.repository = repository
        self.onSave = onSave
        _title = State(initialValue: asset.title ?? "")
    }

    private var normalizedTitle: String? { MediaAsset.normalizedTitle(title) }
    private var titleLength: Int { normalizedTitle?.count ?? 0 }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: AppDesign.gridSpacing) {
                    VStack(alignment: .leading, spacing: AppDesign.gridSpacing) {
                        PhotoThumbnailView(imageData: repository.data(for: asset), systemImage: "photo", size: AppDesign.largeThumbnailSize)
                            .accessibilityHidden(true)
                        Divider()
                        TextField(AppLocalization.string("素材标题"), text: $title, axis: .vertical)
                            .lineLimit(1...4)
                            .accessibilityIdentifier("mediaAssetTitleInput")
                    }
                    .padding(AppDesign.pagePadding)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppPalette.surface, in: .rect(cornerRadius: AppDesign.cornerRadius))

                    if titleLength > MediaAsset.maximumTitleLength {
                        Text(AppLocalization.format("标题最多 %d 个字符", MediaAsset.maximumTitleLength))
                            .font(AppDesign.metadataFont)
                            .foregroundStyle(AppPalette.red)
                    }
                    if let errorMessage {
                        Text(errorMessage)
                            .font(AppDesign.metadataFont)
                            .foregroundStyle(AppPalette.red)
                    }
                }
                .padding(.horizontal, AppDesign.pagePadding)
                .padding(.top, AppDesign.groupedContentTopMargin)
                .padding(.bottom, AppDesign.pagePadding)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            }
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: {
                navigationHeight = $0
            }
            .appPageStyle()
            .navigationTitle(AppLocalization.string("素材标题"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalization.string("取消")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalization.string("保存"), action: save)
                        .disabled(titleLength > MediaAsset.maximumTitleLength)
                }
            }
        }
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.height(contentHeight + navigationHeight)])
    }

    private func save() {
        do {
            try repository.updateImageAssetTitle(id: asset.id, title: normalizedTitle)
            onSave()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
