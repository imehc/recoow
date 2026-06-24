import Foundation

struct PhotoPreviewItem: Identifiable {
    let id: String
    let imageData: Data
    let title: String?

    init(id: String = UUID().uuidString, imageData: Data, title: String? = nil) {
        self.id = id
        self.imageData = imageData
        self.title = title
    }
}
