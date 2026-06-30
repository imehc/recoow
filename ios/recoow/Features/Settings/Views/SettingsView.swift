import SwiftUI
import UniformTypeIdentifiers

enum SettingsNavigationRoute: Hashable {
    case display
    case homeTools
    case data
    case mediaLibrary
}

struct SettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var isConfirmingDeletedDataCleanup = false
    @State private var isClearingDeletedData = false
    @State private var cleanupResult: SoftDeletedDataCleanupResult?
    @State private var cleanupErrorMessage: String?
    @State private var storageUsage: AppStorageUsageSnapshot?
    @State private var isLoadingStorageUsage = false
    @State private var isOptimizingStorage = false
    @State private var storageOptimizationResult: StorageOptimizationResult?
    @State private var storageOptimizationErrorMessage: String?
    @State private var storageUsageRefreshGeneration = 0
    @State private var isConfirmingAllDataReset = false
    @State private var isResettingAllData = false
    @State private var allDataResetErrorMessage: String?
    @State private var isExportingData = false
    @State private var exportDocument: AppDataBackupDocument?
    @State private var exportFileName = "recoow-backup"
    @State private var isShowingBackupExporter = false
    @State private var isShowingBackupImporter = false
    @State private var importedBackupURL: URL?
    @State private var importPreview: AppDataImportPreview?
    @State private var importMode: AppDataImportMode = .mergeMissing
    @State private var selectedImportScopes = Set(AppDataImportScope.allCases)
    @State private var isImportingData = false
    @State private var transferSuccessMessage: String?
    @State private var transferErrorMessage: String?
    @AppStorage(AppPreferenceStorageKeys.lastDataExportedAt) private var lastDataExportedAt: Double = 0
    @AppStorage(AppPreferenceStorageKeys.lastDataExportSchemaVersion) private var lastDataExportSchemaVersion: Int = 0
    @AppStorage(AppPreferenceStorageKeys.lastDataImportedAt) private var lastDataImportedAt: Double = 0
    @AppStorage(AppPreferenceStorageKeys.lastDataImportSchemaVersion) private var lastDataImportSchemaVersion: Int = 0
    @Binding private var tabBarVisibility: Visibility
    private let resetAllLocalData: () async throws -> Void

    init(
        tabBarVisibility: Binding<Visibility> = .constant(.visible),
        resetAllLocalData: @escaping () async throws -> Void = { }
    ) {
        _tabBarVisibility = tabBarVisibility
        self.resetAllLocalData = resetAllLocalData
    }

    var body: some View {
        let language = container.appPreferences.language

        Form {
            Section {
                NavigationLink(value: SettingsNavigationRoute.display) {
                    SettingsCategoryRow(
                        titleKey: "显示与语言",
                        subtitleKey: "语言、外观和显示偏好",
                        systemImage: "textformat.size",
                        tint: .blue,
                        language: language
                    )
                }

                NavigationLink(value: SettingsNavigationRoute.homeTools) {
                    SettingsCategoryRow(
                        titleKey: "主页功能入口",
                        subtitleKey: "管理首页显示的工具入口",
                        systemImage: "square.grid.2x2",
                        tint: .green,
                        language: language
                    )
                }

                NavigationLink(value: SettingsNavigationRoute.data) {
                    SettingsCategoryRow(
                        titleKey: "数据与备份",
                        subtitleKey: "备份、导入和清理本地数据",
                        systemImage: "externaldrive",
                        tint: .orange,
                        language: language
                    )
                }
            } header: {
                Text(AppLocalization.string("设置分类", language: language))
            }
        }
        .reportsTabBarVisibilityWhenScrolling($tabBarVisibility)
        .navigationTitle(AppLocalization.string("设置", language: language))
        .navigationDestination(for: SettingsNavigationRoute.self) { route in
            switch route {
            case .display:
                displaySettingsPage
            case .homeTools:
                homeToolsSettingsPage
            case .data:
                dataSettingsPage
            case .mediaLibrary:
                MediaAssetLibraryManagementView(repository: container.mediaAssetRepository)
            }
        }
    }

    private var displaySettingsPage: some View {
        Form {
            AppPreferencesSection(preferences: container.appPreferences)
        }
        .navigationTitle(AppLocalization.string("显示与语言", language: container.appPreferences.language))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }

    private var homeToolsSettingsPage: some View {
        Form {
            Section {
                ForEach(ToolRegistry.modules) { module in
                    FeatureVisibilityToggleRow(
                        route: module.route,
                        settings: container.featureVisibilitySettings
                    )
                }
            } footer: {
                Text(AppLocalization.string("管理首页显示的工具入口", language: container.appPreferences.language))
            }
        }
        .navigationTitle(AppLocalization.string("主页功能入口", language: container.appPreferences.language))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }

    private var dataSettingsPage: some View {
        Form {
            Section {
                NavigationLink(value: SettingsNavigationRoute.mediaLibrary) {
                    Label(AppLocalization.string("媒体素材库"), systemImage: "photo.stack")
                }
            } header: {
                Text(AppLocalization.string("媒体素材"))
            }

            MediaLibraryPreferencesSection(preferences: container.appPreferences)

            Section {
                Button(action: exportData) {
                    HStack {
                        Label(AppLocalization.string("导出全部数据"), systemImage: "square.and.arrow.up")

                        Spacer()

                        if isExportingData {
                            ProgressView()
                        }
                    }
                }
                .disabled(isExportingData || isImportingData)

                Button {
                    isShowingBackupImporter = true
                } label: {
                    HStack {
                        Label(AppLocalization.string("从备份导入"), systemImage: "square.and.arrow.down")

                        Spacer()

                        if isImportingData {
                            ProgressView()
                        }
                    }
                }
                .disabled(isExportingData || isImportingData)
            } header: {
                Text(AppLocalization.string("数据备份"))
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(AppLocalization.string("备份会包含所有本地数据、媒体附件和应用偏好。导入时可以选择增量导入或覆盖全部，导入前会自动备份当前数据。"))
                    Text(lastExportStatusText)
                    Text(lastImportStatusText)
                }
            }

            Section {
                StorageUsageSummaryRows(
                    usage: storageUsage,
                    isLoading: isLoadingStorageUsage
                )

                Button(action: optimizeStorage) {
                    HStack {
                        Label(AppLocalization.string("压缩存储空间"), systemImage: "arrow.down.doc")

                        Spacer()

                        if isOptimizingStorage {
                            ProgressView()
                        }
                    }
                }
                .disabled(isOptimizingStorage || isClearingDeletedData || isResettingAllData)

                Button(role: .destructive) {
                    isConfirmingDeletedDataCleanup = true
                } label: {
                    HStack {
                        Label(AppLocalization.string("清除已删除数据"), systemImage: "trash")
                            .foregroundStyle(.red)

                        Spacer()

                        if isClearingDeletedData {
                            ProgressView()
                        }
                    }
                }
                .disabled(isClearingDeletedData || isOptimizingStorage || isResettingAllData)

                Button(role: .destructive) {
                    isConfirmingAllDataReset = true
                } label: {
                    HStack {
                        Label(AppLocalization.string("清除所有数据"), systemImage: "trash.slash")
                            .foregroundStyle(.red)

                        Spacer()

                        if isResettingAllData {
                            ProgressView()
                        }
                    }
                }
                .disabled(isResettingAllData || isClearingDeletedData || isOptimizingStorage)
            } header: {
                Text(AppLocalization.string("数据维护"))
            } footer: {
                Text(AppLocalization.string("存储优化说明"))
            }
        }
        .navigationTitle(AppLocalization.string("数据与备份", language: container.appPreferences.language))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .alert(AppLocalization.string("永久删除已删除的数据？"), isPresented: $isConfirmingDeletedDataCleanup) {
            Button(AppLocalization.string("清除"), role: .destructive, action: clearDeletedData)
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("清除已删除数据确认说明"))
        }
        .alert(AppLocalization.string("清除所有数据？"), isPresented: $isConfirmingAllDataReset) {
            Button(AppLocalization.string("清除"), role: .destructive, action: resetAllData)
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("清除所有数据确认说明"))
        }
        .alert(
            AppLocalization.string("清除完成"),
            isPresented: .isPresent($cleanupResult),
            presenting: cleanupResult
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { result in
            Text(cleanupMessage(for: result))
        }
        .alert(
            AppLocalization.string("清除失败"),
            isPresented: .isPresent($cleanupErrorMessage),
            presenting: cleanupErrorMessage
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { message in
            Text(message)
        }
        .alert(
            AppLocalization.string("清除所有数据失败"),
            isPresented: .isPresent($allDataResetErrorMessage),
            presenting: allDataResetErrorMessage
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { message in
            Text(message)
        }
        .alert(
            AppLocalization.string("存储优化完成"),
            isPresented: .isPresent($storageOptimizationResult),
            presenting: storageOptimizationResult
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { result in
            Text(storageOptimizationMessage(for: result))
        }
        .alert(
            AppLocalization.string("存储优化失败"),
            isPresented: .isPresent($storageOptimizationErrorMessage),
            presenting: storageOptimizationErrorMessage
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { message in
            Text(message)
        }
        .alert(
            AppLocalization.string("完成"),
            isPresented: .isPresent($transferSuccessMessage),
            presenting: transferSuccessMessage
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { message in
            Text(message)
        }
        .alert(
            AppLocalization.string("数据导入导出失败"),
            isPresented: .isPresent($transferErrorMessage),
            presenting: transferErrorMessage
        ) { _ in
            Button(AppLocalization.string("确定"), role: .cancel) { }
        } message: { message in
            Text(message)
        }
        .sheet(isPresented: importPreviewPresence) {
            if let importPreview {
                AppDataImportOptionsSheet(
                    preview: importPreview,
                    mode: $importMode,
                    selectedScopes: $selectedImportScopes,
                    isImporting: isImportingData,
                    onCancel: clearPendingImport,
                    onImport: importData
                )
            }
        }
        .fileExporter(
            isPresented: $isShowingBackupExporter,
            document: exportDocument,
            contentType: .recoowBackup,
            defaultFilename: exportFileName
        ) { result in
            switch result {
            case .success:
                recordLastExport()
                transferSuccessMessage = AppLocalization.string("备份文件已导出。")
            case .failure(let error):
                transferErrorMessage = error.localizedDescription
            }

            exportDocument = nil
        }
        .fileImporter(
            isPresented: $isShowingBackupImporter,
            allowedContentTypes: [.recoowBackup, .recoowBackupFilenameExtension, .database],
            allowsMultipleSelection: false
        ) { result in
            handleImportSelection(result)
        }
        .task {
            await refreshStorageUsageIfNeeded()
        }
    }

    private func clearDeletedData() {
        let database = container.database
        isClearingDeletedData = true

        Task {
            do {
                let result = try await Task.detached {
                    let cleanupResult = try database.clearSoftDeletedRecords()
                    let storageUsage = try database.storageUsageSnapshot()
                    return (cleanupResult, storageUsage)
                }.value
                cleanupResult = result.0
                storageUsage = result.1
            } catch {
                cleanupErrorMessage = error.localizedDescription
            }

            isClearingDeletedData = false
        }
    }

    private func cleanupMessage(for result: SoftDeletedDataCleanupResult) -> String {
        if result.deletedRowCount == 0 {
            return AppLocalization.string("没有可清除的已删除数据。")
        }

        if result.retainedRowCount > 0 {
            return AppLocalization.format(
                "已清除 %d 条已删除数据，%d 条仍被正常数据引用并已保留。",
                result.deletedRowCount,
                result.retainedRowCount
            )
        }

        return AppLocalization.format("已清除 %d 条已删除数据。", result.deletedRowCount)
    }

    private func optimizeStorage() {
        let database = container.database
        isOptimizingStorage = true

        Task {
            do {
                let result = try await Task.detached {
                    try database.optimizeStorage()
                }.value
                storageOptimizationResult = result
                storageUsage = result.afterUsage
            } catch {
                storageOptimizationErrorMessage = error.localizedDescription
            }

            isOptimizingStorage = false
        }
    }

    private func resetAllData() {
        isResettingAllData = true

        Task {
            do {
                try await resetAllLocalData()
            } catch {
                allDataResetErrorMessage = error.localizedDescription
            }

            isResettingAllData = false
        }
    }

    private func storageOptimizationMessage(for result: StorageOptimizationResult) -> String {
        AppLocalization.format(
            "已释放 %@。压缩了 %d 条同步日志，清理了 %d 个媒体文件，移除了 %d 个旧回滚备份。",
            storageSizeText(result.reclaimedBytes),
            result.sanitizedChangeLogRowCount,
            result.prunedMediaObjectCount,
            result.removedRollbackBackupCount
        )
    }

    private func refreshStorageUsageIfNeeded() async {
        guard storageUsage == nil, isLoadingStorageUsage == false else { return }
        await refreshStorageUsage()
    }

    private func refreshStorageUsage() async {
        let database = container.database
        storageUsageRefreshGeneration += 1
        let generation = storageUsageRefreshGeneration
        storageUsage = nil
        isLoadingStorageUsage = true

        do {
            let snapshot = try await Task.detached(priority: .utility) {
                try database.storageUsageSnapshot()
            }.value
            guard generation == storageUsageRefreshGeneration else { return }
            storageUsage = snapshot
        } catch {
            guard generation == storageUsageRefreshGeneration else { return }
            storageOptimizationErrorMessage = error.localizedDescription
        }

        guard generation == storageUsageRefreshGeneration else { return }
        isLoadingStorageUsage = false
    }

    private func exportData() {
        let service = container.dataTransferService
        let sourceDeviceID = container.deviceIdentifier.value
        let preferences = transferPreferences()
        isExportingData = true

        Task {
            do {
                let backupURL = try await Task.detached {
                    try service.exportBackup(sourceDeviceID: sourceDeviceID, preferences: preferences)
                }.value
                exportDocument = try AppDataBackupDocument(fileURL: backupURL)
                exportFileName = defaultExportFileName()
                isShowingBackupExporter = true
                try? FileManager.default.removeItem(at: backupURL)
                await refreshStorageUsage()
            } catch {
                transferErrorMessage = error.localizedDescription
            }

            isExportingData = false
        }
    }

    private func handleImportSelection(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let service = container.dataTransferService
            importedBackupURL = url
            isImportingData = true

            Task {
                do {
                    importPreview = try await Task.detached {
                        try service.previewImport(from: url)
                    }.value
                    importMode = .mergeMissing
                    selectedImportScopes = Set(AppDataImportScope.allCases)
                } catch {
                    importedBackupURL = nil
                    transferErrorMessage = error.localizedDescription
                }

                isImportingData = false
            }
        } catch {
            transferErrorMessage = error.localizedDescription
        }
    }

    private func importData() {
        guard let importedBackupURL else { return }

        let service = container.dataTransferService
        let mode = importMode
        let scopes = selectedImportScopes
        isImportingData = true

        Task {
            do {
                let result = try await Task.detached {
                    try service.importBackup(from: importedBackupURL, mode: mode, scopes: scopes)
                }.value
                if mode == .replaceAll {
                    applyImportedPreferences(result.metadata.preferences)
                }
                recordLastImport(schemaVersion: result.metadata.databaseSchemaVersion)
                transferSuccessMessage = successMessage(for: result, mode: mode)
                await refreshStorageUsage()
            } catch {
                transferErrorMessage = error.localizedDescription
            }

            clearPendingImport()
            isImportingData = false
        }
    }

    private func transferPreferences() -> AppDataTransferPreferences {
        let appPreferences = container.appPreferences.transferSnapshot
        return AppDataTransferPreferences(
            languageRawValue: appPreferences.languageRawValue,
            appearanceRawValue: appPreferences.appearanceRawValue,
            addsPickedPhotosToMediaLibrary: appPreferences.addsPickedPhotosToMediaLibrary,
            savesCameraPhotosToLibrary: appPreferences.savesCameraPhotosToLibrary,
            hiddenToolRouteIDs: container.featureVisibilitySettings.transferSnapshotHiddenRouteIDs
        )
    }

    private func applyImportedPreferences(_ preferences: AppDataTransferPreferences) {
        container.appPreferences.applyImportedSnapshot(
            languageRawValue: preferences.languageRawValue,
            appearanceRawValue: preferences.appearanceRawValue,
            addsPickedPhotosToMediaLibrary: preferences.addsPickedPhotosToMediaLibrary,
            savesCameraPhotosToLibrary: preferences.savesCameraPhotosToLibrary
        )
        container.featureVisibilitySettings.replaceHiddenRoutes(preferences.hiddenToolRouteIDs)
    }

    private var importPreviewPresence: Binding<Bool> {
        Binding {
            importPreview != nil
        } set: { isPresented in
            if isPresented == false, isImportingData == false {
                clearPendingImport()
            }
        }
    }

    private func clearPendingImport() {
        importedBackupURL = nil
        importPreview = nil
    }

    private func successMessage(for result: AppDataImportResult, mode: AppDataImportMode) -> String {
        let appVersion = "\(result.metadata.appVersion) (\(result.metadata.appBuild))"
        switch mode {
        case .mergeMissing:
            return AppLocalization.format(
                "已从 App %@ 的备份增量导入 %d 条缺失数据。导入前的数据已保存在回滚备份中。",
                appVersion,
                result.importedRowCount
            )
        case .replaceAll:
            return AppLocalization.format(
                "已覆盖导入来自 App %@ 的备份。导入前的数据已保存在回滚备份中。",
                appVersion
            )
        }
    }

    private func defaultExportFileName() -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "recoow-\(formatter.string(from: Date()))"
    }

    private func recordLastExport() {
        lastDataExportedAt = Date().timeIntervalSince1970
        lastDataExportSchemaVersion = AppMigrator.currentSchemaVersion
    }

    private func recordLastImport(schemaVersion: Int) {
        lastDataImportedAt = Date().timeIntervalSince1970
        lastDataImportSchemaVersion = schemaVersion
    }

    private var lastExportStatusText: String {
        transferStatusText(
            timestamp: lastDataExportedAt,
            schemaVersion: lastDataExportSchemaVersion,
            emptyKey: "最近备份：暂无",
            formatKey: "最近备份：%@，Schema v%d"
        )
    }

    private var lastImportStatusText: String {
        transferStatusText(
            timestamp: lastDataImportedAt,
            schemaVersion: lastDataImportSchemaVersion,
            emptyKey: "最近导入：暂无",
            formatKey: "最近导入：%@，Schema v%d"
        )
    }

    private func transferStatusText(
        timestamp: Double,
        schemaVersion: Int,
        emptyKey: String,
        formatKey: String
    ) -> String {
        guard timestamp > 0, schemaVersion > 0 else {
            return AppLocalization.string(emptyKey)
        }

        let date = Date(timeIntervalSince1970: timestamp)
        let dateText = date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .shortened)
                .locale(container.appPreferences.language.locale)
        )
        return AppLocalization.format(formatKey, dateText, schemaVersion)
    }

    private func storageSizeText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct SettingsCategoryRow: View {
    let titleKey: String
    let subtitleKey: String
    let systemImage: String
    let tint: Color
    let language: AppLanguagePreference

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(AppLocalization.string(titleKey, language: language))

                Text(AppLocalization.string(subtitleKey, language: language))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
        }
    }
}

