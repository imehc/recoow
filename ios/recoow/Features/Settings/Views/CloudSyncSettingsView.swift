import SwiftUI

/// “云同步”设置页：配置服务器、绑定账号（初始化/配对），并支持手动同步上传本地变更。
/// 通过 AppContainer 拿到与同步引擎同一份 RecoowSyncConfig，保证读写一致。
struct CloudSyncSettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var serverInput = ""
    @State private var pairCodeInput = ""
    @State private var busyKind: BusyKind = .none
    @State private var pendingCount = 0
    @State private var banner: String?
    @State private var recoveryKeyToShow: String?
    @State private var syncEnabled = false

    private enum BusyKind: Equatable {
        case none
        case initialize
        case pair
        case sync
    }

    private var config: RecoowSyncConfig { container.cloudSyncConfig }
    private var language: AppLanguagePreference { container.appPreferences.language }

    var body: some View {
        Form {
            serverSection
            if config.isConfigured {
                bindingSection
            } else {
                onboardingSections
            }
            syncSection
            uploadScopeSection
            if config.isConfigured {
                unbindSection
            }
        }
        .appFormStyle()
        .navigationTitle(AppLocalization.string("云同步", language: language))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: reload)
        .alert("请保存恢复密钥", isPresented: .isPresent($recoveryKeyToShow), presenting: recoveryKeyToShow) { _ in
            Button("我已离线保存", role: .cancel) {
                config.deviceToken = nil
                config.accountID = nil
                config.deviceID = nil
                config.lastSyncMessage = "尚未保存恢复密钥，未绑定"
                recoveryKeyToShow = nil
                reload()
            }
            Button("保存并完成") {
                recoveryKeyToShow = nil
                reload()
            }
        } message: { key in
            Text("这台设备将成为主设备。请把下面这串恢复密钥抄写并离线保存，将来丢机时可凭它重建主设备。\n\n\(key)")
        }
    }

    // MARK: - 分区

    private var serverSection: some View {
        Section {
            TextField("https:// 或 http://地址", text: $serverInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .onAppear {
                    if serverInput.isEmpty {
                        serverInput = config.baseURL
                    }
                }
                .onChange(of: serverInput) { _, newValue in
                    config.baseURL = newValue
                    syncEnabled = config.isSyncEnabled
                }
        } header: {
            Text("服务器")
        } footer: {
            Text("填自托管后端地址，例如 http://192.168.1.10:8080")
        }
    }

    @ViewBuilder
    private var bindingSection: some View {
        Section {
            LabeledContent("绑定状态", value: "已连接")
            if let account = config.accountID, account.isEmpty == false {
                LabeledContent("账号", value: Self.short(account))
            }
            LabeledContent("同步游标", value: "\(config.syncCursor)")
        } header: {
            Text("绑定信息")
        }

        Section {
            NavigationLink {
                CloudDeviceManagementView()
            } label: {
                Label("设备管理", systemImage: "ipad.and.iphone")
            }
            NavigationLink {
                SyncConflictsView()
            } label: {
                Label("处理同步冲突", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
            }
        } footer: {
            Text("查看已绑定设备、生成配对码或吊销设备。")
        }
    }

    @ViewBuilder
    private var onboardingSections: some View {
        Section {
            Button(action: beginInitializeAccount) {
                HStack {
                    Label("初始化新账号（成为主设备）", systemImage: "plus.circle")
                    Spacer()
                    if busyKind == .initialize { ProgressView() }
                }
            }
            .disabled(busyKind != .none || serverInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } header: {
            Text("还没有账号")
        } footer: {
            Text("首次部署用：本机成为主设备，可再为其他设备签发配对码。")
        }

        Section {
            TextField("配对码", text: $pairCodeInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(action: beginPairWithCode) {
                HStack {
                    Label("加入已有账号", systemImage: "link")
                    Spacer()
                    if busyKind == .pair { ProgressView() }
                }
            }
            .disabled(busyKind != .none || pairCodeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } header: {
            Text("已有账号")
        } footer: {
            Text("在主设备的设备管理里生成配对码，新设备输入即可加入。")
        }
    }

    private var syncSection: some View {
        Section {
            Toggle("启用全量双向同步", isOn: $syncEnabled)
                .disabled(!config.isConfigured || busyKind != .none)
                .onChange(of: syncEnabled) { _, enabled in
                    config.isSyncEnabled = enabled
                }
            Button(action: beginManualSync) {
                HStack {
                    Label("立即同步", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if busyKind == .sync { ProgressView() }
                }
            }
            .disabled(!config.isConfigured || !syncEnabled || busyKind != .none)
        } header: {
            Text("手动同步")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("默认关闭，不会自动接管现有数据。启用后首次同步前自动备份，冲突保留本地内容，需手动确认。")
                if let banner {
                    Text(banner)
                }
                if config.lastSyncMessage.isEmpty == false {
                    Text(config.lastSyncMessage)
                }
                if let date = config.lastSyncedAt {
                    Text("上次同步：\(Self.dateText(date))")
                }
                Text("本地待同步记录：\(pendingCount) 条")
            }
        }
    }

    private var uploadScopeSection: some View {
        Section {
            ForEach(RecoowSyncConfig.enabledUploadTables.sorted(), id: \.self) { table in
                LabeledContent(table, value: "双向同步")
            }
        } header: {
            Text("同步范围")
        } footer: {
            Text("统一同步全部业务模块与媒体文件。首次绑定或更换服务器会盘点本地数据并恢复云端快照；日常同步只传输变化。切换账号仍使用当前本地数据，请仅连接同一份个人数据集。")
        }
    }

    @ViewBuilder
    private var unbindSection: some View {
        Section {
            Button(role: .destructive) {
                config.deviceToken = nil
                config.accountID = nil
                config.deviceID = nil
                config.syncCursor = 0
                config.lastSyncedAt = nil
                config.lastSyncMessage = "已解除本机与服务器的绑定"
                reload()
            } label: {
                Text("解除服务器绑定")
            }
        } footer: {
            Text("仅解除本机同步配置，不会删除服务器上的备份数据。")
        }
    }

    // MARK: - 动作

    private func beginInitializeAccount() {
        guard busyKind == .none else { return }
        busyKind = .initialize
        banner = nil
        Task {
            do {
                let api = RecoowAPIClient(config: config)
                let response = try await api.initializeAccount()
                // 先持久化 token，等用户确认保存恢复密钥后保持绑定。
                config.deviceToken = response.deviceToken
                config.accountID = response.accountId
                config.deviceID = response.deviceId
                config.syncCursor = 0
                recoveryKeyToShow = response.recoveryKey
                banner = "账号创建成功，绑定后点击“立即同步”上传本地数据"
            } catch {
                banner = error.localizedDescription
            }
            busyKind = .none
        }
    }

    private func beginPairWithCode() {
        guard busyKind == .none else { return }
        busyKind = .pair
        banner = nil
        Task {
            do {
                let api = RecoowAPIClient(config: config)
                let response = try await api.pair(code: pairCodeInput)
                config.deviceToken = response.deviceToken
                config.accountID = response.accountId
                config.deviceID = response.deviceId
                config.syncCursor = 0
                pairCodeInput = ""
                banner = "绑定成功，点击“立即同步”上传或拉取数据"
            } catch {
                banner = error.localizedDescription
            }
            busyKind = .none
        }
    }

    private func beginManualSync() {
        guard config.isConfigured, busyKind == .none else { return }
        busyKind = .sync
        banner = nil
        Task {
            await container.syncEngine.enqueueScan()
            reload()
            busyKind = .none
        }
    }

    private func reload() {
        syncEnabled = config.isSyncEnabled
        if let scope = config.activeScope {
            pendingCount = (try? FullSyncRepository(database: container.database).pendingCount(scope: scope)) ?? 0
        } else {
            pendingCount = (try? container.changeLogRepository.countPending(tables: RecoowSyncConfig.enabledUploadTables)) ?? 0
        }
        if serverInput != config.baseURL {
            serverInput = config.baseURL
        }
    }

    // MARK: - 辅助

    private static func short(_ value: String) -> String {
        guard value.count > 10 else { return value }
        return "\(value.prefix(6))…\(value.suffix(4))"
    }

    private static func dateText(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }
}
