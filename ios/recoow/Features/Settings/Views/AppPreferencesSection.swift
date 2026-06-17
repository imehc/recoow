import SwiftUI

struct AppPreferencesSection: View {
    @Bindable var preferences: AppPreferences

    var body: some View {
        Section {
            Picker("语言", selection: $preferences.language) {
                ForEach(AppLanguagePreference.allCases) { preference in
                    Text(preference.title)
                        .tag(preference)
                }
            }

            Picker("外观", selection: $preferences.appearance) {
                ForEach(AppAppearancePreference.allCases) { preference in
                    Text(preference.title)
                        .tag(preference)
                }
            }
        } header: {
            Text("显示与语言")
        } footer: {
            Text("默认跟随系统设置，也可以手动指定语言和日间或夜间模式。")
        }
    }
}
