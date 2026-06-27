import Foundation
import Observation
import SwiftUI

@MainActor
@Observable
final class AppPreferences {
    @ObservationIgnored private let defaults: UserDefaults?

    // 语言切换只更新应用内偏好和 SwiftUI locale，不在运行时写 AppleLanguages，避免系统本地化缓存与当前视图树状态不一致。
    var language: AppLanguagePreference {
        didSet {
            defaults?.set(language.rawValue, forKey: AppPreferenceStorageKeys.language)
        }
    }

    var appearance: AppAppearancePreference {
        didSet {
            defaults?.set(appearance.rawValue, forKey: AppPreferenceStorageKeys.appearance)
        }
    }

    init(defaults: UserDefaults? = .standard) {
        self.defaults = defaults

        let storedLanguage = defaults?.string(forKey: AppPreferenceStorageKeys.language)
        language = storedLanguage.flatMap(AppLanguagePreference.init(rawValue:)) ?? .system

        let storedAppearance = defaults?.string(forKey: AppPreferenceStorageKeys.appearance)
        appearance = storedAppearance.flatMap(AppAppearancePreference.init(rawValue:)) ?? .system
    }

    var locale: Locale {
        language.locale
    }

    var colorScheme: ColorScheme? {
        appearance.colorScheme
    }

    func applyImportedSnapshot(languageRawValue: String?, appearanceRawValue: String?) {
        language = languageRawValue.flatMap(AppLanguagePreference.init(rawValue:)) ?? .system
        appearance = appearanceRawValue.flatMap(AppAppearancePreference.init(rawValue:)) ?? .system
    }

    var transferSnapshot: (languageRawValue: String, appearanceRawValue: String) {
        (language.rawValue, appearance.rawValue)
    }
}
