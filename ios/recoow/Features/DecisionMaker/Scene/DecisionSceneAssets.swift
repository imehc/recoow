import Foundation
import RealityKit

/// Keep imported, unparented prototypes across collection visits. Each renderer receives its own
/// clone, so appearance and animation changes never mutate another scene or the cached prototype.
@MainActor
final class DecisionSceneAssets {
    static let shared = DecisionSceneAssets()

    private var cachedCatalog: DecisionModelCatalog?
    private var imports: [String: Task<Entity, Error>] = [:]
    private var warmup: Task<Void, Never>?

    func catalog() throws -> DecisionModelCatalog {
        if let cachedCatalog { return cachedCatalog }
        let catalog = try DecisionModelCatalog.load()
        cachedCatalog = catalog
        return catalog
    }

    func prototype(_ filename: String) async throws -> Entity {
        if let existing = imports[filename] { return try await existing.value }
        let task = Task { @MainActor in
            guard let url = DecisionModelCatalog.resourceURL(filename) else {
                throw CocoaError(.fileNoSuchFile)
            }
            return try await Entity(contentsOf: url)
        }
        imports[filename] = task
        do {
            return try await task.value
        } catch {
            imports[filename] = nil
            throw error
        }
    }

    /// Start on the collection page, without waiting for a user to open a particular turntable.
    /// Optional food models are requested only when their titles appear in a collection.
    func prewarmStage() {
        guard warmup == nil else { return }
        warmup = Task(priority: .utility) { [weak self] in
            guard let self, let catalog = try? catalog(),
                  let fallback = catalog.assets.first(where: { $0.key == catalog.fallback }) else { return }
            async let stage = prototype(catalog.stage.usdz)
            async let pad = prototype(catalog.pad.usdz)
            async let card = prototype(fallback.usdz)
            _ = try? await (stage, pad, card)
        }
    }
}
