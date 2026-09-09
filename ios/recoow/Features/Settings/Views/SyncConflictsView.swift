import SwiftUI

struct SyncConflictsView: View {
    @Environment(AppContainer.self) private var container
    @State private var conflicts: [SyncConflict] = []
    @State private var selection: SyncConflict?
    @State private var errorMessage: String?

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
                if conflicts.isEmpty { Text("没有待处理冲突") }
            } footer: {
                Text("冲突不会自动覆盖本地数据。确认后，仅处理所选记录；两端原始快照继续保留。")
            }
            if let errorMessage { Text(errorMessage).foregroundStyle(.red) }
        }
        .appGroupedListStyle()
        .navigationTitle("同步冲突")
        .appNavigationStyle()
        .task { reload() }
        .appSheet(item: $selection) { conflict in
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("本地修改").font(.headline)
                        Text(conflict.localSnapshot).font(.caption.monospaced()).textSelection(.enabled)
                        Text("云端状态").font(.headline)
                        Text(conflict.remoteSnapshot).font(.caption.monospaced()).textSelection(.enabled)
                        Button("保留本地并重新提交") { resolve(conflict, keepLocal: true) }
                        Button("使用云端状态", role: .destructive) { resolve(conflict, keepLocal: false) }
                    }.padding()
                }
                .appPageStyle()
                .navigationTitle("确认冲突")
                .appNavigationStyle()
                .toolbar { Button("取消") { selection = nil } }
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
