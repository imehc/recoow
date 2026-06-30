//
//  recoowApp.swift
//  recoow
//
//  Created by imehc on 2026/6/16.
//

import SwiftUI

@main
struct recoowApp: App {
    @State private var launchState = AppLaunchState.bootstrap(destination: .home)

    var body: some Scene {
        WindowGroup {
            switch launchState {
            case .ready(let container, let destination):
                AppRoot(
                    initialDestination: destination,
                    resetAllLocalData: resetAllLocalData
                )
                    .environment(container)
                    .id(ObjectIdentifier(container))
            case .failed(let message):
                AppLaunchRecoveryView(
                    errorMessage: message,
                    resetAllLocalData: {
                        try await resetAllLocalData(destination: .home)
                    }
                )
            }
        }
    }

    private func resetAllLocalData(destination: AppLaunchDestination) async throws {
        if case .ready(let container, _) = launchState {
            container.prepareForFullDataReset()
        }

        try AppDataResetService.resetAllLocalData()
        launchState = AppLaunchState.bootstrap(destination: destination)

        if case .failed(let message) = launchState {
            throw AppLaunchResetError.bootstrapFailed(message)
        }
    }
}

private enum AppLaunchResetError: LocalizedError {
    case bootstrapFailed(String)

    var errorDescription: String? {
        switch self {
        case .bootstrapFailed(let message):
            message
        }
    }
}

private enum AppLaunchState {
    case ready(AppContainer, AppLaunchDestination)
    case failed(String)

    @MainActor
    static func bootstrap(destination: AppLaunchDestination) -> AppLaunchState {
        do {
            let container = try AppContainer.bootstrap()
            return .ready(container, destination)
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

private struct AppLaunchRecoveryView: View {
    let errorMessage: String
    let resetAllLocalData: () async throws -> Void

    @State private var isConfirmingReset = false
    @State private var isResetting = false
    @State private var resetErrorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "externaldrive.badge.exclamationmark")
                    .font(.system(size: 52, weight: .semibold))
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)

                VStack(spacing: 8) {
                    Text(AppLocalization.string("数据无法打开"))
                        .font(.title2.weight(.semibold))

                    Text(AppLocalization.string("数据库可能已损坏。你可以清除本地数据并重新初始化 App。"))
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)

                Button(role: .destructive) {
                    isConfirmingReset = true
                } label: {
                    HStack {
                        Text(AppLocalization.string("清除所有数据并重新开始"))

                        if isResetting {
                            ProgressView()
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(isResetting)
            }
            .padding(24)
            .frame(maxWidth: 420)
            .navigationTitle(AppLocalization.string("恢复数据"))
            .alert(AppLocalization.string("清除所有数据？"), isPresented: $isConfirmingReset) {
                Button(AppLocalization.string("清除"), role: .destructive, action: reset)
                Button(AppLocalization.string("取消"), role: .cancel) { }
            } message: {
                Text(AppLocalization.string("清除所有数据确认说明"))
            }
            .alert(
                AppLocalization.string("清除所有数据失败"),
                isPresented: .isPresent($resetErrorMessage),
                presenting: resetErrorMessage
            ) { _ in
                Button(AppLocalization.string("确定"), role: .cancel) { }
            } message: { message in
                Text(message)
            }
        }
    }

    private func reset() {
        isResetting = true

        Task {
            do {
                try await resetAllLocalData()
            } catch {
                resetErrorMessage = error.localizedDescription
            }

            isResetting = false
        }
    }
}
