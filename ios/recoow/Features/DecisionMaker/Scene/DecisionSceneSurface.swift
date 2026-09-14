import OSLog
import RealityKit
import SwiftUI

/// A non-AR RealityKit scene. Native controls sit on the projected model, so Dynamic Type,
/// VoiceOver and Switch Control do not depend on 3D hit testing.
@MainActor
final class DecisionSceneSurface: UIView, UIGestureRecognizerDelegate {
    var onDraw: (DecisionSpinInput) -> Void

    private let controller: DecisionSceneController
    private let arView = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
    private let world = AnchorEntity(world: .zero)
    private let camera = Entity()
    private let actionPivot = Entity()
    private let deckPivot = Entity()
    private let drawButton = UIButton(type: .system)
    private let selectionFeedback = UISelectionFeedbackGenerator()
    private let resultFeedback = UINotificationFeedbackGenerator()
    private var catalog: DecisionModelCatalog?
    private var prefabs: [String: Entity] = [:]
    private var padPrefab: Entity?
    private var actionCap: Entity?
    private var stage: Entity?
    private var slots: [Slot] = []
    private var options: [DecisionOption] = []
    private var cursor = 0.0
    private var dragOrigin = 0.0
    private var selectedID: String?
    private var lift: Float = 0
    private var buttonDepression: Float = 0
    private var lastFocusTime = 0.0
    private var lastTickIndex: Int?
    private var visibleLimit = 8
    private var dark = false
    private var contentSize: UIContentSizeCategory = .large
    private var reduceMotion = false
    private var active = true
    private var loaded = false
    private var disposed = false
    private var loadTask: Task<Void, Never>?
    private var modelTasks: [String: Task<Void, Never>] = [:]
    private var displayLink: CADisplayLink?
    private var motion: Motion?
    private var frameTarget: FrameTarget?

    private final class Slot {
        let anchor = Entity()
        let pad: Entity
        let label = UILabel()
        var models: [String: Entity] = [:]
        var model: Entity?
        var key: String?
        var optionIndex = 0
        var highlighted = false

        init(pad: Entity) {
            self.pad = pad
            anchor.addChild(pad)
            anchor.components.set(CollisionComponent(shapes: [.generateBox(size: [1, 1.8, 1])]))
        }
    }

    private struct Motion {
        let start: CFTimeInterval
        let duration: Double
        let frame: (Double) -> Void
        let completion: () -> Void
    }

    private final class FrameTarget: NSObject {
        weak var surface: DecisionSceneSurface?
        init(_ surface: DecisionSceneSurface) { self.surface = surface }
        @objc func tick(_ link: CADisplayLink) { surface?.tick(link) }
    }

