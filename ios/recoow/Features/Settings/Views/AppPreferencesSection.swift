import SwiftUI

struct AppPreferencesSection: View {
    @Bindable var preferences: AppPreferences

    var body: some View {
        let language = preferences.language

        Section {
            Picker(AppLocalization.string("语言", language: language), selection: $preferences.language) {
                ForEach(AppLanguagePreference.allCases) { preference in
                    Text(preference.title)
                        .tag(preference)
                }
            }

            Picker(AppLocalization.string("外观", language: language), selection: $preferences.appearance) {
                ForEach(AppAppearancePreference.allCases) { preference in
                    Text(preference.title)
                        .tag(preference)
                }
            }
        } header: {
            Text(AppLocalization.string("显示与语言", language: language))
        } footer: {
            Text(AppLocalization.string("默认跟随系统设置，也可以手动指定语言和日间或夜间模式。", language: language))
        }
    }
}
