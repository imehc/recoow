import SwiftUI

struct SyncConflictsView: View {
    @Environment(AppContainer.self) private var container
    @State private var conflicts: [SyncConflict] = []
    @State private var selection: SyncConflict?
    @State private var errorMessage: String?
    @State private var resolving = false
    @State private var selectedIDs: Set<String> = []
    @State private var batchChoice: Bool?

    private var language: AppLanguagePreference { container.appPreferences.language }
    private var labelLanguage: String {
        language == .english || language == .system && !Locale.current.identifier.hasPrefix("zh") ? "en" : "zh"
    }

    var body: some View {
        List {
            Section {
                ForEach(conflicts) { conflict in
                    HStack {
                        Button {
                            if !selectedIDs.insert(conflict.id).inserted { selectedIDs.remove(conflict.id) }
                        } label: {
                            Image(systemName: selectedIDs.contains(conflict.id) ? "checkmark.circle.fill" : "circle")
                        }
                        .accessibilityLabel(text(selectedIDs.contains(conflict.id) ? "取消选择" : "选择"))
                        Button { selection = conflict } label: {
                            VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
                                Text(recordTitle(conflict)).font(AppDesign.rowTitleFont).foregroundStyle(AppPalette.ink)
                                Text(entityTitle(conflict.table)).font(AppDesign.rowSubtitleFont).foregroundStyle(AppPalette.secondaryInk)
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
                if conflicts.isEmpty { Text(text("没有待处理冲突")) }
            } footer: { Text(text("独立修改自动合并；争议字段、账单结算信息及删除冲突需要确认。")) }
            if !conflicts.isEmpty {
                Section {
                    Button(text(selectedIDs.count == conflicts.count ? "取消全选" : "全选")) {
                        selectedIDs = selectedIDs.count == conflicts.count ? [] : Set(conflicts.map(\.id))
                    }
                    Button(text("选中记录以本地为准")) { batchChoice = true }
                        .disabled(selectedIDs.isEmpty)
                    Button(text("选中记录以云端为准")) { batchChoice = false }
                        .disabled(selectedIDs.isEmpty)
                } footer: { Text(AppLocalization.format("已选择 %d 条", selectedIDs.count)) }
            }
            if let errorMessage { Text(errorMessage).foregroundStyle(AppPalette.red) }
        }
        .appGroupedListStyle()
        .navigationTitle(text("同步冲突"))
        .appNavigationStyle()
        .toolbar { Button(text("刷新")) { reload() } }
        .task { reload() }
        .disabled(resolving)
        .confirmationDialog(text("批量处理同步冲突"), isPresented: Binding(get: { batchChoice != nil }, set: { if !$0 { batchChoice = nil } }), titleVisibility: .visible) {
            if let keepLocal = batchChoice {
                Button(text(keepLocal ? "确认以本地为准" : "确认以云端为准")) {
                    let chosen = conflicts.filter { selectedIDs.contains($0.id) }
                    batchChoice = nil
                    resolve(chosen, keepLocal: keepLocal)
                }
            }
            Button(text("取消"), role: .cancel) { batchChoice = nil }
        } message: {
            Text(text("将处理所有选中记录的争议内容。可兼容修改会保留，删除冲突按整条记录选择；确认后同步生效。"))
        }
        .appSheet(item: $selection) { conflict in
            NavigationStack {
                List {
                    Section {
                        Text(recordTitle(conflict)).font(AppDesign.rowTitleFont)
                        Text(text(deleted(conflict.localSnapshot, envelope: false) ? "本地：已删除" : "本地：保留记录"))
                        Text(text(deleted(conflict.remoteSnapshot, envelope: true) ? "云端：已删除" : "云端：保留记录"))
                        comparison(text("记录 ID"), value: conflict.entityID)
                        comparison(text("云端修订版本"), value: String(conflict.remoteVersion))
                    }
                    ForEach(differences(conflict), id: \.name) { difference in
                        Section {
                            comparison(text("本地修改"), value: difference.local)
                            comparison(text("云端状态"), value: difference.remote)
                        } header: {
                            Text(difference.title).appSectionHeaderStyle()
                        }
                    }
                    if differences(conflict).isEmpty { Section { Text(text("双方内容相同，冲突可能来自旧修订或同步元数据。")) } }
                    Section {
                        DisclosureGroup(text("原始快照比较")) {
                            comparison(text("本地快照"), value: pretty(conflict.localSnapshot))
                            comparison(text("云端快照"), value: pretty(conflict.remoteSnapshot))
                        }
                    }
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
        let name: String
        let title: String
        let local: String
        let remote: String
    }

    private func differences(_ conflict: SyncConflict) -> [Difference] {
        guard let entity = try? SyncEntityCatalog.entity(conflict.table),
              let local = try? FullSyncRepository.object(conflict.localSnapshot),
              let envelope = try? FullSyncRepository.object(conflict.remoteSnapshot) else { return [] }
        let remote = envelope["snapshot"] as? [String: Any] ?? [:]
        let ignored: Set<String> = ["id", "server_version", "stored_value_effects"]
        var result = entity.fields.compactMap { field -> Difference? in
            guard !field.binary, !field.derived, !ignored.contains(field.name),
                  (try? SyncSnapshotRebase.equal(local[field.name], remote[field.name])) == false else { return nil }
            return Difference(name: field.name, title: fieldTitle(field),
                              local: display(local[field.name], field: field), remote: display(remote[field.name], field: field))
        }
        let known = Set(entity.fields.map(\.name))
        for key in Set(local.keys).union(remote.keys).subtracting(known).sorted() where key != "sync_status" {
            guard (try? SyncSnapshotRebase.equal(local[key], remote[key])) == false else { continue }
            result.append(Difference(name: key, title: key == "_media" ? text("媒体内容（校验和、大小与类型）") : key,
                                     local: rawValue(local[key]), remote: rawValue(remote[key])))
        }
        return result
    }

    private func rawValue(_ value: Any?) -> String {
        guard let value else { return "—" }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .prettyPrinted, .fragmentsAllowed]) else { return String(describing: value) }
        return String(decoding: data, as: UTF8.self)
    }

    private func fieldTitle(_ field: SyncField) -> String {
        let title = field.labels[labelLanguage] ?? field.name
        guard title == field.name else { return title }
        let labels = ["created_at": "创建时间", "updated_at": "修改时间", "deleted_at": "删除时间",
                      "device_id": "修改设备", "title": "标题", "name": "名称", "note": "备注",
                      "storage_key": "素材存储位置", "storage_backend": "素材存储方式", "checksum": "内容校验和",
                      "byte_count": "文件字节数", "mime_type": "媒体格式", "width": "宽度", "height": "高度",
                      "sort_order": "排序", "kind": "类型", "started_at": "开始时间", "ended_at": "结束时间",
                      "desired_accuracy_m": "定位精度（米）", "distance_m": "距离（米）", "duration_s": "时长（秒）",
                      "avg_speed_mps": "平均速度（米/秒）", "max_speed_mps": "最高速度（米/秒）", "status": "状态"]
        return labels[field.name].map(text) ?? title
    }

    private func pretty(_ json: String) -> String {
        (try? FullSyncRepository.object(json)).map { rawValue($0) } ?? json
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
                formatter.timeStyle = .medium
                return formatter.string(from: Date(timeIntervalSince1970: number.doubleValue / 1000)) + "\n(\(number.stringValue) ms)"
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
        selectedIDs.formIntersection(Set(conflicts.map(\.id)))
    }

    private func resolve(_ conflict: SyncConflict, keepLocal: Bool) {
        resolve([conflict], keepLocal: keepLocal)
    }

    private func resolve(_ conflicts: [SyncConflict], keepLocal: Bool) {
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
                try FullSyncRepository(database: container.database).resolveConflicts(scope: scope, conflicts: conflicts, keepLocal: keepLocal)
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
