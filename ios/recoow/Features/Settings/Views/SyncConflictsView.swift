import SwiftUI

struct SyncConflictsView: View {
    @Environment(AppContainer.self) private var container
    @State private var conflicts: [SyncConflict] = []
    @State private var selection: SyncConflict?
    @State private var errorMessage: String?
    @State private var resolving = false

    private var language: AppLanguagePreference { container.appPreferences.language }
    private var labelLanguage: String {
        language == .english || language == .system && !Locale.current.identifier.hasPrefix("zh") ? "en" : "zh"
    }

    var body: some View {
        List {
            Section {
                ForEach(conflicts) { conflict in
                    Button { selection = conflict } label: {
                        VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
                            Text(recordTitle(conflict)).font(AppDesign.rowTitleFont).foregroundStyle(AppPalette.ink)
                            Text(entityTitle(conflict.table)).font(AppDesign.rowSubtitleFont).foregroundStyle(AppPalette.secondaryInk)
                        }
                    }
                }
                if conflicts.isEmpty { Text(text("没有待处理冲突")) }
            } footer: { Text(text("独立修改自动合并；争议字段、账单结算信息及删除冲突需要确认。")) }
            if let errorMessage { Text(errorMessage).foregroundStyle(AppPalette.red) }
        }
        .appGroupedListStyle()
        .navigationTitle(text("同步冲突"))
        .appNavigationStyle()
        .toolbar { Button(text("刷新")) { reload() } }
        .task { reload() }
        .appSheet(item: $selection) { conflict in
            NavigationStack {
                List {
                    Section {
                        Text(recordTitle(conflict)).font(AppDesign.rowTitleFont)
                        Text(text(deleted(conflict.localSnapshot, envelope: false) ? "本地：已删除" : "本地：保留记录"))
                        Text(text(deleted(conflict.remoteSnapshot, envelope: true) ? "云端：已删除" : "云端：保留记录"))
                    }
                    ForEach(differences(conflict), id: \.field.name) { difference in
                        Section {
                            comparison(text("本地修改"), value: difference.local)
                            comparison(text("云端状态"), value: difference.remote)
                        } header: {
                            Text(difference.field.labels[labelLanguage] ?? difference.field.name).appSectionHeaderStyle()
                        }
                    }
                    if differences(conflict).isEmpty { Section { Text(text("差异涉及附件或记录状态。")) } }
                    Section {
                        Button(text("争议内容使用本地")) { resolve(conflict, keepLocal: true) }
                        Button(text("争议内容使用云端")) { resolve(conflict, keepLocal: false) }
                    } footer: {
                        Text(text("可兼容的修改会保留。账单金额、支付方式与结算状态整组选择；删除冲突选择整条记录。确认后同步生效。"))
                    }
                }
                .disabled(resolving)
                .appGroupedListStyle()
                .navigationTitle(text("确认冲突"))
                .appNavigationStyle()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(text("取消")) { selection = nil } }
                }
            }
        }
    }

    private func text(_ key: String) -> String { AppLocalization.string(key, language: language) }

    private func comparison(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
            Text(title).font(AppDesign.metadataFont).foregroundStyle(AppPalette.secondaryInk)
            Text(value).font(.body).foregroundStyle(AppPalette.ink).textSelection(.enabled)
        }
    }

    private func entityTitle(_ table: String) -> String {
        (try? SyncEntityCatalog.entity(table).labels[labelLanguage]) ?? text("记录")
    }

    private func recordTitle(_ conflict: SyncConflict) -> String {
        let local = (try? FullSyncRepository.object(conflict.localSnapshot)) ?? [:]
        return local["title"] as? String ?? local["name"] as? String ?? entityTitle(conflict.table)
    }

    private func deleted(_ json: String, envelope: Bool) -> Bool {
        let object = (try? FullSyncRepository.object(json)) ?? [:]
        return ((object[envelope ? "deletedAtMs" : "deleted_at"] as? NSNumber)?.int64Value ?? 0) > 0
    }

    private struct Difference {
        let field: SyncField
        let local: String
        let remote: String
    }

    private func differences(_ conflict: SyncConflict) -> [Difference] {
        guard let entity = try? SyncEntityCatalog.entity(conflict.table),
              let local = try? FullSyncRepository.object(conflict.localSnapshot),
              let envelope = try? FullSyncRepository.object(conflict.remoteSnapshot) else { return [] }
        let remote = envelope["snapshot"] as? [String: Any] ?? [:]
        let ignored: Set<String> = ["id", "created_at", "updated_at", "deleted_at", "device_id", "server_version", "stored_value_effects", "storage_key", "checksum"]
        return entity.fields.compactMap { field in
            guard !field.binary, !field.derived, !ignored.contains(field.name),
                  (try? SyncSnapshotRebase.equal(local[field.name], remote[field.name])) == false else { return nil }
            return Difference(field: field, local: display(local[field.name], field: field), remote: display(remote[field.name], field: field))
        }
    }

    private func display(_ value: Any?, field: SyncField) -> String {
        guard let value, !(value is NSNull) else { return "—" }
        if let number = value as? NSNumber {
            if field.format == "amount_cents" {
                let formatter = NumberFormatter()
                formatter.locale = Locale(identifier: language.localeIdentifier ?? Locale.current.identifier)
                formatter.numberStyle = .currency
                formatter.currencyCode = "CNY"
                return formatter.string(from: NSDecimalNumber(decimal: number.decimalValue / 100)) ?? number.stringValue
            }
            if field.format == "ts_ms" {
                let formatter = DateFormatter()
                formatter.locale = Locale(identifier: language.localeIdentifier ?? Locale.current.identifier)
                formatter.dateStyle = .medium
                formatter.timeStyle = .short
                return formatter.string(from: Date(timeIntervalSince1970: number.doubleValue / 1000))
            }
            if field.type == .bool { return text(number.boolValue ? "是" : "否") }
            return number.stringValue
        }
        if let value = value as? String, !value.isEmpty {
            if field.name == "payment_method", let method = BillPaymentMethod(rawValue: value) { return text(method.title) }
            if field.name == "transaction_type", let type = BillType(rawValue: value) { return text(type.title) }
            if field.name == "category", let category = BillCategory(rawValue: value) { return text(category.title) }
            if field.name == "settlement_status" {
                let states = ["active": "正常", "redeemed": "已核销", "refunded": "已退款"]
                if let label = states[value] { return text(label) }
            }
            return value
        }
        return "—"
    }

    private func reload() {
        guard let scope = container.cloudSyncConfig.activeScope else { conflicts = []; return }
        do { conflicts = try FullSyncRepository(database: container.database).conflicts(scope: scope); errorMessage = nil }
        catch { errorMessage = error.localizedDescription }
    }

    private func resolve(_ conflict: SyncConflict, keepLocal: Bool) {
        guard !resolving, let scope = container.cloudSyncConfig.activeScope else { return }
        resolving = true
        Task {
            defer { resolving = false }
            do {
                let capabilities = try await RecoowAPIClient(config: container.cloudSyncConfig).capabilities()
                try capabilities.validate(accountID: container.cloudSyncConfig.accountID ?? "")
                guard capabilities.features.contains("conflict_resolution") else {
                    throw CloudAPIError.syncRejected(text("服务端暂不支持此冲突处理方式，请先升级后端"))
                }
                guard container.cloudSyncConfig.activeScope == scope else {
                    throw CloudAPIError.syncRejected(text("冲突状态已改变，请刷新列表"))
                }
                try FullSyncRepository(database: container.database).resolveConflict(scope: scope, conflict: conflict, keepLocal: keepLocal)
                selection = nil
                reload()
                await container.syncEngine.enqueueScan()
            } catch {
                selection = nil
                reload()
                errorMessage = error.localizedDescription
            }
        }
    }
}
