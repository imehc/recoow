import Foundation

enum AppPreferenceStorageKeys {
    nonisolated static let language = "preferences.language"
    nonisolated static let appearance = "preferences.appearance"
    nonisolated static let addsPickedPhotosToMediaLibrary = "preferences.addsPickedPhotosToMediaLibrary"
    nonisolated static let savesCameraPhotosToLibrary = "preferences.savesCameraPhotosToLibrary"
    nonisolated static let lastDataExportedAt = "dataTransfer.lastExportedAt"
    nonisolated static let lastDataExportSchemaVersion = "dataTransfer.lastExportSchemaVersion"
    nonisolated static let lastDataImportedAt = "dataTransfer.lastImportedAt"
    nonisolated static let lastDataImportSchemaVersion = "dataTransfer.lastImportSchemaVersion"
}
