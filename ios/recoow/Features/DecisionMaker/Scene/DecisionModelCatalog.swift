import Foundation

/// 模型匹配只涉及呈现层，新增预制件与别名无需数据库迁移。
nonisolated struct DecisionModelCatalog: Decodable {
    struct Asset: Decodable {
        let key: String
        let usdz: String
    }

    struct Resource: Decodable {
        let usdz: String
    }

    struct MaterialColors: Decodable {
        let light: String
        let dark: String
    }

    let fallback: String
    let aliases: [String: String]
    let assets: [Asset]
    let materials: [String: MaterialColors]
    let stage: Resource
    let pad: Resource

    static func load(bundle: Bundle = .main) throws -> Self {
        guard let url = resourceURL("decision-model-catalog.json", bundle: bundle) else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
    }

    static func resourceURL(_ filename: String, bundle: Bundle = .main) -> URL? {
        let path = filename as NSString
        let name = path.deletingPathExtension
        let ext = path.pathExtension
        return bundle.url(forResource: name, withExtension: ext, subdirectory: "Resources/DecisionModels")
            ?? bundle.url(forResource: name, withExtension: ext, subdirectory: "DecisionModels")
            ?? bundle.url(forResource: name, withExtension: ext)
    }

    static func normalizedTitle(_ title: String) -> String {
        title.precomposedStringWithCompatibilityMapping
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .lowercased()
    }

    func modelKey(title: String, explicitKey: String? = nil) -> String {
        let available = Set(assets.map(\.key))
        if let explicitKey, available.contains(explicitKey) { return explicitKey }
        if let alias = aliases[Self.normalizedTitle(title)], available.contains(alias) { return alias }
        return fallback
    }
}
