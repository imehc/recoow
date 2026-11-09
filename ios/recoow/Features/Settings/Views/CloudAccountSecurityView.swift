import SwiftUI
import RecoowAPI

struct CloudAccountSecurityView: View {
    @Environment(AppContainer.self) private var container
    @State private var identity: CloudAccountIdentity?
    @State private var supported = false
    @State private var isOwner = false
    @State private var loading = true
    @State private var error: String?
    @State private var result: String?
    @State private var editor: AccountPasswordMode?
    @State private var loadTask: Task<Void, Never>?
    @State private var showingLogout = false
    @State private var showingReset = false

    private var config: RecoowSyncConfig { container.cloudSyncConfig }
    private var language: AppLanguagePreference { container.appPreferences.language }
    private func text(_ key: String) -> String { AppLocalization.string(key, language: language) }

    var body: some View {
        Form {
            Section {
                if config.isConfigured {
                    if let identity {
                        CopyableValueRow(
                            title: text("用户名"), value: identity.username,
                            copyLabel: text("复制用户名"), copiedLabel: text("已复制到剪贴板")
                        )
                    } else if loading {
                        ProgressView(text("正在读取账号"))
                    } else if supported && error == nil {
                        LabeledContent(text("用户名"), value: text("设置密码后生成"))
                    }
                } else {
                    NavigationLink(text("启用云同步")) { CloudAccountLoginView() }
                }
                if config.isConfigured {
                    LabeledContent(text("服务器"), value: config.baseURL)
                }
            } header: { Text(text("账号")) }

            if config.isConfigured {
                Section {
                    LabeledContent(text("同步状态"), value: syncStatus)
                    Toggle(text("自动同步"), isOn: Binding(get: { config.isSyncEnabled }, set: { enabled in
                        if enabled && config.needsCloudResetConfirmation { showingReset = true }
                        else {
                            config.isSyncEnabled = enabled
                            Task {
                                if enabled { await container.syncEngine.enqueueScan() }
                                else { await container.syncEngine.cancelScan() }
                                await reload()
                            }
                        }
                    }))
                    Button(text("立即同步")) {
                        Task { await container.syncEngine.enqueueScan(); await reload() }
                    }
                    .disabled(!config.isSyncEnabled || container.syncProgress.isRunning)
                    if config.lastSyncState == "authentication" { NavigationLink(text("登录账号")) { CloudAccountLoginView() } }
                    NavigationLink(text("设备管理")) { CloudDeviceManagementView() }
                    NavigationLink(text("处理同步冲突")) { SyncConflictsView() }
                } header: { Text(text("同步与设备")) }
            }

            if config.isConfigured && !loading && supported && error == nil {
                Section {
                    if identity == nil {
                        if isOwner {
                            passwordAction("设置密码", mode: .set, status: "未设置")
                        } else {
                            LabeledContent(text("密码"), value: text("未设置"))
                        }
                    } else {
                        passwordAction("修改密码", mode: .change)
                    }
                    passwordAction("找回密码", mode: .reset)
                } header: { Text(text("密码与安全")) } footer: {
                    if identity == nil && !isOwner { Text(text("请在主设备设置密码")) }
                }
            }

            if !config.isConfigured {
                Section {
                    NavigationLink(text("其他连接方式")) { CloudSyncSettingsView() }
                    if !config.baseURL.isEmpty { passwordAction("找回密码", mode: .reset) }
                } header: { Text(text("连接云端")) } footer: {
                    if config.hasDatasetOwner {
                        Text(text("本机资料已保留，重新登录原账号即可继续同步。"))
                    }
                }
            }
            if config.isConfigured {
                Section {
                    NavigationLink(text("连接设置")) { CloudSyncSettingsView() }
                    Button(text("退出账号"), role: .destructive) { showingLogout = true }
                }
            }
            if let result {
                Section { Text(result).accessibilityAddTraits(.updatesFrequently) }
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(AppPalette.red)
                    Button(text("重试")) { Task { await reload() } }
                }
            } else if config.isConfigured && !loading && !supported {
                Section { Text(text("服务器尚不支持账号密码，请先升级后端")) }
            }
        }
        .appFormStyle()
        .navigationTitle(text("账号与云同步"))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            loadTask?.cancel()
            loadTask = Task { await reload() }
        }
        .onDisappear { loadTask?.cancel() }
        .alert(text("退出账号？"), isPresented: $showingLogout) {
            Button(text("退出账号"), role: .destructive) {
                config.disconnect()
                Task { await container.syncEngine.cancelScan(); result = nil; error = nil; await reload() }
            }
            Button(text("取消"), role: .cancel) {}
        } message: { Text(text("退出后停止云同步，本机与云端资料均保留。")) }
        .alert(text("云端资料已重建"), isPresented: $showingReset) {
            Button(text("重新合并")) { config.confirmCloudReset(); Task { await container.syncEngine.enqueueScan(); await reload() } }
            Button(text("取消"), role: .cancel) {}
        } message: { Text(text("云端资料已清空或重建。重新合并会备份本机资料，并将现有资料上传到云端。")) }
        .appSheet(item: $editor) { mode in
            AccountPasswordEditor(mode: mode, language: language) { current, next, recovery in
                try await save(mode: mode, current: current, next: next, recovery: recovery)
            }
        }
    }

    private var syncStatus: String {
        if container.syncProgress.isRunning { return text("正在同步") }
        if config.lastSyncState == "authentication" { return text("需要重新登录") }
        if !config.isSyncEnabled { return text("已暂停") }
        switch config.lastSyncState {
        case "synced": return text("已同步")
        case "conflicts": return text("待处理冲突")
        case "offline": return text("等待联网")
        case "authentication": return text("需要重新登录")
        default: break
        }
        return text("等待同步")
    }

    private func passwordAction(_ title: String, mode: AccountPasswordMode, status: String? = nil) -> some View {
        Button { editor = mode } label: {
            HStack {
                Text(text(title)).foregroundStyle(AppPalette.ink)
                Spacer()
                if let status { Text(text(status)).foregroundStyle(AppPalette.secondaryInk) }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppPalette.secondaryInk)
                    .accessibilityHidden(true)
            }
        }
    }

    private func reload() async {
        loading = true
        identity = nil
        supported = false
        isOwner = false
        error = nil
        guard config.isConfigured else { loading = false; return }
        let connection = config.connectionID
        let token = config.deviceToken
        let accountID = config.accountID ?? ""
        let client = RecoowAPIClient(config: config).frozenSession()
        do {
            let capabilities = try await client.capabilities()
            try capabilities.validate(accountID: accountID)
            guard connection == config.connectionID, token == config.deviceToken, !Task.isCancelled else { return }
            supported = capabilities.features.contains("account_credentials_v1")
            if supported {
                let user = try await client.accountIdentity()
                let devices = try await client.listDevices()
                guard connection == config.connectionID, token == config.deviceToken, !Task.isCancelled else { return }
                if let user, user.accountId != config.accountID { throw CloudAPIError.invalidResponse("account mismatch") }
                identity = user
                isOwner = devices.first { $0.id == config.deviceID }?.isOwner == true
            }
        } catch is CancellationError { return
        } catch { self.error = CloudAccountValidation.message(for: error) }
        loading = false
    }

    private func save(mode: AccountPasswordMode, current: String, next: String, recovery: String) async throws {
        let connection = config.connectionID
        let token = config.deviceToken
        let client = RecoowAPIClient(config: config).frozenSession()
        let user: CloudAccountIdentity?
        switch mode {
        case .set: user = try await client.bindAccountPassword(next)
        case .change:
            try await client.changeAccountPassword(current: current, next: next)
            user = identity
        case .reset: user = try await client.resetAccountPassword(recoveryKey: recovery, next: next)
        }
        guard connection == config.connectionID, token == config.deviceToken else { throw CloudAPIError.unconfigured }
        if let user {
            if mode != .reset || config.isConfigured {
                guard user.accountId == config.accountID else {
                    // 密钥可能属于其他账号；不切换当前设备绑定或展示其他账号身份。
                    if mode == .reset { result = text("密码已重设，请用原用户名登录账号"); return }
                    throw CloudAPIError.invalidResponse("account mismatch")
                }
            }
            if config.isConfigured { identity = user }
            switch mode {
            case .reset: result = AppLocalization.format("密码已重设，用户名：%@", user.username)
            case .set: result = text("密码已设置")
            case .change: result = text("密码已修改，Web 会话已退出")
            }
        } else { result = text("密码已修改，Web 会话已退出") }
        error = nil
    }
}

