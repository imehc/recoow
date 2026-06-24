import SwiftUI

struct SettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var isConfirmingDeletedDataCleanup = false
    @State private var isClearingDeletedData = false
    @State private var cleanupResult: SoftDeletedDataCleanupResult?
    @State private var cleanupErrorMessage: String?

    var body: some View {
        let language = container.appPreferences.language

        Form {
            AppPreferencesSection(preferences: container.appPreferences)

            Section {
                ForEach(ToolRegistry.modules) { module in
                    FeatureVisibilityToggleRow(
                        route: module.route,
                        settings: container.featureVisibilitySettings
                    )
                }
            } header: {
                Text(AppLocalization.string("主页功能入口"))
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
        .navigationTitle(AppLocalization.string("设置", language: language))
        .alert(AppLocalization.string("永久删除已删除的数据？"), isPresented: $isConfirmingDeletedDataCleanup) {
            Button(AppLocalization.string("清除"), role: .destructive, action: clearDeletedData)
            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("清除已删除数据确认说明"))
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
}
