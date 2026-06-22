import Foundation

enum AppDeepLink: Equatable {
    case tool(ToolRoute)

    init?(url: URL) {
        guard url.scheme?.lowercased() == "recoow" else {
            return nil
        }

        let host = url.host?.lowercased()
        let pathComponents = url.pathComponents.filter { $0 != "/" }

        switch host {
        case "tool":
            guard let routeID = pathComponents.first,
                  let route = ToolRoute(rawValue: routeID) else {
                return nil
            }
            self = .tool(route)
        default:
            return nil
        }
    }

    var url: URL {
        switch self {
        case .tool(let route):
            URL(string: "recoow://tool/\(route.rawValue)")!
        }
    }
}