private struct MediaLibraryPreferencesSection: View {
    @Bindable var preferences: AppPreferences

    var body: some View {
        let language = preferences.language

        Section {
            Toggle(
                AppLocalization.string("选择照片后加入素材库", language: language),
                isOn: $preferences.addsPickedPhotosToMediaLibrary
            )

            Toggle(
                AppLocalization.string("拍照后保存到系统图库", language: language),
                isOn: $preferences.savesCameraPhotosToLibrary
            )
        } header: {
            Text(AppLocalization.string("照片处理", language: language))
        } footer: {
            Text(AppLocalization.string("开启后，从相册或相机添加的新图片会加入素材库，并在当前记录中保存为素材引用；关闭时保存为独立图片。保存到系统图库只影响相机拍摄。", language: language))
        }
    }
}

private struct StorageUsageSummaryRows: View {
    let usage: AppStorageUsageSnapshot?
    let isLoading: Bool

    var body: some View {
        if isLoading {
            LabeledContent(AppLocalization.string("当前占用")) {
                HStack(spacing: 8) {
                    Text(AppLocalization.string("计算中..."))
                        .foregroundStyle(.secondary)
                    ProgressView()
                }
            }
        } else if let usage {
            LabeledContent(AppLocalization.string("当前占用"), value: Self.sizeText(usage.totalBytes))
            LabeledContent(AppLocalization.string("数据库"), value: Self.sizeText(usage.databaseBytes))

            if usage.changeLogPayloadBytes > 0 {
                LabeledContent(AppLocalization.string("同步日志载荷"), value: Self.sizeText(usage.changeLogPayloadBytes))
            }

            if usage.mediaAttachmentDataBytes > 0 {
                LabeledContent(AppLocalization.string("附件二进制"), value: Self.sizeText(usage.mediaAttachmentDataBytes))
            }

            if usage.legacyImageDataBytes > 0 {
                LabeledContent(AppLocalization.string("旧图片字段"), value: Self.sizeText(usage.legacyImageDataBytes))
            }

            if usage.mediaObjectBytes > 0 {
                LabeledContent(AppLocalization.string("素材文件"), value: Self.sizeText(usage.mediaObjectBytes))
            }

            if usage.rollbackBackupBytes > 0 {
                LabeledContent(AppLocalization.string("回滚备份"), value: Self.sizeText(usage.rollbackBackupBytes))
            }

            if usage.cacheBytes > 0 {
                LabeledContent(AppLocalization.string("缓存"), value: Self.sizeText(usage.cacheBytes))
            }
        } else {
            LabeledContent(AppLocalization.string("当前占用")) {
                Text("--")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private static func sizeText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct AppDataImportOptionsSheet: View {
    let preview: AppDataImportPreview
    @Binding var mode: AppDataImportMode
    @Binding var selectedScopes: Set<AppDataImportScope>
    let isImporting: Bool
    let onCancel: () -> Void
    let onImport: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent(AppLocalization.string("备份文件"), value: preview.fileName)
                    LabeledContent(AppLocalization.string("来源 App"), value: preview.appVersionDisplay)
                    LabeledContent(AppLocalization.string("数据版本"), value: "\(preview.metadata.databaseSchemaVersion)")
                }

                Section {
                    Picker(AppLocalization.string("导入方式"), selection: $mode) {
                        Text(AppLocalization.string("增量导入")).tag(AppDataImportMode.mergeMissing)
                        Text(AppLocalization.string("覆盖全部")).tag(AppDataImportMode.replaceAll)
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text(importModeFooter)
                }

                if mode == .mergeMissing {
                    Section {
                        ForEach(AppDataImportScope.allCases) { scope in
                            Toggle(scope.title, isOn: binding(for: scope))
                        }
                    } header: {
                        Text(AppLocalization.string("导入范围"))
                    } footer: {
                        Text(AppLocalization.string("增量导入只会插入本机没有的记录，已有记录不会被更新或删除。"))
                    }
                }
            }
            .navigationTitle(AppLocalization.string("导入备份"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalization.string("取消"), action: onCancel)
                        .disabled(isImporting)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalization.string("导入"), action: onImport)
                        .disabled(isImporting || (mode == .mergeMissing && selectedScopes.isEmpty))
                }
            }
        }
    }

    private var importModeFooter: String {
        switch mode {
        case .mergeMissing:
            AppLocalization.string("适合从其他设备补齐缺失数据，可选择只导入某些板块。")
        case .replaceAll:
            AppLocalization.string("会用备份覆盖当前全部数据和应用偏好。导入前会自动生成回滚备份。")
        }
    }

    private func binding(for scope: AppDataImportScope) -> Binding<Bool> {
        Binding {
            selectedScopes.contains(scope)
        } set: { isSelected in
            if isSelected {
                selectedScopes.insert(scope)
            } else {
                selectedScopes.remove(scope)
            }
        }
    }
}
