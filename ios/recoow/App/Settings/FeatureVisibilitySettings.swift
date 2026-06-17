import Foundation
import Observation

@MainActor
@Observable
final class FeatureVisibilitySettings {
    private static let hiddenRoutesKey = "home.hiddenToolRoutes"

    @ObservationIgnored private let defaults: UserDefaults?
    private var hiddenRouteIDs: Set<String>

    init(defaults: UserDefaults? = .standard) {
        self.defaults = defaults
        hiddenRouteIDs = Set(defaults?.stringArray(forKey: Self.hiddenRoutesKey) ?? [])
    }

    var visibleTools: [ToolRoute] {
        ToolRoute.allCases.filter { isVisible($0) }
    }

    func isVisible(_ route: ToolRoute) -> Bool {
        hiddenRouteIDs.contains(route.rawValue) == false
    }

    func setVisible(_ isVisible: Bool, for route: ToolRoute) {
        if isVisible {
            hiddenRouteIDs.remove(route.rawValue)
        } else {
            hiddenRouteIDs.insert(route.rawValue)
        }

        defaults?.set(hiddenRouteIDs.sorted(), forKey: Self.hiddenRoutesKey)
    }
}
