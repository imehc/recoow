import SwiftUI
import RecoowAPI
#if canImport(UIKit)
import UIKit
#endif

/// “云同步”设置页：配置服务器、绑定账号（初始化/配对），并支持手动同步上传本地变更。
/// 通过 AppContainer 拿到与同步引擎同一份 RecoowSyncConfig，保证读写一致。
struct CloudSyncSettingsView: View {
    @Environment(AppContainer.self) private var container
    @State private var serverInput = ""
    @State private var pairCodeInput = ""
    @State private var recoveryKeyInput = ""
    @State private var busyKind: BusyKind = .none
    @State private var pendingCount = 0
    @State private var banner: String?
    @State private var recoveryKeyToShow: String?
    @State private var didCopyRecoveryKey = false
    @State private var syncEnabled = false

    private enum BusyKind: Equatable {
        case none
        case initialize
        case pair
        case recover
        case sync
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
            syncSection
            if config.isConfigured {
                unbindSection
            }
        }
        .appFormStyle()
        .navigationTitle(AppLocalization.string("云同步", language: language))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: reload)
        .appSheet(isPresented: .isPresent($recoveryKeyToShow)) {
            if let key = recoveryKeyToShow {
                recoveryKeySheet(key)
            }
        }
    }

    // MARK: - 分区

    private var serverSection: some View {
        Section {
            TextField(AppLocalization.string("https:// 或 http://地址", language: language), text: $serverInput)
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
            Text(AppLocalization.string("服务器", language: language))
        } footer: {
            Text(AppLocalization.string("自托管后端地址，例如 http://192.168.1.10:8080", language: language))
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

        Section {
            NavigationLink {
                CloudDeviceManagementView()
            } label: {
                Label(AppLocalization.string("设备管理", language: language), systemImage: "ipad.and.iphone")
            }
            NavigationLink {
                SyncConflictsView()
            } label: {
                Label(AppLocalization.string("处理同步冲突", language: language), systemImage: "exclamationmark.arrow.triangle.2.circlepath")
            }
        } footer: {
            Text(AppLocalization.string("首次绑定或更换服务器会合并本机与云端数据，请只连接同一份数据集。", language: language))
        }
    }

    @ViewBuilder
    private var onboardingSections: some View {
        Section {
            Button(action: beginInitializeAccount) {
                HStack {
                    Label(AppLocalization.string("初始化新账号（成为主设备）", language: language), systemImage: "plus.circle")
                    Spacer()
                    if busyKind == .initialize { ProgressView() }
                }
            }
            .disabled(busyKind != .none || serverInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
            .disabled(busyKind != .none || pairCodeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
            .disabled(busyKind != .none || recoveryKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
                        reload()
                    }
                }
            }
        }
    }

    private var syncSection: some View {
        Section {
            Toggle(AppLocalization.string("启用全量双向同步", language: language), isOn: $syncEnabled)
                .disabled(!config.isConfigured || busyKind != .none)
                .onChange(of: syncEnabled) { _, enabled in
                    config.isSyncEnabled = enabled
                }
            Button(action: beginManualSync) {
                HStack(spacing: 8) {
                    Label(AppLocalization.string("立即同步", language: language), systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if let counter = progressCounter {
                        Text(counter)
                            .font(AppDesign.metadataFont)
                            .monospacedDigit()
                            .foregroundStyle(AppPalette.secondaryInk)
                    }
                    if progress.isRunning || busyKind == .sync {
                        ProgressView()
                    }
                }
            }
            .disabled(!config.isConfigured || !syncEnabled || busyKind != .none || progress.isRunning)
            if progress.isRunning {
                VStack(alignment: .leading, spacing: 6) {
                    if let fraction = progress.fraction {
                        ProgressView(value: fraction)
                    } else {
                        ProgressView()
                    }
                    Text(progress.headline)
                        .font(AppDesign.metadataFont)
                        .monospacedDigit()
                        .foregroundStyle(AppPalette.secondaryInk)
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text(AppLocalization.string("手动同步", language: language))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(AppLocalization.string("默认关闭；首次同步前自动备份，冲突保留本地内容需手动确认。全部数据与媒体一起同步，不支持按模块选择。", language: language))
                if let banner {
                    Text(banner)
                }
                if config.lastSyncMessage.isEmpty == false {
                    Text(config.lastSyncMessage)
                }
                if let date = config.lastSyncedAt {
                    Text(AppLocalization.format("上次同步：%@", Self.dateText(date)))
                }
                Text(AppLocalization.format("本地待同步记录：%d 条", pendingCount))
            }
        }
    }

    /// 「立即同步」右侧的紧凑计数：只有上传与媒体阶段有确定总数。
    private var progressCounter: String? {
        switch progress.phase {
        case .uploading where progress.uploadTotal > 0:
            return "\(progress.uploaded)/\(progress.uploadTotal)"
        case .media where progress.mediaTotal > 0:
            return "\(progress.mediaDone)/\(progress.mediaTotal)"
        case .receiving where progress.received > 0:
            return "\(progress.received)"
        default:
            return nil
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
                config.lastSyncMessage = AppLocalization.string("已解除本机与服务器的绑定")
                reload()
            } label: {
                Text(AppLocalization.string("解除服务器绑定", language: language))
            }
        } footer: {
            Text(AppLocalization.string("仅解除本机配置，不删除服务器上的数据。", language: language))
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
                didCopyRecoveryKey = false
                recoveryKeyToShow = response.recoveryKey
                banner = AppLocalization.string("账号创建成功，绑定后点击“立即同步”上传本地数据")
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
                banner = AppLocalization.string("绑定成功，点击“立即同步”上传或拉取数据")
            } catch {
                banner = error.localizedDescription
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
                config.deviceToken = response.deviceToken
                config.accountID = response.accountId
                config.deviceID = response.deviceId
                config.syncCursor = 0
                recoveryKeyInput = ""
                banner = AppLocalization.string("已用恢复密钥重新绑定，点击“立即同步”拉取云端数据")
            } catch CloudAPIError.badStatus(401, _) {
                banner = AppLocalization.string("恢复密钥无效：服务器上没有匹配的账号，请核对大小写和字符")
            } catch {
                banner = error.localizedDescription
            }
            reload()
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
