import Foundation

struct ImageReference: Hashable, Sendable {
    var data: Data?
    var assetID: String?

    var hasImage: Bool {
        assetID != nil || data != nil
    }

    var resolvedData: Data? {
        if let assetID,
           let assetData = MediaAssetObjectStore.shared.data(forAssetID: assetID) {
            return assetData
        }

        return data
    }

    var independentData: Data? {
        assetID == nil ? data : nil
    }
}

protocol ImageReferenceProviding {
    var imageReference: ImageReference { get }
}

protocol StandardImageReferenceProviding: ImageReferenceProviding {
    var imageData: Data? { get }
    var imageAssetID: String? { get }
}

extension StandardImageReferenceProviding {
    var imageReference: ImageReference {
        ImageReference(data: imageData, assetID: imageAssetID)
    }
}

extension ImageReferenceProviding {
    var hasImage: Bool {
        imageReference.hasImage
    }

    var resolvedImageData: Data? {
        imageReference.resolvedData
    }
}

protocol MutableStandardImageReferenceProviding: StandardImageReferenceProviding {
    var imageData: Data? { get set }
    var imageAssetID: String? { get set }
}

extension MutableStandardImageReferenceProviding {
    mutating func setImageReference(_ reference: ImageReference) {
        imageData = reference.independentData
        imageAssetID = reference.assetID
    }

    mutating func setIndependentImageData(_ data: Data?) {
        imageData = data
        imageAssetID = nil
    }

    mutating func setImageAssetID(_ assetID: String?) {
        imageData = nil
        imageAssetID = assetID
    }

    mutating func clearImageReference() {
        imageData = nil
        imageAssetID = nil
    }
}
