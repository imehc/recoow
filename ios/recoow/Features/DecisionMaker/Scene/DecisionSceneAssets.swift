import Foundation
import RealityKit

/// 跨多次集合页访问保留已导入但未挂父级的预制件。每个渲染器拿到各自的克隆，
/// 因此外观与动画改动不会影响其他场景或缓存的预制件。
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

    /// 从集合页开始准备，不必等用户打开某个转盘。
    /// 可选食物模型只在其名称出现在集合中时才请求。
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
