import Foundation

/// 游标指向整个候选池，可见槽位只是渲染预算。
nonisolated enum DecisionTurntableLayout {
    struct Placement {
        let slot: Int
        let optionIndex: Int
        let angle: Double
        let radius: Double
        let scale: Double
    }

    static func wrap(_ index: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return (index % count + count) % count
    }

    static func focusedIndex(cursor: Double, count: Int) -> Int? {
        count > 0 ? wrap(Int(cursor.rounded()), count: count) : nil
    }

    static func placements(count: Int, limit: Int, cursor: Double) -> [Placement] {
        let visible = min(count, max(1, limit))
        guard visible > 0 else { return [] }
        let virtual = count > visible
        let first = virtual ? Int(floor(cursor)) - (visible - 1) / 2 : 0
        return (0..<visible).map { offset in
            let logical = first + offset
            let angle = count == 1 ? 0 : Double(logical) - cursor
            let radians = angle * 2 * .pi / Double(visible)
            let wrapped = atan2(sin(radians), cos(radians))
            let seam = min(1, max(0, (.pi - abs(wrapped)) / 0.32))
            let scale = (visible > 8 ? 0.85 : 1) * (virtual ? seam * seam * (3 - 2 * seam) : 1)
            return Placement(
                slot: virtual ? wrap(logical, count: visible) : offset,
                optionIndex: wrap(logical, count: count),
                angle: radians,
                radius: count < 3 ? 1.18 : 1.5,
                scale: scale
            )
        }
    }

    static func browseTarget(index: Int, count: Int, cursor: Double) -> Double {
        guard count > 0 else { return 0 }
        let cycle = Double(count)
        let delta = Double(index) - cursor
        return cursor + delta - (delta / cycle).rounded() * cycle
    }

    static func drawTarget(index: Int, count: Int, limit: Int, cursor: Double, input: DecisionSpinInput) -> Double {
        guard count > 1 else { return Double(index) }
        let cycle = Double(count)
        let turns = (cursor - Double(index)) / cycle
        var target = Double(index) + (input.direction > 0 ? ceil(turns) : floor(turns)) * cycle
        let minimumTravel = Double(min(count, limit)) * (1.8 + input.intensity * 0.5)
        while input.direction * (target - cursor) < minimumTravel {
            target += input.direction * cycle
        }
        return target
    }

    static func spinProgress(_ progress: Double, fromGesture: Bool) -> Double {
        let t = min(1, max(0, progress))
        return fromGesture ? 1 - pow(1 - t, 4) : 1 - pow(1 - t, 5) * (1 + 5 * t)
    }
}

nonisolated struct DecisionSpinInput {
    var velocity: Double = 0

    var fromGesture: Bool { velocity != 0 }
    var direction: Double { velocity > 0 ? -1 : 1 }
    var intensity: Double { min(1, abs(velocity) / 2400) }
}
