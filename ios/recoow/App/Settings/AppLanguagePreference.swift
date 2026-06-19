import Foundation
import SwiftUI

enum AppLanguagePreference: String, CaseIterable, Identifiable {
    case system
    case english
    case simplifiedChinese

    nonisolated var id: String { rawValue }

    nonisolated var title: LocalizedStringKey {
        switch self {
        case .system:
            "跟随系统"
        case .english:
            "English"
        case .simplifiedChinese:
            "简体中文"
        }
    }

    nonisolated var localeIdentifier: String? {
        switch self {
        case .system:
            nil
        case .english:
            "en-US"
        case .simplifiedChinese:
            "zh-Hans-CN"
        }
    }

    nonisolated var resourceIdentifier: String? {
        switch self {
        case .system:
            nil
        case .english:
            "en"
        case .simplifiedChinese:
            "zh-Hans"
        }
    }

    nonisolated var locale: Locale {
        guard let localeIdentifier else { return .autoupdatingCurrent }
        return Locale(identifier: localeIdentifier)
    }

    nonisolated var appleLanguagesValue: [String]? {
        guard let localeIdentifier else { return nil }
        return [localeIdentifier]
    }
}
