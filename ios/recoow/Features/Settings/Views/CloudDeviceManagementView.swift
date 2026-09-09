import SwiftUI

/// “设备管理”：查看已绑定设备、生成一次性配对码（仅主设备）、吊销设备。
struct CloudDeviceManagementView: View {
    @Environment(AppContainer.self) private var container
    @State private var devices: [CloudDevice] = []
    @State private var isLoading = true
    @State private var isGeneratingCode = false
    @State private var pairingCode: String?
    @State private var message: String?
    @State private var deviceToRevoke: CloudDevice?
    @State private var isRevoking = false

    private var config: RecoowSyncConfig { container.cloudSyncConfig }

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
                        .foregroundStyle(message.hasPrefix("错误") ? .red : .secondary)
                }
            }

            Section {
                if isLoading {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在读取设备…")
                            .foregroundStyle(.secondary)
                    }
                } else if devices.isEmpty {
                    Text("还没有已绑定设备")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(devices, id: \.id) { device in
                        deviceRow(device)
                    }
                }
            } header: {
                Text("已绑定设备")
            } footer: {
                Text("设备数：\(devices.count)｜只有主设备能签发配对码或吊销其他设备。")
            }

            if isOwner {
                Section {
                    Button {
                        generatePairingCode()
                    } label: {
                        HStack {
                            Label("生成一次性配对码", systemImage: "qrcode")
                            Spacer()
                            if isGeneratingCode { ProgressView() }
                        }
                    }
                    .disabled(isGeneratingCode || isLoading)
                } footer: {
                    Text("配对码只显示一次且有时效，供新设备在“云同步”页输入加入本账号。")
                }
            }
        }
        .appGroupedListStyle()
        .navigationTitle("设备管理")
        .appNavigationStyle()
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: refresh)
        .alert("新的配对码", isPresented: .isPresent($pairingCode), presenting: pairingCode) { _ in
            Button("知道了", role: .cancel) {
                pairingCode = nil
            }
        } message: { code in
            Text("请在另一台设备的“云同步 → 加入已有账号”里输入：\n\n\(code)")
        }
        .alert("吊销这台设备？", isPresented: .isPresent($deviceToRevoke), presenting: deviceToRevoke) { device in
            Button("吊销", role: .destructive) {
                revoke(device)
            }
            Button("取消", role: .cancel) { }
        } message: { device in
            Text("吊销后该设备将无法再同步本账号数据。")
        }
    }

    // MARK: - 行视图

    private func deviceRow(_ device: CloudDevice) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.name.isEmpty ? "未命名设备" : device.name)
                        .fontWeight(.medium)
                    if device.isOwner {
                        Text("主设备")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.purple.opacity(0.15)))
                            .foregroundStyle(.purple)
                    } else if device.id == config.deviceID {
                        Text("本机")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.secondary.opacity(0.15)))
                            .foregroundStyle(.secondary)
                    }
                }
                if device.lastSeenAt.isEmpty == false {
                    Text("最近活跃：\(device.lastSeenAt)")
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

    private func canRevoke(_ device: CloudDevice) -> Bool {
        if device.isOwner {
            // 主设备一般不自我吊销（会导致失去签发权），允许吊销其他主设备仅当是本机以外的。
            return isOwner && device.id != config.deviceID
        }
        // 主设备可吊销任意普通设备；普通设备只能吊销自己。
        return isOwner || device.id == config.deviceID
    }

    // MARK: - 动作

    private func refresh() {
        isLoading = true
        message = nil
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                devices = try await api.listDevices()
            } catch {
                message = "错误：\(error.localizedDescription)"
            }
            isLoading = false
        }
    }

    private func generatePairingCode() {
        guard isOwner else { return }
        isGeneratingCode = true
        message = nil
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                pairingCode = try await api.createPairingCode()
            } catch {
                message = "错误：\(error.localizedDescription)"
            }
            isGeneratingCode = false
        }
    }

    private func revoke(_ device: CloudDevice) {
        isRevoking = true
        message = nil
        let api = RecoowAPIClient(config: config)
        Task {
            do {
                try await api.deleteDevice(id: device.id)
                devices.removeAll { $0.id == device.id }
                message = "已吊销 \(device.name.isEmpty ? "该设备" : device.name)"
            } catch {
                message = "错误：\(error.localizedDescription)"
            }
            isRevoking = false
        }
    }
}
