import Foundation

struct PhotoPreviewItem: Identifiable {
    let id: String
    let imageData: Data
    let title: String?
    let assetID: String?
    let assetTitleFallback: String?

    init(id: String = UUID().uuidString, imageData: Data, title: String? = nil, assetID: String? = nil, assetTitleFallback: String? = nil) {
        self.id = id
        self.imageData = imageData
        self.title = title
        self.assetID = assetID
        self.assetTitleFallback = assetTitleFallback
    }
}
