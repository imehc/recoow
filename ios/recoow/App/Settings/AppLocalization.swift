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
        if let resourceIdentifier = currentLanguage.resourceIdentifier,
           let path = Bundle.main.path(forResource: resourceIdentifier, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return NSLocalizedString(key, bundle: bundle, comment: "")
        }

        return NSLocalizedString(key, comment: "")
    }

    nonisolated static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: currentLocale, arguments: arguments)
    }
}
