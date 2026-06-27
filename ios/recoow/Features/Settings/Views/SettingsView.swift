import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var isConfirmingDeletedDataCleanup = false
    @State private var isClearingDeletedData = false
    @State private var cleanupResult: SoftDeletedDataCleanupResult?
    @State private var cleanupErrorMessage: String?
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

    var body: some View {
        let language = container.appPreferences.language

        Form {
            Section {
                NavigationLink {
                    displaySettingsPage
                } label: {
                    SettingsCategoryRow(
                        titleKey: "显示与语言",
                        subtitleKey: "语言、外观和显示偏好",
                        systemImage: "textformat.size",
                        tint: .blue
                    )
                }

                NavigationLink {
                    homeToolsSettingsPage
                } label: {
                    SettingsCategoryRow(
                        titleKey: "主页功能入口",
                        subtitleKey: "管理首页显示的工具入口",
                        systemImage: "square.grid.2x2",
                        tint: .green
                    )
                }

                NavigationLink {
                    dataSettingsPage
                } label: {
                    SettingsCategoryRow(
                        titleKey: "数据与备份",
                        subtitleKey: "备份、导入和清理本地数据",
                        systemImage: "externaldrive",
                        tint: .orange
                    )
                }
            } header: {
                Text(AppLocalization.string("设置分类"))
            }
        }
        .hidesTabBarWhenScrollingDown()
        .navigationTitle(AppLocalization.string("设置", language: language))
        .alert(AppLocalization.string("永久删除已删除的数据？"), isPresented: $isConfirmingDeletedDataCleanup) {
            Button(AppLocalization.string("清除"), role: .destructive, action: clearDeletedData)
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("清除已删除数据确认说明"))
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
    }

    private var displaySettingsPage: some View {
        Form {
            AppPreferencesSection(preferences: container.appPreferences)
        }
        .navigationTitle(AppLocalization.string("显示与语言"))
        .navigationBarTitleDisplayMode(.inline)
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
                Text(AppLocalization.string("管理首页显示的工具入口"))
            }
        }
        .navigationTitle(AppLocalization.string("主页功能入口"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var dataSettingsPage: some View {
        Form {
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
                .disabled(isClearingDeletedData)
            } header: {
                Text(AppLocalization.string("数据维护"))
            } footer: {
                Text(AppLocalization.string("清除已删除数据说明"))
            }
        }
        .navigationTitle(AppLocalization.string("数据与备份"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func clearDeletedData() {
        let database = container.database
        isClearingDeletedData = true

        Task {
            do {
                let result = try await Task.detached {
                    try database.clearSoftDeletedRecords()
                }.value
                cleanupResult = result
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
            hiddenToolRouteIDs: container.featureVisibilitySettings.transferSnapshotHiddenRouteIDs
        )
    }

    private func applyImportedPreferences(_ preferences: AppDataTransferPreferences) {
        container.appPreferences.applyImportedSnapshot(
            languageRawValue: preferences.languageRawValue,
            appearanceRawValue: preferences.appearanceRawValue
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
}

private struct SettingsCategoryRow: View {
    let titleKey: String
    let subtitleKey: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(AppLocalization.string(titleKey))

                Text(AppLocalization.string(subtitleKey))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
        }
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
