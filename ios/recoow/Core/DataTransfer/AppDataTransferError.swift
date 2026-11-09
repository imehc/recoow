import Foundation

enum AppDataTransferError: LocalizedError, Sendable {
    case accountMismatch
    case unverifiedAccount
    case localConflict(Int)
    case missingMetadata
    case unsupportedFormatVersion(Int)
    case newerDatabaseSchema(backupVersion: Int, currentVersion: Int)
    case unreadableBackup
    case missingMediaAssets(Int)
    case rollbackFailed(importError: String, rollbackError: String)

    var errorDescription: String? {
        switch self {
        case .accountMismatch:
            AppLocalization.string("备份属于其他账号，无法导入当前账号。请先备份并重置本机数据，再恢复对应账号。")
        case .unverifiedAccount:
            AppLocalization.string("此备份未记录账号，请确认将数据归入当前账号后再导入。")
        case .localConflict(let count):
            AppLocalization.format("发现 %d 条同 ID 不同内容的记录，增量导入已取消，本机数据未改动。如需恢复备份版本，请先导出当前数据，再选择覆盖全部。", count)
        case .missingMetadata:
            AppLocalization.string("备份文件缺少版本信息，无法安全导入。")
        case .unsupportedFormatVersion(let version):
            AppLocalization.format("备份格式版本 %d 暂不支持，请升级 App 后再试。", version)
        case .newerDatabaseSchema(let backupVersion, let currentVersion):
            AppLocalization.format(
                "备份数据版本 %d 高于当前 App 支持的版本 %d，请升级 App 后再导入。",
                backupVersion,
                currentVersion
            )
        case .unreadableBackup:
            AppLocalization.string("无法读取该备份文件，请确认文件完整。")
        case .missingMediaAssets(let count):
            AppLocalization.format("备份缺少 %d 个媒体素材文件，无法生成完整备份。", count)
        case .rollbackFailed(let importError, let rollbackError):
            AppLocalization.format(
                "导入失败，且自动恢复当前数据失败。导入错误：%@；恢复错误：%@",
                importError,
                rollbackError
            )
        }
    }
}
