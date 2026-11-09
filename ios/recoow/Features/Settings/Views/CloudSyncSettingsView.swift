import SwiftUI
import RecoowAPI
#if canImport(UIKit)
import UIKit
#endif

/// 连接设置页：配置服务器、绑定账号（初始化/配对/恢复），同步与设备操作集中在账号页。
/// 通过 AppContainer 拿到与同步引擎同一份 RecoowSyncConfig，保证读写一致。
struct CloudSyncSettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var serverInput = ""
    @State private var pairCodeInput = ""
    @State private var recoveryKeyInput = ""
    @State private var busyKind: BusyKind = .none
    @State private var banner: String?
    @State private var recoveryKeyToShow: String?
    @State private var didCopyRecoveryKey = false
    @State private var isOwner = false
    @State private var clearGeneration: String?
    @State private var showingClearConfirmation = false
    @State private var showingServerChange = false
    @State private var serverError: String?

    private enum BusyKind: Equatable {
        case none
        case initialize
        case pair
        case recover
        case clear
    }

    private var config: RecoowSyncConfig { container.cloudSyncConfig }
    private var progress: SyncProgressStore { container.syncProgress }
    private var language: AppLanguagePreference { container.appPreferences.language }

    var body: some View {
        Form {
            serverSection
            if config.isConfigured {
                bindingSection
            } else {
                onboardingSections
            }
            if config.isConfigured {
                if isOwner { clearBackupSection }
            }
        }
        .disabled(busyKind != .none || progress.isRunning)
        .appFormStyle()
        .navigationTitle(AppLocalization.string("连接设置", language: language))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: reload)
        .task(id: config.connectionID) { await loadCloudManagement() }
        .alert(AppLocalization.string("清空服务器备份？", language: language), isPresented: $showingClearConfirmation) {
            Button(AppLocalization.string("清空服务器备份", language: language), role: .destructive) { beginClearBackup() }
            Button(AppLocalization.string("取消", language: language), role: .cancel) {}
        } message: {
            Text(AppLocalization.string("将删除当前账号的全部云端记录、媒体文件和同步历史，保留本机数据与账号绑定。本机同步会暂停；请先关闭其他设备的同步，避免旧客户端重新上传。重新启用同步会重新备份本机数据。", language: language))
        }
        .alert(AppLocalization.string("更换服务器？", language: language), isPresented: $showingServerChange) {
            Button(AppLocalization.string("更换服务器", language: language)) { saveServer() }
            Button(AppLocalization.string("取消", language: language), role: .cancel) {}
        } message: {
            Text(AppLocalization.string("本机数据保留，当前账号退出并停止同步。登录其他账号前，请先导出备份并重置本机数据。", language: language))
        }
        .appSheet(isPresented: .isPresent($recoveryKeyToShow)) {
            if let key = recoveryKeyToShow {
                recoveryKeySheet(key)
            }
        }
    }

    // MARK: - 分区

    private var serverSection: some View {
        Section {
            TextField(AppLocalization.string("HTTPS 服务器地址", language: language), text: $serverInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .onAppear {
                    if serverInput.isEmpty {
                        serverInput = config.baseURL
                    }
                }
            if serverInput.trimmingCharacters(in: .whitespacesAndNewlines) != config.baseURL {
                Button(AppLocalization.string(config.isConfigured ? "更换服务器" : "保存服务器", language: language)) {
                    guard (try? CloudAccountValidation.endpoint(serverInput)) != nil else {
                        serverError = AppLocalization.string("请输入有效的 HTTPS 服务器地址", language: language)
                        return
                    }
                    if config.isConfigured { showingServerChange = true } else { saveServer() }
                }
            }
        } header: {
            Text(AppLocalization.string("服务器", language: language))
        } footer: {
            VStack(alignment: .leading, spacing: AppDesign.metadataItemSpacing) {
                Text(serverError ?? AppLocalization.string("请输入自托管后端的 HTTPS 地址，例如 https://example.com。修改后先保存服务器地址。", language: language))
                if let banner { FormFeedbackView(message: banner, systemImage: "info.circle") }
            }
        }
    }

    @ViewBuilder
    private var bindingSection: some View {
        Section {
            LabeledContent(AppLocalization.string("绑定状态", language: language), value: AppLocalization.string("已连接", language: language))
            if let account = config.accountID, account.isEmpty == false {
                LabeledContent(AppLocalization.string("账号", language: language), value: Self.short(account))
            }
            LabeledContent(AppLocalization.string("同步游标", language: language), value: "\(config.syncCursor)")
        } header: {
            Text(AppLocalization.string("绑定信息", language: language))
        }

    }

    @ViewBuilder
    private var onboardingSections: some View {
        Section {
            NavigationLink {
                CloudAccountLoginView()
            } label: {
                Label(AppLocalization.string("登录账号", language: language), systemImage: "person.crop.circle")
            }
        }
        Section {
            Button(action: beginInitializeAccount) {
                HStack {
                    Label(AppLocalization.string("初始化新账号（成为主设备）", language: language), systemImage: "plus.circle")
                    Spacer()
                    if busyKind == .initialize { ProgressView() }
                }
            }
            .disabled(busyKind != .none || serverInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || serverInput.trimmingCharacters(in: .whitespacesAndNewlines) != config.baseURL)
        } header: {
            Text(AppLocalization.string("还没有账号", language: language))
        } footer: {
            Text(AppLocalization.string("本机成为主设备，可给其他设备签发配对码。", language: language))
        }

        Section {
            TextField(AppLocalization.string("配对码", language: language), text: $pairCodeInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(action: beginPairWithCode) {
                HStack {
                    Label(AppLocalization.string("加入已有账号", language: language), systemImage: "link")
                    Spacer()
                    if busyKind == .pair { ProgressView() }
                }
            }
            .disabled(busyKind != .none || pairCodeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || serverInput.trimmingCharacters(in: .whitespacesAndNewlines) != config.baseURL)
        } header: {
            Text(AppLocalization.string("已有账号", language: language))
        } footer: {
            Text(AppLocalization.string("在主设备的设备管理里生成配对码，输入即可加入。", language: language))
        }

        Section {
            TextField(AppLocalization.string("恢复密钥", language: language), text: $recoveryKeyInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(action: beginRecoverWithKey) {
                HStack {
                    Label(AppLocalization.string("用恢复密钥找回账号", language: language), systemImage: "key")
                    Spacer()
                    if busyKind == .recover { ProgressView() }
                }
            }
            .disabled(busyKind != .none || recoveryKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || serverInput.trimmingCharacters(in: .whitespacesAndNewlines) != config.baseURL)
        } header: {
            Text(AppLocalization.string("配对码也丢了", language: language))
        } footer: {
            Text(AppLocalization.string("用初始化时保存的密钥把账号重新绑到本机，不占账号配额；密钥丢失只能由服务器管理员清空账号。", language: language))
        }
    }

    /// 一次性恢复密钥的展示 Sheet：可长按选择、可复制，关闭不夺走绑定。
    private func recoveryKeySheet(_ key: String) -> some View {
        NavigationStack {
            Form {
                Section {
                    Text(key)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                        .accessibilityLabel(Text(AppLocalization.string("恢复密钥", language: language)))
                } header: {
                    Text(AppLocalization.string("恢复密钥", language: language))
                } footer: {
                    Text(AppLocalization.string("这台设备将成为主设备。密钥只显示一次，请离线保存，丢机时凭它找回账号。", language: language))
                }

                Section {
                    Button {
                        didCopyRecoveryKey = true
                        #if canImport(UIKit)
                        UIPasteboard.general.string = key
                        #endif
                    } label: {
                        Label(didCopyRecoveryKey ? AppLocalization.string("已复制到剪贴板") : AppLocalization.string("复制恢复密钥"),
                              systemImage: didCopyRecoveryKey ? "checkmark.circle" : "doc.on.doc")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        config.discardUnconfirmedDatasetBinding()
                        config.deviceToken = nil
                        config.accountID = nil
                        config.deviceID = nil
                        config.lastSyncMessage = AppLocalization.string("尚未保存恢复密钥，未绑定")
                        recoveryKeyToShow = nil
                        reload()
                    } label: {
                        Text(AppLocalization.string("没保存，放弃绑定", language: language))
                    }
                } footer: {
                    Text(AppLocalization.string("只清本机令牌，服务器账号仍存在；再次初始化可能被账号上限拒绝。", language: language))
                }
            }
            .appFormStyle()
            .navigationTitle(AppLocalization.string("请保存恢复密钥", language: language))
            .appNavigationStyle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalization.string("保存并完成", language: language)) {
                        recoveryKeyToShow = nil
                        config.isSyncEnabled = true
                        Task { await container.syncEngine.enqueueScan() }
                        reload()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var clearBackupSection: some View {
        Section {
            Button(role: .destructive) { showingClearConfirmation = true } label: {
                HStack {
                    Text(AppLocalization.string("清空服务器备份", language: language))
                    Spacer()
                    if busyKind == .clear { ProgressView() }
                }
            }
            .disabled(clearGeneration == nil || busyKind != .none || progress.isRunning)
        } footer: {
            Text(AppLocalization.string("仅主设备可清空当前账号的云端数据。清空后暂停同步，重新启用会重新上传本机数据。", language: language))
        }
    }

    // MARK: - 动作

    private func loadCloudManagement() async {
        isOwner = false
        clearGeneration = nil
        guard config.isConfigured else { return }
        let connection = config.connectionID
        let client = RecoowAPIClient(config: config).frozenSession()
        do {
            let devices = try await client.listDevices()
            let capabilities = try await client.capabilities()
            try capabilities.validate(accountID: config.accountID ?? "")
            guard config.connectionID == connection else { return }
            isOwner = devices.first(where: { $0.id == config.deviceID })?.isOwner == true
            if capabilities.features.contains("clear_dataset") { clearGeneration = capabilities.dataset.generation }
        } catch { /* 连接失败不显示可执行的危险入口。 */ }
    }

    private func beginClearBackup() {
        guard isOwner, busyKind == .none, !progress.isRunning, let generation = clearGeneration else { return }
        busyKind = .clear
        banner = nil
        let connection = config.connectionID
        let client = RecoowAPIClient(config: config).frozenSession()
        config.isSyncEnabled = false
        container.database.beginDataImport()
        Task {
            defer {
                container.database.endDataImport()
                busyKind = .none
            }
            do {
                let dataset = try await client.clearDataset(generation: generation)
                guard config.connectionID == connection else { return }
                guard dataset.generation != generation, dataset.head == 0 else {
                    throw CloudAPIError.invalidResponse(AppLocalization.string("清空确认无效，请刷新服务器状态"))
                }
                config.activeScope = nil
                config.needsCloudResetConfirmation = true
                config.syncCursor = 0
                config.lastSyncedAt = nil
                clearGeneration = dataset.generation
                config.lastSyncMessage = AppLocalization.string("服务器备份已清空，本机数据保留，同步已暂停。")
                banner = config.lastSyncMessage
                reload()
            } catch {
                banner = CloudAccountValidation.message(for: error)
                await loadCloudManagement()
            }
        }
    }

    private func beginInitializeAccount() {
        guard busyKind == .none else { return }
        busyKind = .initialize
        banner = nil
        Task {
            do {
                guard !config.hasDatasetOwner else { throw CloudAccountMismatch() }
                let api = RecoowAPIClient(config: config)
                let response = try await api.initializeAccount()
                do { try config.bindDataset(endpoint: config.baseURL, accountID: response.accountId) }
                catch {
                    try? await api.sessionForEndpoint(config.baseURL, token: response.deviceToken).deleteDevice(id: response.deviceId)
                    throw error
                }
                // 先持久化 token，等用户确认保存恢复密钥后保持绑定。
                config.deviceToken = response.deviceToken
                config.accountID = response.accountId
                config.deviceID = response.deviceId
                config.syncCursor = 0
                didCopyRecoveryKey = false
                recoveryKeyToShow = response.recoveryKey
                banner = AppLocalization.string("账号创建成功，请先保存恢复密钥")
            } catch {
                banner = CloudAccountValidation.message(for: error)
            }
            busyKind = .none
        }
    }

    private func saveServer() {
        guard busyKind == .none, !progress.isRunning,
              let endpoint = try? CloudAccountValidation.endpoint(serverInput) else { return }
        container.database.beginDataImport()
        defer { container.database.endDataImport() }
        config.disconnect()
        config.baseURL = endpoint
        config.isSyncEnabled = false
        config.lastSyncMessage = AppLocalization.string("服务器已保存，请连接账号")
        serverInput = endpoint
        serverError = nil
        reload()
    }

    private func beginPairWithCode() {
        guard busyKind == .none else { return }
        busyKind = .pair
        banner = nil
        Task {
            do {
                let api = RecoowAPIClient(config: config)
                let response = try await api.pair(code: pairCodeInput)
                do { try config.bindDataset(endpoint: config.baseURL, accountID: response.accountId) }
                catch {
                    try? await api.sessionForEndpoint(config.baseURL, token: response.deviceToken).deleteDevice(id: response.deviceId)
                    throw error
                }
                try config.installDeviceCredentials(endpoint: config.baseURL, accountID: response.accountId,
                    deviceID: response.deviceId, token: response.deviceToken, enableSync: true)
                Task { await container.syncEngine.enqueueScan() }
                pairCodeInput = ""
                banner = AppLocalization.string("连接成功，可返回账号与云同步查看状态")
            } catch {
                banner = CloudAccountValidation.message(for: error)
            }
            busyKind = .none
        }
    }

    /// 用恢复密钥把账号重新绑到本机。服务端只校验密钥哈希，不占账号配额。
    private func beginRecoverWithKey() {
        guard busyKind == .none else { return }
        busyKind = .recover
        banner = nil
        Task {
            do {
                let api = RecoowAPIClient(config: config)
                let response = try await api.recover(recoveryKey: recoveryKeyInput)
                do { try config.bindDataset(endpoint: config.baseURL, accountID: response.accountId) }
                catch {
                    try? await api.sessionForEndpoint(config.baseURL, token: response.deviceToken).deleteDevice(id: response.deviceId)
                    throw error
                }
                try config.installDeviceCredentials(endpoint: config.baseURL, accountID: response.accountId,
                    deviceID: response.deviceId, token: response.deviceToken, enableSync: true)
                Task { await container.syncEngine.enqueueScan() }
                recoveryKeyInput = ""
                banner = AppLocalization.string("连接成功，可返回账号与云同步查看状态")
            } catch CloudAPIError.badStatus(401, _) {
                banner = AppLocalization.string("恢复密钥无效：服务器上没有匹配的账号，请核对大小写和字符")
            } catch {
                banner = CloudAccountValidation.message(for: error)
            }
            reload()
            busyKind = .none
        }
    }

    private func reload() {
        if serverInput != config.baseURL {
            serverInput = config.baseURL
        }
    }

    // MARK: - 辅助

    private static func short(_ value: String) -> String {
        guard value.count > 10 else { return value }
        return "\(value.prefix(6))…\(value.suffix(4))"
    }

}
