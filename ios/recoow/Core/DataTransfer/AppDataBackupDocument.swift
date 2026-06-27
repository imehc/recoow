import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let recoowBackup = UTType(exportedAs: "imehc.recoow.backup", conformingTo: .data)
    static let recoowBackupFilenameExtension = UTType(filenameExtension: "recoowbackup") ?? .recoowBackup
}

struct AppDataBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.recoowBackup, .recoowBackupFilenameExtension] }
    static var writableContentTypes: [UTType] { [.recoowBackup] }

    private let data: Data

    init(fileURL: URL) throws {
        data = try Data(contentsOf: fileURL)
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw AppDataTransferError.unreadableBackup
        }

        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