private enum AccountPasswordMode: String, Identifiable {
    case set, change, reset
    var id: String { rawValue }
    var title: String {
        switch self { case .set: "设置密码"; case .change: "修改密码"; case .reset: "找回密码" }
    }
}

private struct AccountPasswordEditor: View {
    @Environment(\.dismiss) private var dismiss
    let mode: AccountPasswordMode
    let language: AppLanguagePreference
    let onSave: (String, String, String) async throws -> Void
    @State private var current = ""
    @State private var next = ""
    @State private var confirmation = ""
    @State private var recovery = ""
    @State private var busy = false
    @State private var attempted = false
    @State private var error: String?
    private func text(_ key: String) -> String { AppLocalization.string(key, language: language) }

    var body: some View {
        NavigationStack {
            Form {
                if mode == .change {
                    Section {
                        SecureField(text("当前密码"), text: $current, prompt: Text(text("当前密码")).foregroundStyle(AppPalette.secondaryInk)).textContentType(.password)
                    } footer: {
                        if attempted, let issue = CloudAccountValidation.passwordError(current) { Text(text(issue)).foregroundStyle(AppPalette.red) }
                    }
                }
                if mode == .reset {
                    Section {
                        SecureField(text("恢复密钥"), text: $recovery, prompt: Text(text("恢复密钥")).foregroundStyle(AppPalette.secondaryInk)).textContentType(.none)
                    } footer: {
                        if attempted && recovery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(text("请输入恢复密钥")).foregroundStyle(AppPalette.red)
                        }
                    }
                }
                Section {
                    SecureField(text("新密码"), text: $next, prompt: Text(text("新密码")).foregroundStyle(AppPalette.secondaryInk)).textContentType(.newPassword)
                } footer: {
                    if attempted, let passwordError = CloudAccountValidation.passwordError(next) {
                        Text(text(passwordError)).foregroundStyle(AppPalette.red)
                    } else {
                        Text(text("6–18 个字符，使用英文字母、数字或常用符号"))
                    }
                }
                Section {
                    SecureField(text("确认密码"), text: $confirmation, prompt: Text(text("确认密码")).foregroundStyle(AppPalette.secondaryInk)).textContentType(.newPassword)
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if attempted && next != confirmation { Text(text("两次密码不一致")).foregroundStyle(AppPalette.red) }
                        Text(text(mode == .reset
                            ? "使用恢复密钥重设密码，网页版需重新登录。"
                            : mode == .set
                                ? "用于登录网页版和其他设备，已有数据不受影响。"
                                : "修改后需重新登录网页版。"))
                    }
                }
                if let error { Section { Text(error).foregroundStyle(AppPalette.red) } }
                if busy { Section { ProgressView(text("正在保存")) } }
            }
            .disabled(busy)
            .appFormStyle()
            .navigationTitle(text(mode.title))
            .appNavigationStyle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(text("取消")) { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) { Button(text("保存")) { save() }.disabled(busy) }
            }
        }
        .interactiveDismissDisabled(busy)
        .onDisappear { current = ""; next = ""; confirmation = ""; recovery = "" }
    }

    private func save() {
        attempted = true
        guard CloudAccountValidation.password(next), next == confirmation,
              mode != .change || CloudAccountValidation.password(current),
              mode != .reset || !recovery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        busy = true
        error = nil
        Task {
            do {
                try await onSave(current, next, recovery)
                current = ""; next = ""; confirmation = ""; recovery = ""
                dismiss()
            } catch { self.error = CloudAccountValidation.message(for: error) }
            busy = false
        }
    }
}