    init(controller: DecisionSceneController, onDraw: @escaping (DecisionSpinInput) -> Void) {
        self.controller = controller
        self.onDraw = onDraw
        super.init(frame: .zero)
        clipsToBounds = true
        isAccessibilityElement = false
        arView.isAccessibilityElement = false
        arView.accessibilityElementsHidden = true
        arView.renderOptions = [.disableCameraGrain, .disableMotionBlur, .disableDepthOfField]
        addSubview(arView)
        arView.scene.addAnchor(world)

        var lens = OrthographicCameraComponent()
        lens.near = 0.1
        lens.far = 100
        lens.scaleDirection = .vertical
        camera.components.set(lens)
        camera.look(at: [0, 0.3, 0], from: [0, 6, 8], relativeTo: nil)
        world.addChild(camera)
        world.addChild(actionPivot)
        world.addChild(deckPivot)

        let keyLight = DirectionalLight()
        keyLight.light.intensity = 1200
        keyLight.look(at: .zero, from: [-3, 6, 5], relativeTo: nil)
        world.addChild(keyLight)
        let fillLight = DirectionalLight()
        fillLight.light.intensity = 400
        fillLight.look(at: .zero, from: [4, 3, -4], relativeTo: nil)
        world.addChild(fillLight)

        drawButton.addTarget(self, action: #selector(triggerDraw), for: .touchUpInside)
        drawButton.addTarget(self, action: #selector(pressButton), for: .touchDown)
        drawButton.addTarget(self, action: #selector(releaseButton), for: [.touchCancel, .touchUpOutside])
        drawButton.accessibilityIdentifier = "decision.scene.draw"
        addSubview(drawButton)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(drag(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        arView.addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapOption(_:)))
        tap.require(toFail: pan)
        arView.addGestureRecognizer(tap)

        controller.surface = self
        options = controller.options
        applyTheme()
        loadTask = Task { [weak self] in await self?.loadAssets() }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(dark: Bool, contentSize: UIContentSizeCategory, reduceMotion: Bool, active: Bool) {
        let changedTheme = self.dark != dark
        let changedType = self.contentSize != contentSize
        self.dark = dark
        self.contentSize = contentSize
        let newlyReducedMotion = reduceMotion && !self.reduceMotion
        self.reduceMotion = reduceMotion
        overrideUserInterfaceStyle = dark ? .dark : .light
        if changedTheme { applyTheme() }
        if changedType { updateLabelFonts() }
        if self.active != active {
            self.active = active
            if !active { finishMotion() }
            arView.isHidden = !active || controller.isSceneUnavailable
        }
        if newlyReducedMotion { finishMotion() }
        updateControls()
        setNeedsLayout()
    }

    private func loadAssets() async {
        do {
            controller.isLoading = true
            controller.isSceneUnavailable = false
            let catalog = try DecisionSceneAssets.shared.catalog()
            self.catalog = catalog
            guard let fallback = catalog.assets.first(where: { $0.key == catalog.fallback }) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            // Import the three small structural assets concurrently. Food models never gate the first frame.
            async let stageLoad = load(catalog.stage.usdz)
            async let padLoad = load(catalog.pad.usdz)
            async let cardLoad = load(fallback.usdz)
            requestOptionModels()
            let (stage, pad, card) = try await (stageLoad, padLoad, cardLoad)
            try Task.checkCancellation()
            guard !disposed else { return }
            self.stage = stage
            world.addChild(stage)
            // Keep USD axis conversions intact; these native pivots rotate in world Y.
            stage.findEntity(named: "DrawButton")?.setParent(actionPivot, preservingWorldTransform: true)
            stage.findEntity(named: "Turntable")?.setParent(deckPivot, preservingWorldTransform: true)
            actionCap = actionPivot.findEntity(named: "ActionCap")
            padPrefab = pad
            prefabs[fallback.key] = card
            loaded = true
            applyTheme()
            rebuildSlots()
            controller.isLoading = false
            updateControls()
            setNeedsLayout()
        } catch is CancellationError {
            return
        } catch {
            guard !disposed else { return }
            Logger(subsystem: "imehc.recoow", category: "DecisionScene")
                .error("Unable to load decision scene: \(error.localizedDescription, privacy: .public)")
            controller.isLoading = false
            controller.isSceneUnavailable = true
            arView.isHidden = true
            updateControls()
            setNeedsLayout()
        }
    }

    private func load(_ filename: String) async throws -> Entity {
        let prototype = try await DecisionSceneAssets.shared.prototype(filename)
        try Task.checkCancellation()
        return prototype.clone(recursive: true)
    }

    private func requestOptionModels() {
        guard let catalog, !disposed else { return }
        let keys = Set(options.map { catalog.modelKey(title: $0.title) })
        for key in keys where key != catalog.fallback && prefabs[key] == nil && modelTasks[key] == nil {
            guard let asset = catalog.assets.first(where: { $0.key == key }) else { continue }
            modelTasks[key] = Task { [weak self] in
                guard let model = try? await DecisionSceneAssets.shared.prototype(asset.usdz),
                      !Task.isCancelled, let self, !disposed else { return }
                let clone = model.clone(recursive: true)
                recolor(clone)
                prefabs[key] = clone
                modelTasks[key] = nil
                if loaded { positionSlots() }
            }
        }
    }

    func setOptions(_ options: [DecisionOption], focusedIndex: Int) {
        stopMotion()
        self.options = options
        cursor = Double(focusedIndex)
        selectedID = controller.resultOption?.id
        lift = selectedID == nil ? 0 : 0.2
        requestOptionModels()
        if loaded { rebuildSlots() }
        updateControls()
    }

    private func rebuildSlots() {
        for slot in slots {
            slot.anchor.removeFromParent()
            slot.label.removeFromSuperview()
        }
        slots.removeAll()
        guard let padPrefab else { return }
        for _ in 0..<min(options.count, visibleLimit) {
            let slot = Slot(pad: padPrefab.clone(recursive: true))
            slot.label.numberOfLines = 1
            slot.label.textAlignment = .center
            slot.label.lineBreakMode = .byTruncatingTail
            slot.label.layer.cornerRadius = AppDesign.iconCornerRadius / 2
            slot.label.clipsToBounds = true
            slot.label.isAccessibilityElement = false
            slot.label.isUserInteractionEnabled = false
            world.addChild(slot.anchor)
            insertSubview(slot.label, belowSubview: drawButton)
            slots.append(slot)
        }
        updateLabelFonts()
        positionSlots()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        arView.frame = bounds
        guard bounds.width > 0, bounds.height > 0 else { return }
        let newLimit = bounds.width < 380 ? 8 : 10
        if visibleLimit != newLimit {
            visibleLimit = newLimit
            if loaded { rebuildSlots() }
        }
        var lens = OrthographicCameraComponent()
        lens.near = 0.1
        lens.far = 100
        lens.scaleDirection = .vertical
        // RealityKit's orthographic scale is the half-height of the visible world.
        lens.scale = Float(max(3.6, 4.96 * bounds.height / bounds.width) / 2)
        camera.components.set(lens)
        positionSlots()
        positionButton()
    }

    private func positionSlots() {
        guard loaded, let catalog else { return }
        for placement in DecisionTurntableLayout.placements(count: options.count, limit: visibleLimit, cursor: cursor) {
            guard slots.indices.contains(placement.slot) else { continue }
            let slot = slots[placement.slot]
            let option = options[placement.optionIndex]
            let resolvedKey = catalog.modelKey(title: option.title)
            let key = prefabs[resolvedKey] == nil ? catalog.fallback : resolvedKey
            if slot.key != key {
                slot.model?.removeFromParent()
                if slot.models[key] == nil, let prefab = prefabs[key] {
                    let wrapper = Entity()
                    wrapper.addChild(prefab.clone(recursive: true))
                    wrapper.position.y = 0.074
                    slot.models[key] = wrapper
                }
                slot.model = slot.models[key]
                if let model = slot.model { slot.anchor.addChild(model) }
                slot.key = key
            }
            slot.optionIndex = placement.optionIndex
            let selected = selectedID == option.id
            slot.anchor.position = [
                Float(sin(placement.angle) * placement.radius),
                0.324 + (selected ? lift : 0),
                Float(cos(placement.angle) * placement.radius)
            ]
            slot.anchor.orientation = simd_quatf(angle: Float(placement.angle), axis: [0, 1, 0])
            slot.anchor.scale = .init(repeating: Float(max(0.001, placement.scale)))
            slot.anchor.isEnabled = placement.scale > 0.005
            if slot.highlighted != selected {
                slot.highlighted = selected
                recolor(slot.pad, highlight: selected)
            }
            slot.label.text = option.title
            slot.label.isHidden = controller.isBusy || contentSize.isAccessibilityCategory
                || cos(placement.angle) < 0.43 || options.count == 1 || bounds.height < 220
            if !slot.label.isHidden,
               let point = arView.project(slot.anchor.convert(position: [0, -0.01, 0.48], to: nil)) {
                let size = slot.label.sizeThatFits(CGSize(width: 96, height: 100))
                let width = min(108, size.width + AppDesign.gridSpacing)
                slot.label.frame = CGRect(x: point.x - width / 2, y: point.y + 5, width: width, height: size.height + 4)
            }
        }
        let pitch = 2 * Double.pi / Double(max(1, slots.count))
        deckPivot.orientation = simd_quatf(angle: Float(-cursor * pitch), axis: [0, 1, 0])
    }

    private func positionButton() {
        guard loaded, let actionCap else {
            drawButton.frame = CGRect(x: bounds.midX - 80, y: bounds.midY - 24, width: 160, height: 48)
            return
        }
        actionPivot.scale = .one
        actionPivot.position.y = -buttonDepression
        let capBounds = actionCap.visualBounds(relativeTo: nil)
        let capPoint = SIMD3<Float>(capBounds.center.x, capBounds.max.y, capBounds.center.z)
        guard let center = arView.project(capPoint),
              let right = arView.project(capPoint + [0.373, 0, 0]),
              let left = arView.project(capPoint - [0.373, 0, 0]) else { return }
        let wantedWidth: CGFloat = contentSize.isAccessibilityCategory ? 84 : 64
        let baseWidth = max(1, abs(right.x - left.x))
        let scale = min(2.2, wantedWidth / baseWidth)
        actionPivot.scale = [Float(scale), 1, Float(scale)]
        let width = max(AppDesign.touchIconSize, baseWidth * scale)
        drawButton.frame = CGRect(x: center.x - width / 2, y: center.y - AppDesign.touchIconSize / 2,
                                  width: width, height: AppDesign.touchIconSize)
        updateButtonTitle(availableWidth: width)
    }

    func updateControls() {
        drawButton.isHidden = controller.isLoading || (!loaded && !controller.isSceneUnavailable)
        drawButton.isEnabled = !controller.isBusy && !options.isEmpty
        drawButton.alpha = options.isEmpty ? 0.5 : 1
        drawButton.accessibilityLabel = AppLocalization.string(controller.isBusy ? "正在抽选" : "随机选择")
        drawButton.accessibilityHint = AppLocalization.string("按候选项权重抽选，并自动保存到历史记录。")
        drawButton.setTitleColor(resolved(AppPalette.accentForeground), for: .normal)
        drawButton.setTitleColor(resolved(AppPalette.accentForeground), for: .disabled)
        drawButton.tintColor = resolved(AppPalette.accentForeground)
        if controller.isSceneUnavailable {
            drawButton.backgroundColor = resolved(AppPalette.accent)
            drawButton.layer.cornerRadius = AppDesign.cornerRadius
        }
        updateButtonTitle(availableWidth: drawButton.bounds.width)
    }

    private func updateButtonTitle(availableWidth: CGFloat) {
        let traits = UITraitCollection(preferredContentSizeCategory: contentSize)
        let font = UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traits)
        let title = AppLocalization.string(controller.isBusy ? "抽选中" : (controller.resultOption == nil ? "抽选" : "再选"))
        drawButton.titleLabel?.font = font
        let textWidth = (title as NSString).size(withAttributes: [.font: font]).width
        if textWidth + 8 <= availableWidth {
            drawButton.setImage(nil, for: .normal)
            drawButton.setTitle(title, for: .normal)
        } else {
            drawButton.setTitle(nil, for: .normal)
            drawButton.setImage(UIImage(systemName: controller.isBusy ? "ellipsis" : "shuffle",
                                       withConfiguration: UIImage.SymbolConfiguration(textStyle: .body)), for: .normal)
        }
    }

    private func updateLabelFonts() {
        for slot in slots {
            slot.label.font = UIFont.preferredFont(forTextStyle: .footnote,
                                                  compatibleWith: UITraitCollection(preferredContentSizeCategory: contentSize))
            slot.label.textColor = resolved(AppPalette.ink)
            slot.label.backgroundColor = resolved(AppPalette.surface)
        }
    }

    private func resolved(_ color: Color) -> UIColor {
        UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
    }

    private func applyTheme() {
        let background = resolved(AppPalette.background)
        backgroundColor = background
        arView.environment.background = .color(background)
        for entity in [stage, Optional(actionPivot), Optional(deckPivot), padPrefab].compactMap({ $0 }) {
            recolor(entity)
        }
        for prefab in prefabs.values { recolor(prefab) }
        for slot in slots {
            recolor(slot.pad, highlight: slot.highlighted)
            for model in slot.models.values { recolor(model) }
        }
        updateLabelFonts()
        updateControls()
    }

    private func recolor(_ entity: Entity, highlight: Bool = false, unlit: Bool = false) {
        let unlit = unlit || entity.name.contains("ActionCap")
        if var model = entity.components[ModelComponent.self] {
            let names = entity.components[DecisionMaterialNames.self]?.names
                ?? model.materials.map { $0.name ?? "" }
            entity.components.set(DecisionMaterialNames(names: names))
            model.materials = zip(model.materials, names).map { original, name in
                let materialKey = catalog?.materials.keys.sorted { $0.count > $1.count }.first { name.contains($0) }
                guard let materialKey, let colors = catalog?.materials[materialKey] else { return original }
                let color = materialKey == "RD_Accent" || highlight
                    ? resolved(AppPalette.accent) : Self.color(hex: dark ? colors.dark : colors.light)
                if unlit {
                    var material = UnlitMaterial(applyPostProcessToneMap: false)
                    material.color = .init(tint: color)
                    return material
                }
                return SimpleMaterial(color: color, roughness: 0.82, isMetallic: false)
            }
            entity.components.set(model)
        }
        for child in entity.children { recolor(child, highlight: highlight, unlit: unlit) }
    }

    private static func color(hex: String) -> UIColor {
        let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x808080
        return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                       green: CGFloat((value >> 8) & 255) / 255,
                       blue: CGFloat(value & 255) / 255, alpha: 1)
    }

    func prepareDraw() {
        stopMotion()
        selectedID = nil
        lift = 0
        buttonDepression = 0.025
        selectionFeedback.prepare()
        resultFeedback.prepare()
        updateControls()
        positionSlots()
        positionButton()
    }

    func spin(to option: DecisionOption, input: DecisionSpinInput) {
        guard let index = options.firstIndex(where: { $0.id == option.id }) else {
            controller.finishSelection(option, announce: active)
            return
        }
        let from = cursor
        let target = DecisionTurntableLayout.drawTarget(index: index, count: options.count,
                                                       limit: visibleLimit, cursor: cursor, input: input)
        let duration = options.count == 1 || !loaded ? 0 : 2.8 + input.intensity * 0.18
        animate(duration: duration) { [weak self] progress in
            guard let self else { return }
            cursor = from + (target - from) * DecisionTurntableLayout.spinProgress(progress, fromGesture: input.fromGesture)
            buttonDepression = 0.025 * Float(max(0, 1 - progress / 0.15))
            if progress > 0.58, controller.phase != .slowing { controller.phase = .slowing }
            updateFocus(force: progress == 1)
            positionSlots()
            positionButton()
        } completion: { [weak self] in
            guard let self else { return }
            cursor = Double(index)
            selectedID = option.id
            controller.phase = .revealing
            controller.focus(index: index)
            animate(duration: 0.26) { [weak self] progress in
                self?.lift = 0.2 * Float(1 - pow(1 - progress, 3))
                self?.positionSlots()
            } completion: { [weak self] in
                guard let self else { return }
                controller.finishSelection(option, announce: active)
                if active { resultFeedback.notificationOccurred(.success) }
                positionSlots()
            }
        }
    }

    func browse(to index: Int) {
        guard options.indices.contains(index), !controller.isBusy else { return }
        selectedID = nil
        lift = 0
        let from = cursor
        let target = DecisionTurntableLayout.browseTarget(index: index, count: options.count, cursor: cursor)
        animate(duration: 0.34) { [weak self] progress in
            guard let self else { return }
            cursor = from + (target - from) * (1 - pow(1 - progress, 3))
            positionSlots()
        } completion: { [weak self] in
            guard let self else { return }
            cursor = Double(index)
            controller.focus(index: index)
            updateControls()
            positionSlots()
        }
    }

    private func updateFocus(force: Bool = false) {
        let now = CACurrentMediaTime()
        guard force || now - lastFocusTime > 0.095,
              let index = DecisionTurntableLayout.focusedIndex(cursor: cursor, count: options.count) else { return }
        lastFocusTime = now
        controller.focus(index: index)
        if lastTickIndex != index, active, !reduceMotion {
            selectionFeedback.selectionChanged()
            lastTickIndex = index
        }
    }

    @objc private func triggerDraw() { onDraw(DecisionSpinInput()) }
    @objc private func pressButton() { buttonDepression = 0.025; positionButton() }
    @objc private func releaseButton() { buttonDepression = 0; positionButton() }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard !controller.isBusy, !options.isEmpty, loaded else { return false }
        if let pan = gestureRecognizer as? UIPanGestureRecognizer {
            let velocity = pan.velocity(in: arView)
            let originX = pan.location(in: arView).x - pan.translation(in: arView).x
            return originX > 24 && abs(velocity.x) > abs(velocity.y) * 1.15
        }
        return true
    }

    @objc private func drag(_ pan: UIPanGestureRecognizer) {
        guard !controller.isBusy, !options.isEmpty else { return }
        switch pan.state {
        case .began:
            stopMotion()
            controller.beginBrowsing()
            selectedID = nil
            lift = 0
            dragOrigin = cursor
            selectionFeedback.prepare()
            updateControls()
        case .changed:
            let step = max(32, bounds.width / CGFloat(max(3, slots.count)) * 1.4)
            cursor = dragOrigin - Double(pan.translation(in: arView).x / step)
            updateFocus()
            positionSlots()
        case .ended:
            let velocity = pan.velocity(in: arView).x
            if abs(velocity) > 780, abs(pan.translation(in: arView).x) > 24 {
                onDraw(DecisionSpinInput(velocity: Double(velocity)))
            } else if let index = DecisionTurntableLayout.focusedIndex(cursor: cursor, count: options.count) {
                controller.browse(options[index])
            }
        case .cancelled, .failed:
            if let index = DecisionTurntableLayout.focusedIndex(cursor: cursor, count: options.count) {
                controller.browse(options[index])
            }
        default: break
        }
    }

    @objc private func tapOption(_ tap: UITapGestureRecognizer) {
        guard !controller.isBusy else { return }
        var entity = arView.entity(at: tap.location(in: arView))
        while let current = entity {
            if let slot = slots.first(where: { $0.anchor === current }), options.indices.contains(slot.optionIndex) {
                controller.browse(options[slot.optionIndex])
                return
            }
            entity = current.parent
        }
    }

    private func animate(duration: Double, frame: @escaping (Double) -> Void, completion: @escaping () -> Void) {
        stopMotion()
        guard duration > 0, active, !reduceMotion else {
            frame(1)
            completion()
            return
        }
        motion = Motion(start: CACurrentMediaTime(), duration: duration, frame: frame, completion: completion)
        let target = FrameTarget(self)
        frameTarget = target
        let link = CADisplayLink(target: target, selector: #selector(FrameTarget.tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        displayLink = link
        link.add(to: .main, forMode: .common)
    }

    private func tick(_ link: CADisplayLink) {
        guard let motion else { return }
        let progress = min(1, (link.timestamp - motion.start) / motion.duration)
        motion.frame(max(0, progress))
        if progress >= 1 {
            stopMotion()
            motion.completion()
        }
    }

    private func finishMotion() {
        guard let motion else { return }
        stopMotion()
        motion.frame(1)
        motion.completion()
    }

    private func stopMotion() {
        displayLink?.invalidate()
        displayLink = nil
        frameTarget = nil
        motion = nil
    }

    func tearDown() {
        disposed = true
        active = false
        finishMotion()
        loadTask?.cancel()
        loadTask = nil
        for task in modelTasks.values { task.cancel() }
        modelTasks.removeAll()
        arView.scene.anchors.removeAll()
        if controller.surface === self { controller.surface = nil }
    }
}

private struct DecisionMaterialNames: Component {
    let names: [String]
}
