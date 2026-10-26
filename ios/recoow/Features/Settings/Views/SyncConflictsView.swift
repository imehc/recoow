import SwiftUI

struct SyncConflictsView: View {
    @Environment(AppContainer.self) private var container
    @State private var conflicts: [SyncConflict] = []
    @State private var selection: SyncConflict?
    @State private var errorMessage: String?

    private var language: AppLanguagePreference { container.appPreferences.language }

    var body: some View {
        List {
            Section {
                ForEach(conflicts) { conflict in
                    Button { selection = conflict } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(conflict.table)
                            Text(conflict.entityID).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if conflicts.isEmpty {
                    Text(AppLocalization.string("没有待处理冲突", language: language))
                }
            } footer: {
                Text(AppLocalization.string("冲突不会自动覆盖本地数据。确认后，仅处理所选记录；两端原始快照继续保留。", language: language))
            }
            if let errorMessage { Text(errorMessage).foregroundStyle(.red) }
        }
        .appGroupedListStyle()
        .navigationTitle(AppLocalization.string("同步冲突", language: language))
        .appNavigationStyle()
        .task { reload() }
        .appSheet(item: $selection) { conflict in
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(AppLocalization.string("本地修改", language: language)).font(.headline)
                        Text(conflict.localSnapshot).font(.caption.monospaced()).textSelection(.enabled)
                        Text(AppLocalization.string("云端状态", language: language)).font(.headline)
                        Text(conflict.remoteSnapshot).font(.caption.monospaced()).textSelection(.enabled)
                        Button(AppLocalization.string("保留本地并重新提交", language: language)) { resolve(conflict, keepLocal: true) }
                        Button(AppLocalization.string("使用云端状态", language: language), role: .destructive) { resolve(conflict, keepLocal: false) }
                    }.padding()
                }
                .appPageStyle()
                .navigationTitle(AppLocalization.string("确认冲突", language: language))
                .appNavigationStyle()
                .toolbar { Button(AppLocalization.string("取消", language: language)) { selection = nil } }
            }
        }
    }

    private func reload() {
        guard let scope = container.cloudSyncConfig.activeScope else { return }
        do { conflicts = try FullSyncRepository(database: container.database).conflicts(scope: scope) }
        catch { errorMessage = error.localizedDescription }
    }

    private func resolve(_ conflict: SyncConflict, keepLocal: Bool) {
        guard let scope = container.cloudSyncConfig.activeScope else { return }
        do {
            try FullSyncRepository(database: container.database).resolveConflict(scope: scope, conflict: conflict, keepLocal: keepLocal)
            selection = nil
            reload()
            Task { await container.syncEngine.enqueueScan() }
        } catch { errorMessage = error.localizedDescription }
    }
}
