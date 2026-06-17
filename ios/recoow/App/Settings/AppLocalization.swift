import Foundation

enum AppLocalization {
    static var currentLanguage: AppLanguagePreference {
        let storedLanguage = UserDefaults.standard.string(forKey: AppPreferenceStorageKeys.language)
        return storedLanguage.flatMap(AppLanguagePreference.init(rawValue:)) ?? .system
    }

    static var currentLocale: Locale {
        currentLanguage.locale
    }

    static func string(_ key: String) -> String {
        if let localeIdentifier = currentLanguage.localeIdentifier,
           let path = Bundle.main.path(forResource: localeIdentifier, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return NSLocalizedString(key, bundle: bundle, comment: "")
        }

        return NSLocalizedString(key, comment: "")
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: currentLocale, arguments: arguments)
    }
}
