import Foundation
import Observation
import SwiftUI

@MainActor
@Observable
final class AppPreferences {
    @ObservationIgnored private let defaults: UserDefaults?

    var language: AppLanguagePreference {
        didSet {
            defaults?.set(language.rawValue, forKey: AppPreferenceStorageKeys.language)
            updateAppleLanguages()
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

        updateAppleLanguages()
    }

    var locale: Locale {
        language.locale
    }

    var colorScheme: ColorScheme? {
        appearance.colorScheme
    }

    private func updateAppleLanguages() {
        guard let defaults else { return }

        if let appleLanguagesValue = language.appleLanguagesValue {
            defaults.set(appleLanguagesValue, forKey: "AppleLanguages")
        } else {
            defaults.removeObject(forKey: "AppleLanguages")
        }
    }
}
