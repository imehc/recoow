import Foundation

enum AppLocalization {
    nonisolated static var currentLanguage: AppLanguagePreference {
        let storedLanguage = UserDefaults.standard.string(forKey: AppPreferenceStorageKeys.language)
        return storedLanguage.flatMap(AppLanguagePreference.init(rawValue:)) ?? .system
    }

    nonisolated static var currentLocale: Locale {
        currentLanguage.locale
    }

    nonisolated static func string(_ key: String) -> String {
        string(key, language: currentLanguage)
    }

    nonisolated static func string(_ key: String, language: AppLanguagePreference) -> String {
        if let resourceIdentifier = language.resourceIdentifier,
           let path = Bundle.main.path(forResource: resourceIdentifier, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return NSLocalizedString(key, bundle: bundle, comment: "")
        }

        return NSLocalizedString(key, comment: "")
    }

    nonisolated static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: currentLocale, arguments: arguments)
    }

    nonisolated static func format(_ key: String, language: AppLanguagePreference, _ arguments: CVarArg...) -> String {
        String(format: string(key, language: language), locale: language.locale, arguments: arguments)
    }
}
