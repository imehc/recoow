import SwiftUI
import RecoowAPI

/// “设备管理”：查看已绑定设备；生成一次性配对码与吊销设备仅限主设备。
struct CloudDeviceManagementView: View {
    @Environment(AppContainer.self) private var container
    @State private var devices: [CloudDevice] = []
    @State private var isLoading = true
    @State private var isGeneratingCode = false
    @State private var pairingCode: String?
    @State private var message: String?
    @State private var isErrorMessage = false
    @State private var deviceToRevoke: CloudDevice?
    @State private var isRevoking = false

    private var config: RecoowSyncConfig { container.cloudSyncConfig }
    private var language: AppLanguagePreference { container.appPreferences.language }

    private var currentDevice: CloudDevice? {
        guard let deviceID = config.deviceID else { return nil }
        return devices.first { $0.id == deviceID }
    }

    private var isOwner: Bool {
        currentDevice?.isOwner == true
    }

    var body: some View {
        List {
            if let message {
                Section {
                    Text(message)
                        .foregroundStyle(isErrorMessage ? .red : .secondary)
                }
            }

            Section {
                if isLoading {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(AppLocalization.string("正在读取设备…", language: language))
                            .foregroundStyle(.secondary)
                    }
                } else if devices.isEmpty {
                    Text(AppLocalization.string("还没有已绑定设备", language: language))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(devices, id: \.id) { device in
                        deviceRow(device)
                    }
                }
            } header: {
                Text(AppLocalization.string("已绑定设备", language: language))
            } footer: {
                // 页脚是权限说明，非主设备没有管理操作，整段不出现，避免多出一段无用的说明。
                if isOwner {
                    Text(AppLocalization.format("设备数：%d｜只有主设备能签发配对码或吊销其他设备。", language: language, devices.count))
                }
            }

            if isOwner {
                Section {
                    Button {
                        generatePairingCode()
                    } label: {
                        HStack {
                            Label(AppLocalization.string("生成一次性配对码", language: language), systemImage: "qrcode")
                            Spacer()
                            if isGeneratingCode { ProgressView() }
                        }
                    }
                    .disabled(isGeneratingCode || isLoading)
                } footer: {
                    Text(AppLocalization.string("配对码只显示一次且有时效，供新设备在“云同步”页输入加入本账号。", language: language))
                }
            }
        }
        .appGroupedListStyle()
        .navigationTitle(AppLocalization.string("设备管理", language: language))
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: refresh)
        .alert(AppLocalization.string("新的配对码", language: language), isPresented: .isPresent($pairingCode), presenting: pairingCode) { _ in
            Button(AppLocalization.string("知道了", language: language), role: .cancel) {
                pairingCode = nil
            }
        } message: { code in
            Text(AppLocalization.format("请在另一台设备的“云同步 → 加入已有账号”里输入：\n\n%@", language: language, code))
        }
        .alert(AppLocalization.string("吊销这台设备？", language: language), isPresented: .isPresent($deviceToRevoke), presenting: deviceToRevoke) { device in
            Button(AppLocalization.string("吊销", language: language), role: .destructive) {
                revoke(device)
            }
            Button(AppLocalization.string("取消", language: language), role: .cancel) { }
        } message: { _ in
            Text(AppLocalization.string("吊销后该设备将无法再同步本账号数据。", language: language))
        }
    }

    // MARK: - 行视图

    private func deviceRow(_ device: CloudDevice) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.name.isEmpty ? AppLocalization.string("未命名设备", language: language) : device.name)
                        .fontWeight(.medium)
                    if device.isOwner {
                        Text(AppLocalization.string("主设备", language: language))
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.purple.opacity(0.15)))
                            .foregroundStyle(.purple)
                    } else if device.id == config.deviceID {
                        Text(AppLocalization.string("本机", language: language))
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.secondary.opacity(0.15)))
                            .foregroundStyle(.secondary)
                    }
                }
                if let lastSeen = lastSeenText(device) {
                    Text(AppLocalization.format("最近活跃：%@", language: language, lastSeen))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if canRevoke(device) {
                Button(role: .destructive) {
                    deviceToRevoke = device
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .disabled(isRevoking || isGeneratingCode)
            }
        }
        .padding(.vertical, 2)
    }

    /// 接口返回的是 RFC 3339 原文，展示前换成当前语言的本地时间；
    /// 解析失败时退回原文，保留“有这台设备活跃过”的信息而不是整行消失。
    private func lastSeenText(_ device: CloudDevice) -> String? {
        guard device.lastSeenAt.isEmpty == false else { return nil }
        return AppFormatters.dateTime(iso8601: device.lastSeenAt, locale: language.locale)
            ?? device.lastSeenAt
    }

    private func canRevoke(_ device: CloudDevice) -> Bool {
        // 吊销是主设备的管理动作：非主设备不显示入口，主设备也不能吊销本机（会丢掉签发权）。
        isOwner && device.id != config.deviceID
    }

    // MARK: - 动作

    private func refresh() {
        isLoading = true
        message = nil
        isErrorMessage = false
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                devices = try await api.listDevices()
            } catch {
                message = AppLocalization.format("错误：%@", language: language, error.localizedDescription)
                isErrorMessage = true
            }
            isLoading = false
        }
    }

    private func generatePairingCode() {
        guard isOwner else { return }
        isGeneratingCode = true
        message = nil
        isErrorMessage = false
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                pairingCode = try await api.createPairingCode()
            } catch {
                message = AppLocalization.format("错误：%@", language: language, error.localizedDescription)
                isErrorMessage = true
            }
            isGeneratingCode = false
        }
    }

    private func revoke(_ device: CloudDevice) {
        isRevoking = true
        message = nil
        isErrorMessage = false
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                try await api.deleteDevice(id: device.id)
                devices.removeAll { $0.id == device.id }
                let name = device.name.isEmpty ? AppLocalization.string("该设备", language: language) : device.name
                message = AppLocalization.format("已吊销 %@", language: language, name)
            } catch {
                message = AppLocalization.format("错误：%@", language: language, error.localizedDescription)
                isErrorMessage = true
            }
            isRevoking = false
        }
    }
}
