import SwiftUI
import RecoowAPI

struct CloudAccountLoginView: View {
    @Environment(AppContainer.self) private var container
    @State private var endpoint = ""
    @State private var username = ""
    @State private var password = ""
    @State private var attempted = false
    @State private var busy = false
    @State private var confirming = false
    @State private var error: String?
    @State private var loggedInUsername: String?
    @State private var loginTask: Task<Void, Never>?
    private var config: RecoowSyncConfig { container.cloudSyncConfig }
    private var language: AppLanguagePreference { container.appPreferences.language }
    private func text(_ key: String) -> String { AppLocalization.string(key, language: language) }

    var body: some View {
        Form {
            if let loggedInUsername {
                Section {
                    LabeledContent(text("用户名"), value: loggedInUsername)
                    Text(text("登录成功"))
                    NavigationLink(text("账号与云同步")) { CloudAccountSecurityView() }
                } footer: {
                    Text(text("首次同步前会自动备份。内容冲突时保留本机资料，等待确认。"))
                }
            } else {
                Section {
                    TextField(text("HTTPS 服务器地址"), text: $endpoint, prompt: Text(text("HTTPS 服务器地址")).foregroundStyle(AppPalette.secondaryInk))
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                } header: { Text(text("服务器")) } footer: {
                    if attempted && (try? CloudAccountValidation.endpoint(endpoint)) == nil {
                        Text(text("请输入有效的 HTTPS 服务器地址")).foregroundStyle(AppPalette.red)
                    }
                }
                Section {
                    TextField(text("用户名"), text: $username, prompt: Text(text("用户名")).foregroundStyle(AppPalette.secondaryInk))
                        .textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                    SecureField(text("密码"), text: $password, prompt: Text(text("密码")).foregroundStyle(AppPalette.secondaryInk)).textContentType(.password)
                } header: { Text(text("账号")) } footer: {
                    if attempted {
                        if username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(text("请输入用户名")).foregroundStyle(AppPalette.red)
                        }
                        if let issue = CloudAccountValidation.passwordError(password) {
                            Text(text(issue)).foregroundStyle(AppPalette.red)
                        }
                    }
                }
                Section {
                    Button(text("登录账号")) {
                        attempted = true
                        guard (try? CloudAccountValidation.endpoint(endpoint)) != nil,
                              !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                              CloudAccountValidation.password(password) else { return }
                        confirming = true
                    }
                } footer: {
                    Text(text("密码登录加入已有账号，成为普通设备。主设备管理、配对码和恢复密钥方式保留。"))
                }
            }
            if let error { Section { Text(error).foregroundStyle(AppPalette.red) } }
            if busy { Section { ProgressView(text("正在登录")) } }
        }
        .disabled(busy || container.syncProgress.isRunning)
        .appFormStyle()
        .navigationTitle(text("登录账号"))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear { if endpoint.isEmpty { endpoint = config.baseURL } }
        .onDisappear { loginTask?.cancel(); password = "" }
        .alert(text("登录此账号？"), isPresented: $confirming) {
            Button(text("登录账号")) { login() }
            Button(text("取消"), role: .cancel) {}
        } message: {
            Text(text("登录后启用云同步，合并本机与云端资料。同账号重登保留本机资料并继续增量同步。"))
        }
    }

    private func login() {
        guard !busy, !container.syncProgress.isRunning else { return }
        busy = true
        error = nil
        let connection = config.connectionID
        let oldToken = config.deviceToken
        let enteredUsername = username
        let enteredPassword = password
        loginTask = Task {
            var issued: CloudDeviceLogin?
            var client: RecoowAPIClient?
            do {
                let server = try CloudAccountValidation.endpoint(endpoint)
                let api = RecoowAPIClient(config: config).sessionForEndpoint(server)
                let response = try await api.loginAccount(username: enteredUsername, password: enteredPassword)
                issued = response
                let authenticated = api.sessionForEndpoint(server, token: response.deviceToken)
                client = authenticated
                let caps = try await authenticated.capabilities()
                try caps.validate(accountID: response.accountId)
                guard caps.features.contains("account_credentials_v1") else { throw CloudAPIError.badStatus(404, "unsupported") }
                try Task.checkCancellation()
                guard connection == config.connectionID, oldToken == config.deviceToken,
                      !container.syncProgress.isRunning else { throw CloudAPIError.unconfigured }
                do {
                    container.database.beginDataImport()
                    defer { container.database.endDataImport() }
                    try config.installDeviceCredentials(endpoint: server, accountID: response.accountId,
                        deviceID: response.deviceId, token: response.deviceToken, enableSync: true)
                }
                config.lastSyncMessage = text("登录成功，正在连接云同步")
                loggedInUsername = response.username
                password = ""
                issued = nil
                Task { await container.syncEngine.enqueueScan() }
            } catch {
                if let issued, let client { try? await client.deleteDevice(id: issued.deviceId) }
                if !Task.isCancelled { self.error = CloudAccountValidation.message(for: error) }
            }
            busy = false
        }
    }
}
