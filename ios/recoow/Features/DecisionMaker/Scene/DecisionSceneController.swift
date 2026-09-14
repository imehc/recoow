import Observation
import UIKit

@MainActor
@Observable
final class DecisionSceneController {
    enum Phase {
        case browsing, preparing, spinning, slowing, revealing, result
    }

    var phase: Phase = .browsing
    var focusedOption: DecisionOption?
    var resultOption: DecisionOption?
    var isLoading = true
    var isSceneUnavailable = false

    @ObservationIgnored private(set) var options: [DecisionOption] = []
    @ObservationIgnored private var pendingOptions: [DecisionOption]?
    @ObservationIgnored weak var surface: DecisionSceneSurface?

    var isBusy: Bool {
        switch phase {
        case .preparing, .spinning, .slowing, .revealing: true
        case .browsing, .result: false
        }
    }

    func updateOptions(_ options: [DecisionOption]) {
        if isBusy {
            pendingOptions = options
        } else {
            applyOptions(options)
        }
    }

    private func applyOptions(_ options: [DecisionOption]) {
        guard self.options != options else { return }
        let previousID = focusedOption?.id
        self.options = options
        let index = options.firstIndex { $0.id == previousID } ?? 0
        focusedOption = options.indices.contains(index) ? options[index] : nil
        if resultOption?.id != focusedOption?.id {
            resultOption = nil
            phase = .browsing
        }
        surface?.setOptions(options, focusedIndex: index)
    }

    /// Lock synchronously, before the asynchronous save task can yield, to coalesce repeated taps.
    func prepareDraw() -> Bool {
        guard !isBusy, !options.isEmpty, !isLoading else { return false }
        phase = .preparing
        resultOption = nil
        surface?.prepareDraw()
        return true
    }

    func animateSelection(_ option: DecisionOption, input: DecisionSpinInput) {
        guard phase == .preparing else { return }
        // A remote edit may have arrived just before the save task began. Always land on its actual result.
        if !options.contains(where: { $0.id == option.id }) {
            options.append(option)
            surface?.setOptions(options, focusedIndex: 0)
        }
        phase = .spinning
        if let surface {
            surface.spin(to: option, input: input)
        } else {
            finishSelection(option, announce: false)
        }
    }

    func cancelPreparation() {
        guard phase == .preparing else { return }
        phase = .browsing
        applyPendingOptions()
        surface?.updateControls()
    }

    func finishSelection(_ option: DecisionOption, announce: Bool) {
        resultOption = option
        focusedOption = option
        phase = .result
        applyPendingOptions()
        surface?.updateControls()
        if announce {
            UIAccessibility.post(notification: .announcement, argument: AppLocalization.format("本次选中：%@", option.title))
        }
    }

    private func applyPendingOptions() {
        if let pendingOptions {
            self.pendingOptions = nil
            applyOptions(pendingOptions)
        }
    }

    func browse(_ option: DecisionOption) {
        guard !isBusy, let index = options.firstIndex(where: { $0.id == option.id }) else { return }
        phase = .browsing
        resultOption = nil
        focusedOption = option
        surface?.browse(to: index)
    }

    func beginBrowsing() {
        guard !isBusy else { return }
        phase = .browsing
        resultOption = nil
    }

    func focus(index: Int) {
        guard options.indices.contains(index), focusedOption?.id != options[index].id else { return }
        focusedOption = options[index]
    }
}
