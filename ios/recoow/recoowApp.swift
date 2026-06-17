//
//  recoowApp.swift
//  recoow
//
//  Created by imehc on 2026/6/16.
//

import SwiftUI

@main
struct recoowApp: App {
    @State private var container = AppContainer.bootstrap()

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .environment(container)
        }
    }
}
